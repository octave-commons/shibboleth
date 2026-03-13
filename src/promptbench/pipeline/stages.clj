(ns promptbench.pipeline.stages
  "Stage execution engine.

   Stage 0 (Fetch): Download/copy source datasets to data/raw/,
   compute SHA-256 checksums, write stage manifest.

   Stage 1 (Canonicalize): NFKC normalize text, collapse whitespace,
   compute canonical_hash (SHA-256 of normalized text), generate source_id
   (SHA-256 of dataset-id + row-id + canonical-hash-prefix), map source
   labels to taxonomy via taxonomy-mapping, output records with all
   required fields.

   Stage 2 (Embed + Cluster): Embed all canonical prompts via
   sentence-transformers, cluster via HDBSCAN, assign cluster_ids.
   No records dropped.

   Stage 3 (Split): Cluster-level stratified split (70/15/15) with
   enforced disjointness invariant.

   All stages are idempotent and write manifests per spec §5.3."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [promptbench.pipeline.sources :as sources]
            [promptbench.pipeline.manifest :as manifest]
            [promptbench.pipeline.splitter :as splitter]
            [promptbench.python.embed :as embed]
            [promptbench.python.cluster :as cluster]
            [promptbench.python.parquet :as parquet]
            [promptbench.util.crypto :as crypto])
  (:import [java.nio.file Files Paths StandardCopyOption]
           [java.text Normalizer Normalizer$Form]))

;; ============================================================
;; Hashing Utilities (delegated to promptbench.util.crypto)
;; ============================================================

(def ^:private sha256-file crypto/sha256-file)
(def ^:private sha256-string crypto/sha256-string)

;; ============================================================
;; Text Normalization
;; ============================================================

(defn normalize-text
  "Apply NFKC normalization and whitespace collapse.

   1. NFKC Unicode normalization (decomposes ligatures, fullwidth chars, etc.)
   2. Replace all whitespace sequences (tabs, newlines, multiple spaces) with single space
   3. Trim leading/trailing whitespace"
  ^String [^String text]
  (-> text
      (Normalizer/normalize Normalizer$Form/NFKC)
      (str/replace #"\s+" " ")
      str/trim))

;; ============================================================
;; Canonical Hashing
;; ============================================================

(defn canonical-hash
  "Compute the canonical hash of a text string.
   Returns SHA-256 hex string of the normalized text.

   The text is first NFKC-normalized and whitespace-collapsed,
   then hashed. This ensures the same logical text always produces
   the same hash regardless of encoding or whitespace variations."
  ^String [^String text]
  (sha256-string (normalize-text text)))

;; ============================================================
;; Source ID Generation
;; ============================================================

(defn compute-source-id
  "Generate a deterministic source_id from (dataset-id, row-id, canonical-hash-prefix).

   source_id = SHA-256(dataset-id + '|' + row-id + '|' + canonical-hash-prefix)

   This ensures uniqueness across datasets and rows while being
   fully deterministic from the input components."
  ^String [^String dataset-id row-id ^String canonical-hash-prefix]
  (let [input (str dataset-id "|" row-id "|" canonical-hash-prefix)]
    (sha256-string input)))

;; ============================================================
;; Taxonomy Resolution
;; ============================================================

(defn resolve-taxonomy
  "Resolve taxonomy labels from a raw record using source taxonomy-mapping.

   Returns a map with resolved :harm-category, :attack-family, and :intent-label.
   Unmapped labels resolve to :unmapped with a warning logged."
  [record source-data]
  (let [mapping (:taxonomy-mapping source-data)
        raw-harm (get record :harm_category (get record "harm_category"))
        raw-family (get record :family (get record "family"))
        ;; Resolve harm category
        harm-mapping (get mapping :harm_category {})
        resolved-harm (get harm-mapping raw-harm)
        ;; Resolve attack family
        family-mapping (get mapping :family {})
        resolved-family (get family-mapping raw-family)
        ;; Determine intent label based on harm category
        intent (cond
                 (= raw-harm "benign") :benign
                 (or (= raw-family "benign") (= resolved-harm :benign)) :benign
                 :else :adversarial)]
    (when (and raw-harm (nil? resolved-harm))
      (binding [*out* *err*]
        (println (str "WARNING: Unmapped harm_category '" raw-harm
                      "' in source " (:description source-data)))))
    (when (and raw-family (nil? resolved-family))
      (binding [*out* *err*]
        (println (str "WARNING: Unmapped family '" raw-family
                      "' in source " (:description source-data)))))
    {:harm-category (or resolved-harm :unmapped)
     :attack-family (or resolved-family :unmapped)
     :intent-label  intent}))

;; ============================================================
;; JSONL Reading
;; ============================================================

(defn- read-jsonl
  "Read a JSONL file and return a vector of maps with string keys converted to keywords."
  [^String path]
  (with-open [rdr (io/reader path)]
    (into []
          (comp (map str/trim)
                (remove str/blank?)
                (map #(json/parse-string % true)))
          (line-seq rdr))))

;; ============================================================
;; CSV Reading
;; ============================================================

(defn- read-csv-file
  "Read a CSV file and return a vector of maps.
   The first row is treated as headers, converted to keywords.
   Returns maps with keyword keys."
  [^String path]
  (with-open [rdr (io/reader path)]
    (let [data (csv/read-csv rdr)
          headers (map keyword (first data))
          rows (rest data)]
      (into []
            (map (fn [row]
                   (zipmap headers row)))
            rows))))

;; ============================================================
;; Parquet Reading (via Python bridge)
;; ============================================================

(defn- read-parquet-file
  "Read a Parquet file and return a vector of maps with keyword keys.
   Uses polars via the Python bridge."
  [^String path]
  (parquet/read-parquet path))

;; ============================================================
;; Format-Dispatched Reading
;; ============================================================

(defn- read-source-file
  "Read a source file from data/raw/ based on the source's declared format.

   Dispatches to:
   - :jsonl   → read-jsonl (JSON lines)
   - :csv     → read-csv-file (CSV with header row)
   - :parquet → read-parquet-file (Parquet via polars/libpython-clj)
   - :edn     → clojure.edn/read-string

   Returns a vector of maps with keyword keys."
  [^String path format]
  (case format
    :jsonl   (read-jsonl path)
    :csv     (read-csv-file path)
    :parquet (read-parquet-file path)
    :edn     (clojure.edn/read-string (slurp path))
    (throw (ex-info (str "Unsupported source format: " format)
                    {:format format :path path}))))

;; ============================================================
;; Field Extraction from Source Records
;; ============================================================

(defn- extract-text-field
  "Extract the text field from a source record using field-mapping.

   Checks :field-mapping for a key that maps to :text.
   Falls back to :prompt or 'prompt' for backwards compatibility."
  [row source-data]
  (let [field-mapping (:field-mapping source-data)
        ;; Find which source column maps to :text
        text-source-key (when field-mapping
                          (some (fn [[k v]] (when (= v :text) k)) field-mapping))]
    (if text-source-key
      ;; Use field-mapping: try keyword first, then string
      (or (get row text-source-key)
          (get row (name text-source-key))
          "")
      ;; Fallback: legacy JSONL convention (prompt field)
      (or (:prompt row) (get row "prompt") ""))))

(defn- extract-language-field
  "Extract the language field from a source record.

   Checks :field-mapping for a key that maps to :language.
   Falls back to :default-language from source-data, then 'en'."
  [row source-data]
  (let [field-mapping (:field-mapping source-data)
        lang-source-key (when field-mapping
                          (some (fn [[k v]] (when (= v :language) k)) field-mapping))
        default-lang (or (:default-language source-data) "en")]
    (if lang-source-key
      ;; Use field-mapping: try keyword first, then string
      (let [val (or (get row lang-source-key)
                    (get row (name lang-source-key)))]
        (if (and val (not (str/blank? (str val))))
          (str val)
          default-lang))
      ;; Fallback: legacy JSONL convention (language field), then default
      (or (some-> (or (:language row) (get row "language"))
                  str
                  (#(when-not (str/blank? %) %)))
          default-lang))))

(defn- extract-harm-category-field
  "Extract the harm category field from a source record using field-mapping.

   Checks :field-mapping for a key mapping to :harm-category.
   Falls back to :harm_category for backwards compatibility."
  [row source-data]
  (let [field-mapping (:field-mapping source-data)
        harm-source-key (when field-mapping
                          (some (fn [[k v]] (when (= v :harm-category) k)) field-mapping))]
    (if harm-source-key
      (or (get row harm-source-key)
          (get row (name harm-source-key)))
      ;; Fallback: legacy convention
      (or (:harm_category row) (get row "harm_category")))))

;; ============================================================
;; Cross-Source Deduplication
;; ============================================================

(defn deduplicate-cross-source
  "Deduplicate canonical records across sources by canonical-hash.

   **Dedup Policy** (documented per VAL-CORPUS-006):
   When the same text (identical after NFKC normalization and whitespace
   collapse) appears in multiple sources:
   1. Curated sources take precedence over public sources
      (source keyword contains 'curated' prefix).
   2. Among same-type sources (both curated or both public),
      the record from the alphabetically-first source is kept.
   3. Deterministic: same input always produces same output.

   Rationale: Curated sources carry more precise attack family
   classifications. Keeping curated provenance preserves the
   taxonomy granularity that curated data provides.

   Arguments:
   - records — vector of canonical records (output of canonicalize!)

   Returns:
   {:records     [<deduplicated-records>]
    :duplicates  [{:canonical-hash ... :kept-source ... :removed [{:source ... :source-id ...}]}]
    :stats       {:total-input int :total-output int :removed int}}"
  [records]
  (let [;; Group by canonical-hash
        by-hash (group-by :canonical-hash records)
        ;; Process each group
        results
        (reduce-kv
          (fn [acc hash group]
            (if (= 1 (count group))
              ;; No duplicates — keep as-is
              (update acc :records conj (first group))
              ;; Duplicates found — apply dedup policy
              (let [curated? (fn [r]
                               (let [src-name (name (get-in r [:source :dataset] :unknown))]
                                 (str/starts-with? src-name "curated")))
                    ;; Sort: curated first, then alphabetically by source name
                    sorted-group (sort-by (fn [r]
                                            [(if (curated? r) 0 1)
                                             (name (get-in r [:source :dataset] :unknown))])
                                          group)
                    keeper (first sorted-group)
                    removed (rest sorted-group)]
                (-> acc
                    (update :records conj keeper)
                    (update :duplicates conj
                            {:canonical-hash hash
                             :kept-source    (get-in keeper [:source :dataset])
                             :removed        (mapv (fn [r]
                                                     {:source    (get-in r [:source :dataset])
                                                      :source-id (:source-id r)})
                                                   removed)})))))
          {:records [] :duplicates []}
          (into (sorted-map) by-hash))
        total-input (count records)
        total-output (count (:records results))
        removed (- total-input total-output)]
    (when (pos? removed)
      (binding [*out* *err*]
        (println (str "DEDUP: Removed " removed " cross-source duplicate(s). "
                      "Policy: curated sources preferred."))))
    (assoc results
           :stats {:total-input total-input
                   :total-output total-output
                   :removed removed})))

;; ============================================================
;; Stage 0: Fetch
;; ============================================================

(defn- download-url!
  "Download a file from a URL to a local destination path.

   Uses clj-http with :as :byte-array to handle both text (CSV) and
   binary (Parquet) formats correctly. Follows redirects.

   Throws on HTTP errors (non-2xx status)."
  [^String url ^String dest-path]
  (let [resp (http/get url {:as :byte-array
                            :redirect-strategy :lax
                            :socket-timeout 60000
                            :connection-timeout 30000})]
    (when-not (<= 200 (:status resp) 299)
      (throw (ex-info (str "HTTP fetch failed with status " (:status resp)
                           " for URL: " url)
                      {:url url :status (:status resp)})))
    (io/copy (:body resp) (io/file dest-path))
    dest-path))

(defn fetch!
  "Stage 0: Fetch source datasets.

   For each source in :sources, copies/downloads the data to data/raw/
   with a deterministic filename based on the source name.

   Supports:
   - :path sources — copies local files
   - :url sources  — downloads from HTTP(S) URLs (CSV, Parquet, JSONL)

   Config map keys:
   :sources   — vector of source keywords to fetch
   :data-dir  — base data directory (default: 'data')
   :seed      — integer build seed
   :version   — pipeline version string

   Returns:
   {:manifest <stage-manifest>
    :files    {source-kw file-path ...}}

   Idempotent: re-running with the same config produces identical output."
  [{:keys [sources data-dir seed version]
    :or   {data-dir "data" version "0.1.0"}}]
  (let [raw-dir (str data-dir "/raw")
        manifests-dir (str data-dir "/manifests")
        _ (.mkdirs (io/file raw-dir))
        _ (.mkdirs (io/file manifests-dir))
        files (into {}
                    (for [source-name (sort sources)]
                      (let [source-data (sources/get-source source-name)
                            _ (when-not source-data
                                (throw (ex-info (str "Source not found: " source-name)
                                                {:source source-name})))
                            fmt (name (:format source-data))
                            dest-filename (str (name source-name) "." fmt)
                            dest-path (str raw-dir "/" dest-filename)
                            src-path (:path source-data)
                            src-url  (:url source-data)]
                        ;; Fetch: prefer path (local), fallback to URL download
                        (cond
                          ;; Local path — copy file
                          (and src-path (.exists (io/file src-path)))
                          (io/copy (io/file src-path) (io/file dest-path))

                          ;; URL — download from remote
                          src-url
                          (do
                            (binding [*out* *err*]
                              (println (str "Fetching " (name source-name)
                                            " from " src-url "...")))
                            (download-url! src-url dest-path))

                          ;; Local path specified but file doesn't exist
                          src-path
                          (throw (ex-info (str "Source file not found: " src-path
                                               " for source " source-name)
                                          {:source source-name :path src-path}))

                          ;; Neither path nor URL
                          :else
                          (throw (ex-info (str "Source " source-name
                                               " has neither :path nor :url")
                                          {:source source-name})))
                        [source-name dest-path])))
        ;; Compute checksums for all fetched files
        checksums (into {}
                        (for [[source-name file-path] (sort-by key files)]
                          (let [rel-path (str "raw/" (name source-name) "."
                                              (name (:format (sources/get-source source-name))))]
                            [rel-path (sha256-file file-path)])))
        ;; Compute output hash (hash of sorted checksums)
        output-hash (sha256-string
                      (pr-str (into (sorted-map) checksums)))
        ;; Compute config hash
        config-hash (sha256-string
                      (pr-str {:sources (sort sources)
                               :seed seed
                               :version version}))
        ;; Create manifest
        stage-manifest (manifest/create-stage-manifest
                         {:stage :fetch
                          :version version
                          :seed seed
                          :input-hash config-hash
                          :output-hash output-hash
                          :artifact-count (count files)
                          :config-hash config-hash
                          :checksums checksums})]
    ;; Write manifest
    (manifest/write-manifest! stage-manifest
                              (str manifests-dir "/fetch-manifest.edn"))
    {:manifest stage-manifest
     :files files}))

;; ============================================================
;; Stage 1: Canonicalize
;; ============================================================

(defn canonicalize!
  "Stage 1: Canonicalize source records.

   Reads JSONL files from data/raw/, applies NFKC normalization,
   collapses whitespace, computes canonical hashes, generates source IDs,
   and maps taxonomy labels. Writes canonical records and stage manifest.

   Config map keys:
   :sources   — vector of source keywords to canonicalize
   :data-dir  — base data directory (default: 'data')
   :seed      — integer build seed
   :version   — pipeline version string

   Returns:
   {:manifest <stage-manifest>
    :records  [<canonical-record> ...]}

   Output record shape (spec §5.1):
   {:source-id      'sha256:...'
    :canonical-hash 'sha256:...'
    :canonical-text '...'
    :canonical-lang :en
    :intent-label   :adversarial
    :attack-family  :persona-injection
    :harm-category  :identity-manipulation
    :source         {:dataset :source-name :row-id 42 :license :gpl-3.0}}

   Idempotent: same input produces identical output."
  [{:keys [sources data-dir seed version]
    :or   {data-dir "data" version "0.1.0"}}]
  (let [raw-dir (str data-dir "/raw")
        canon-dir (str data-dir "/canonicalized")
        manifests-dir (str data-dir "/manifests")
        _ (.mkdirs (io/file canon-dir))
        _ (.mkdirs (io/file manifests-dir))
        ;; Process each source
        all-records
        (into []
              (mapcat
                (fn [source-name]
                  (let [source-data (sources/get-source source-name)
                        _ (when-not source-data
                            (throw (ex-info (str "Source not found: " source-name)
                                            {:source source-name})))
                        fmt (:format source-data)
                        raw-file (str raw-dir "/" (name source-name) "." (name fmt))
                        _ (when-not (.exists (io/file raw-file))
                            (throw (ex-info (str "Raw file not found: " raw-file
                                                  ". Run fetch! first.")
                                            {:source source-name :file raw-file})))
                        rows (read-source-file raw-file fmt)
                        ;; Check if this is a toxicchat-style source (has toxicity + jailbreaking columns)
                        has-toxicity-cols? (and (contains? (:schema source-data) :toxicity)
                                               (contains? (:schema source-data) :jailbreaking))]
                    (map-indexed
                      (fn [idx row]
                        (let [raw-text (extract-text-field row source-data)
                              lang-str (extract-language-field row source-data)
                              raw-harm (extract-harm-category-field row source-data)
                              row-id (or (:row_id row) (get row "row_id") idx)
                              ;; Normalize text
                              canon-text (normalize-text (str raw-text))
                              ;; Compute canonical hash
                              c-hash (sha256-string canon-text)
                              ;; Compute source ID
                              hash-prefix (subs c-hash 0 (min 16 (count c-hash)))
                              s-id (compute-source-id (name source-name) row-id hash-prefix)
                              ;; Build a record with the harm category field for taxonomy resolution
                              record-for-taxonomy (cond-> row
                                                    raw-harm (assoc :harm_category raw-harm))
                              ;; Resolve taxonomy labels
                              taxonomy (resolve-taxonomy record-for-taxonomy source-data)
                              ;; For ToxicChat-style sources, derive intent from toxicity/jailbreaking
                              intent-label (if has-toxicity-cols?
                                             (let [get-int (fn [row k]
                                                             (let [v (if (contains? row k)
                                                                       (get row k)
                                                                       (get row (name k)))]
                                                               (cond (nil? v) 0
                                                                     (string? v) (or (parse-long v) 0)
                                                                     (number? v) (long v)
                                                                     :else 0)))
                                                   toxicity     (get-int row :toxicity)
                                                   jailbreaking (get-int row :jailbreaking)]
                                               (if (or (= 1 jailbreaking) (= 1 toxicity))
                                                 :adversarial
                                                 :benign))
                                             (:intent-label taxonomy))]
                          {:source-id      s-id
                           :canonical-hash c-hash
                           :canonical-text canon-text
                           :canonical-lang (keyword lang-str)
                           :intent-label   intent-label
                           :attack-family  (:attack-family taxonomy)
                           :harm-category  (:harm-category taxonomy)
                           :source         {:dataset source-name
                                            :row-id  row-id
                                            :license (:license source-data)}}))
                      rows))))
              (sort sources))
        ;; Deduplicate cross-source records (VAL-CORPUS-006)
        dedup-result (deduplicate-cross-source all-records)
        all-records (:records dedup-result)
        dedup-report {:duplicates (:duplicates dedup-result)
                      :stats (:stats dedup-result)}
        ;; Write canonical records as EDN
        canon-file (str canon-dir "/canonical-records.edn")
        _ (spit canon-file (pr-str all-records))
        ;; Write dedup report if any duplicates found
        _ (when (seq (:duplicates dedup-report))
            (spit (str canon-dir "/dedup-report.edn") (pr-str dedup-report)))
        ;; Compute checksums
        canon-checksum (sha256-file canon-file)
        checksums {"canonicalized/canonical-records.edn" canon-checksum}
        ;; Compute hashes
        ;; Read fetch manifest for input hash chaining
        fetch-manifest-path (str manifests-dir "/fetch-manifest.edn")
        input-hash (if (.exists (io/file fetch-manifest-path))
                     (:output-hash (manifest/read-manifest fetch-manifest-path))
                     (sha256-string "no-fetch-manifest"))
        output-hash (sha256-string (pr-str (into (sorted-map) checksums)))
        config-hash (sha256-string
                      (pr-str {:sources (sort sources)
                               :seed seed
                               :version version
                               :normalization :nfkc
                               :whitespace :collapse
                               :hash-algo :sha256}))
        ;; Create manifest
        stage-manifest (manifest/create-stage-manifest
                         {:stage :canonicalize
                          :version version
                          :seed seed
                          :input-hash input-hash
                          :output-hash output-hash
                          :artifact-count (count all-records)
                          :config-hash config-hash
                          :checksums checksums})]
    ;; Write manifest
    (manifest/write-manifest! stage-manifest
                              (str manifests-dir "/canonicalize-manifest.edn"))
    {:manifest stage-manifest
     :records all-records
     :dedup-report dedup-report}))

;; ============================================================
;; Stage 2: Embed + Cluster
;; ============================================================

(defn embed-cluster!
  "Stage 2: Embed all canonical prompts and cluster them.

   Embeds each prompt's canonical text using sentence-transformers,
   then clusters embeddings using HDBSCAN. No records are dropped.

   Config map keys:
   :records    — vector of canonical records from Stage 1
   :data-dir   — base data directory (default: 'data')
   :seed       — integer build seed
   :version    — pipeline version string
   :embedding  — {:model str, :batch-size int}
   :clustering — {:min-cluster-size int, :metric str}

   Returns:
   {:manifest <stage-manifest>
    :records  [<record-with-embedding-and-cluster-id> ...]}

   Each output record gains :embedding (vector of doubles) and
   :cluster-id (integer >= -1, where -1 = noise).

   Idempotent: same input + seed produces identical output."
  [{:keys [records data-dir seed version embedding clustering]
    :or   {data-dir "data" version "0.1.0"}}]
  (let [manifests-dir (str data-dir "/manifests")
        embedded-dir (str data-dir "/embedded")
        _ (.mkdirs (io/file manifests-dir))
        _ (.mkdirs (io/file embedded-dir))
        ;; Extract texts for embedding (use canonical-text)
        texts (mapv :canonical-text records)
        model-name (or (:model embedding) "intfloat/multilingual-e5-large")
        batch-size (or (:batch-size embedding) 256)
        ;; Embed all texts
        embeddings (embed/embed-batch texts model-name :batch-size batch-size)
        ;; Cluster embeddings
        min-cluster-size (or (:min-cluster-size clustering) 5)
        metric (or (:metric clustering) "cosine")
        labels (cluster/cluster-embeddings embeddings
                 :min-cluster-size min-cluster-size
                 :metric metric)
        ;; Attach embeddings and cluster-ids to records
        enriched-records (mapv (fn [record emb label]
                                 (assoc record
                                        :embedding (vec emb)
                                        :cluster-id (long label)))
                               records embeddings labels)
        ;; Write enriched records as EDN (without embeddings for size)
        cluster-file (str embedded-dir "/cluster-assignments.edn")
        cluster-data (mapv #(select-keys % [:source-id :cluster-id]) enriched-records)
        _ (spit cluster-file (pr-str cluster-data))
        ;; Compute checksums and manifest
        cluster-checksum (sha256-file cluster-file)
        checksums {"embedded/cluster-assignments.edn" cluster-checksum}
        ;; Chain input hash from canonicalize manifest
        canon-manifest-path (str manifests-dir "/canonicalize-manifest.edn")
        input-hash (if (.exists (io/file canon-manifest-path))
                     (:output-hash (manifest/read-manifest canon-manifest-path))
                     (sha256-string "no-canonicalize-manifest"))
        output-hash (sha256-string (pr-str (into (sorted-map) checksums)))
        config-hash (sha256-string
                      (pr-str {:embedding {:model model-name
                                           :batch-size batch-size}
                               :clustering {:min-cluster-size min-cluster-size
                                            :metric metric}
                               :seed seed
                               :version version}))
        stage-manifest (manifest/create-stage-manifest
                         {:stage :embed-cluster
                          :version version
                          :seed seed
                          :input-hash input-hash
                          :output-hash output-hash
                          :artifact-count (count enriched-records)
                          :config-hash config-hash
                          :checksums checksums})]
    (manifest/write-manifest! stage-manifest
                              (str manifests-dir "/embed-cluster-manifest.edn"))
    {:manifest stage-manifest
     :records enriched-records}))

;; ============================================================
;; Stage 3: Split
;; ============================================================

(defn split!
  "Stage 3: Cluster-level stratified split.

   Assigns each prompt to exactly one of :train, :dev, :test splits.
   The KEY INVARIANT: no cluster ID appears in more than one split.

   Config map keys:
   :records  — vector of records from Stage 2 (with :cluster-id)
   :data-dir — base data directory (default: 'data')
   :seed     — integer build seed
   :version  — pipeline version string
   :split    — {:train 0.70 :dev 0.15 :test 0.15
                :stratify-by [:intent-label :attack-family :canonical-lang]
                :constraint :cluster-disjoint}

   Returns:
   {:manifest <stage-manifest>
    :records  [<record-with-split> ...]}

   Idempotent: same input + seed produces identical output."
  [{:keys [records data-dir seed version split]
    :or   {data-dir "data" version "0.1.0"}}]
  (let [manifests-dir (str data-dir "/manifests")
        split-dir (str data-dir "/split")
        _ (.mkdirs (io/file manifests-dir))
        _ (.mkdirs (io/file split-dir))
        ;; Perform cluster-disjoint split
        split-config (or split {:train 0.70 :dev 0.15 :test 0.15
                                :stratify-by [:intent-label :attack-family :canonical-lang]
                                :constraint :cluster-disjoint})
        split-records (splitter/split-clusters records split-config seed)
        ;; Verify disjointness invariant
        disjointness (splitter/verify-disjointness split-records)
        _ (when-not (:passed disjointness)
            (throw (ex-info "FATAL: cluster leakage detected — this is a bug in the splitter"
                            {:leaks (:leaks disjointness)})))
        ;; Write split assignments as EDN
        split-file (str split-dir "/split-assignments.edn")
        split-data (mapv #(select-keys % [:source-id :cluster-id :split]) split-records)
        _ (spit split-file (pr-str split-data))
        ;; Checksums and manifest
        split-checksum (sha256-file split-file)
        checksums {"split/split-assignments.edn" split-checksum}
        embed-manifest-path (str manifests-dir "/embed-cluster-manifest.edn")
        input-hash (if (.exists (io/file embed-manifest-path))
                     (:output-hash (manifest/read-manifest embed-manifest-path))
                     (sha256-string "no-embed-cluster-manifest"))
        output-hash (sha256-string (pr-str (into (sorted-map) checksums)))
        config-hash (sha256-string
                      (pr-str {:split split-config
                               :seed seed
                               :version version}))
        stage-manifest (manifest/create-stage-manifest
                         {:stage :split
                          :version version
                          :seed seed
                          :input-hash input-hash
                          :output-hash output-hash
                          :artifact-count (count split-records)
                          :config-hash config-hash
                          :checksums checksums})]
    (manifest/write-manifest! stage-manifest
                              (str manifests-dir "/split-manifest.edn"))
    {:manifest stage-manifest
     :records split-records}))
