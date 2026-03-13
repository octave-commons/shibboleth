(ns promptbench.pipeline.stages
  "Stage execution engine.

   Stage 0 (Fetch): Download/copy source datasets to data/raw/,
   compute SHA-256 checksums, write stage manifest.

   Stage 1 (Canonicalize): NFKC normalize text, collapse whitespace,
   compute canonical_hash (SHA-256 of normalized text), generate source_id
   (SHA-256 of dataset-id + row-id + canonical-hash-prefix), map source
   labels to taxonomy via taxonomy-mapping, output records with all
   required fields.

   Both stages are idempotent and write manifests per spec §5.3."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [promptbench.pipeline.sources :as sources]
            [promptbench.pipeline.manifest :as manifest])
  (:import [java.security MessageDigest]
           [java.nio.file Files Paths StandardCopyOption]
           [java.text Normalizer Normalizer$Form]))

;; ============================================================
;; Hashing Utilities
;; ============================================================

(defn- sha256-bytes
  "Compute SHA-256 digest of a byte array, return hex string."
  ^String [^bytes data]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md data)
    (let [digest (.digest md)]
      (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

(defn- sha256-file
  "Compute SHA-256 hex string of a file."
  ^String [^String path]
  (let [bytes (Files/readAllBytes (Paths/get path (into-array String [])))]
    (sha256-bytes bytes)))

(defn- sha256-string
  "Compute SHA-256 hex string of a UTF-8 string."
  ^String [^String s]
  (sha256-bytes (.getBytes s "UTF-8")))

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
;; Stage 0: Fetch
;; ============================================================

(defn fetch!
  "Stage 0: Fetch source datasets.

   For each source in :sources, copies/downloads the data to data/raw/
   with a deterministic filename based on the source name.

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
                            ;; Determine the actual source file
                            src-path (or (:path source-data)
                                         (throw (ex-info
                                                  (str "URL fetching not implemented yet. "
                                                       "Source " source-name " requires :path for local files.")
                                                  {:source source-name})))]
                        ;; Copy source to raw directory
                        (io/copy (io/file src-path) (io/file dest-path))
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
                        rows (read-jsonl raw-file)]
                    (map-indexed
                      (fn [idx row]
                        (let [raw-text (or (:prompt row) (get row "prompt") "")
                              lang-str (or (:language row) (get row "language") "en")
                              row-id (or (:row_id row) (get row "row_id") idx)
                              ;; Normalize text
                              canon-text (normalize-text raw-text)
                              ;; Compute canonical hash
                              c-hash (sha256-string canon-text)
                              ;; Compute source ID
                              hash-prefix (subs c-hash 0 (min 16 (count c-hash)))
                              s-id (compute-source-id (name source-name) row-id hash-prefix)
                              ;; Resolve taxonomy labels
                              taxonomy (resolve-taxonomy row source-data)]
                          {:source-id      s-id
                           :canonical-hash c-hash
                           :canonical-text canon-text
                           :canonical-lang (keyword lang-str)
                           :intent-label   (:intent-label taxonomy)
                           :attack-family  (:attack-family taxonomy)
                           :harm-category  (:harm-category taxonomy)
                           :source         {:dataset source-name
                                            :row-id  row-id
                                            :license (:license source-data)}}))
                      rows))))
              (sort sources))
        ;; Write canonical records as EDN
        canon-file (str canon-dir "/canonical-records.edn")
        _ (spit canon-file (pr-str all-records))
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
     :records all-records}))
