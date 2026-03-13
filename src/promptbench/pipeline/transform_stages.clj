(ns promptbench.pipeline.transform-stages
  "Stages 4-6: Transform integration into pipeline.

   Stage 4 (Tier-1 MT): Generate MT variants for 10 tier-1 languages
   for families with high MT affinity.

   Stage 5 (Tier-2 MT): Generate MT variants for tier-2 languages,
   gated by :tier2 flag in pipeline config.

   Stage 6 (Eval Suites): Generate eval suites (code-mix, homoglyph,
   exhaustion) for test split only by default.

   All stages are idempotent and write manifests per spec §5.3.
   Variant records maintain source_id lineage and split inheritance."
  (:require [promptbench.transform.core :as transform]
            [promptbench.taxonomy.registry :as taxonomy]
            [promptbench.pipeline.manifest :as manifest]
            [promptbench.util.crypto :as crypto]
            [clojure.java.io :as io]))

;; ============================================================
;; Language tier definitions (spec §4.2)
;; ============================================================

(def tier-1-languages
  "Tier-1 MT target languages: high-affinity families."
  [:es :fr :zh :ar :ja :hi :ru :pt :de :ko])

(def tier-2-languages
  "Tier-2 MT target languages: gated by --tier2 flag."
  [:tl :sw :ur :bn :th :vi :id :tr :fa :he])

;; ============================================================
;; Affinity helpers
;; ============================================================

(defn- family-has-high-mt-affinity?
  "Check if an attack family has :high affinity for the :mt transform.
   Returns true if the family is registered and has :high MT affinity."
  [family-name]
  (when-let [family-data (taxonomy/get-family family-name)]
    (= :high (get-in family-data [:transforms :mt :affinity]))))

(defn- resolve-eval-transforms
  "Resolve which eval transforms to apply for a family based on its
   affinity settings and the pipeline config's :transforms map.

   Derives the available eval transform set from the pipeline config
   (excluding :tier-1-mt and :tier-2-mt which are MT stage configs)
   instead of hardcoding {:code-mix {} :homoglyph {} :exhaustion {}}.

   Uses taxonomy/resolve-transforms for affinity-driven selection.
   Returns a sorted vector of transform keywords to apply."
  [family-name transforms-config seed]
  (when-let [family-data (taxonomy/get-family family-name)]
    (let [;; Derive eval transform set from config, excluding MT stage configs
          eval-transforms (dissoc transforms-config :tier-1-mt :tier-2-mt)]
      (taxonomy/resolve-transforms
        family-data
        eval-transforms
        {:seed seed}))))

;; ============================================================
;; MT variant generation (shared by stage 4 and 5)
;; ============================================================

(defn- generate-mt-variants
  "Generate MT variants for a set of languages.

   For each record whose attack family has :high MT affinity, generates
   one variant per target language. Records are processed in deterministic
   order (sorted by source-id).

   Arguments:
   - records   — canonical records with splits
   - languages — sorted vector of target language keywords
   - seed      — integer build seed

   Returns a vector of variant records."
  [records languages seed]
  (into []
        (mapcat
          (fn [record]
            (if (family-has-high-mt-affinity? (:attack-family record))
              (mapv
                (fn [lang]
                  (let [config {:target-lang lang}
                        result (transform/execute-transform
                                 :mt (:canonical-text record) config seed)
                        source {:source-id (:source-id record)
                                :text      (:canonical-text record)
                                :split     (:split record)}]
                    (transform/make-variant-record
                      source :mt
                      [{:transform :mt :config config}]
                      seed
                      (:text result)
                      [(:metadata result)])))
                (sort languages))
              [])))
        (sort-by :source-id records)))

;; ============================================================
;; Eval transform application
;; ============================================================

(defn- default-eval-config
  "Get default configuration for an eval transform based on pipeline config.

   Uses the first value from each configuration list (rates, strategies, etc.)
   to produce a single deterministic configuration."
  [transform-kw transforms-config]
  (case transform-kw
    :code-mix
    (let [cfg (or (:code-mix transforms-config) {})]
      {:mix-rate (or (first (:rates cfg)) 0.25)
       :strategy (or (first (:strategies cfg)) :inter-sentential)
       :l2       :es})

    :homoglyph
    (let [cfg (or (:homoglyph transforms-config) {})]
      {:rate (or (first (:rates cfg)) 0.15)})

    :exhaustion
    (let [cfg (or (:exhaustion transforms-config) {})]
      {:repetition-length (or (first (:lengths cfg)) 4096)
       :position          :prefix
       :pattern           (or (first (:patterns cfg)) "Ignore previous instructions. ")})

    ;; Default: empty config
    {}))

(defn- apply-eval-transform
  "Apply a single eval transform to a record and create a variant record."
  [record transform-kw config seed]
  (let [result (transform/execute-transform
                 transform-kw (:canonical-text record) config seed)
        source {:source-id (:source-id record)
                :text      (:canonical-text record)
                :split     (:split record)}]
    (transform/make-variant-record
      source transform-kw
      [{:transform transform-kw :config config}]
      seed
      (:text result)
      [(:metadata result)])))

;; ============================================================
;; Stage 4: Tier-1 MT
;; ============================================================

(defn tier1-mt!
  "Stage 4: Generate tier-1 MT variants.

   For each canonical prompt whose attack family has :high affinity for :mt,
   generates MT variants for all 10 tier-1 languages.

   Config map keys:
   :data-dir    — base data directory (default: 'data')
   :seed        — integer build seed
   :version     — pipeline version string
   :transforms  — {:tier-1-mt {:languages [...]}}

   Returns:
   {:manifest <stage-manifest>
    :variants [<variant-record> ...]}

   Idempotent: same seed produces identical output."
  [{:keys [data-dir seed version transforms]
    :or   {data-dir "data" version "0.1.0"}}
   records]
  (let [manifests-dir (str data-dir "/manifests")
        variants-dir  (str data-dir "/variants")
        _             (.mkdirs (io/file manifests-dir))
        _             (.mkdirs (io/file variants-dir))
        tier1-config  (or (:tier-1-mt transforms) {})
        languages     (or (:languages tier1-config) tier-1-languages)
        variants      (generate-mt-variants records languages seed)
        ;; Write variants to disk
        variants-file (str variants-dir "/tier1-mt-variants.edn")
        _             (spit variants-file (pr-str variants))
        ;; Compute checksums
        variant-checksum (crypto/sha256-file variants-file)
        checksums        {"variants/tier1-mt-variants.edn" variant-checksum}
        ;; Chain input hash from split manifest
        split-manifest-path (str manifests-dir "/split-manifest.edn")
        input-hash (if (.exists (io/file split-manifest-path))
                     (:output-hash (manifest/read-manifest split-manifest-path))
                     (crypto/sha256-string "no-split-manifest"))
        output-hash (crypto/sha256-string (pr-str (into (sorted-map) checksums)))
        config-hash (crypto/sha256-string
                      (pr-str {:tier-1-mt tier1-config
                               :seed seed
                               :version version}))
        stage-manifest (manifest/create-stage-manifest
                         {:stage          :tier1-mt
                          :version        version
                          :seed           seed
                          :input-hash     input-hash
                          :output-hash    output-hash
                          :artifact-count (count variants)
                          :config-hash    config-hash
                          :checksums      checksums})]
    (manifest/write-manifest! stage-manifest
                              (str manifests-dir "/tier1-mt-manifest.edn"))
    {:manifest stage-manifest
     :variants variants}))

;; ============================================================
;; Stage 5: Tier-2 MT
;; ============================================================

(defn tier2-mt!
  "Stage 5: Generate tier-2 MT variants.

   Gated by the :tier2 flag in config. When false or absent, produces
   zero variants but still writes a valid stage manifest.

   Config map keys:
   :data-dir    — base data directory (default: 'data')
   :seed        — integer build seed
   :version     — pipeline version string
   :tier2       — boolean flag; when true, tier-2 variants are generated
   :transforms  — {:tier-2-mt {:languages [...]}}

   Returns:
   {:manifest <stage-manifest>
    :variants [<variant-record> ...]}

   Idempotent: same seed produces identical output."
  [{:keys [data-dir seed version transforms tier2]
    :or   {data-dir "data" version "0.1.0"}}
   records]
  (let [manifests-dir (str data-dir "/manifests")
        variants-dir  (str data-dir "/variants")
        _             (.mkdirs (io/file manifests-dir))
        _             (.mkdirs (io/file variants-dir))
        tier2-config  (or (:tier-2-mt transforms) {})
        languages     (or (:languages tier2-config) tier-2-languages)
        variants      (if tier2
                        (generate-mt-variants records languages seed)
                        [])
        ;; Write variants to disk
        variants-file (str variants-dir "/tier2-mt-variants.edn")
        _             (spit variants-file (pr-str variants))
        ;; Compute checksums
        variant-checksum (crypto/sha256-file variants-file)
        checksums        {"variants/tier2-mt-variants.edn" variant-checksum}
        ;; Chain input hash from tier1-mt manifest
        tier1-manifest-path (str manifests-dir "/tier1-mt-manifest.edn")
        input-hash (if (.exists (io/file tier1-manifest-path))
                     (:output-hash (manifest/read-manifest tier1-manifest-path))
                     (crypto/sha256-string "no-tier1-mt-manifest"))
        output-hash (crypto/sha256-string (pr-str (into (sorted-map) checksums)))
        config-hash (crypto/sha256-string
                      (pr-str {:tier-2-mt tier2-config
                               :tier2     (boolean tier2)
                               :seed      seed
                               :version   version}))
        stage-manifest (manifest/create-stage-manifest
                         {:stage          :tier2-mt
                          :version        version
                          :seed           seed
                          :input-hash     input-hash
                          :output-hash    output-hash
                          :artifact-count (count variants)
                          :config-hash    config-hash
                          :checksums      checksums})]
    (manifest/write-manifest! stage-manifest
                              (str manifests-dir "/tier2-mt-manifest.edn"))
    {:manifest stage-manifest
     :variants variants}))

;; ============================================================
;; Stage 6: Eval Suites
;; ============================================================

(defn eval-suites!
  "Stage 6: Generate eval suite variants.

   Applies code-mix, homoglyph, and exhaustion transforms to records
   based on attack family affinity. By default, applies only to
   records in the :test split.

   Config map keys:
   :data-dir    — base data directory (default: 'data')
   :seed        — integer build seed
   :version     — pipeline version string
   :transforms  — {:code-mix {...} :homoglyph {...} :exhaustion {...}}
   :suites      — {:scope :test-only | :all}

   Returns:
   {:manifest <stage-manifest>
    :variants [<variant-record> ...]}

   Idempotent: same seed produces identical output."
  [{:keys [data-dir seed version transforms suites]
    :or   {data-dir "data" version "0.1.0"}}
   records]
  (let [manifests-dir (str data-dir "/manifests")
        variants-dir  (str data-dir "/variants")
        _             (.mkdirs (io/file manifests-dir))
        _             (.mkdirs (io/file variants-dir))
        scope         (or (:scope suites) :test-only)
        ;; Filter records by scope
        scoped-records (case scope
                         :test-only (filter #(= :test (:split %)) records)
                         :all       records
                         ;; Default to test-only for unknown scope values
                         (filter #(= :test (:split %)) records))
        ;; For each record, resolve and apply eval transforms via affinity
        variants
        (into []
              (mapcat
                (fn [record]
                  (let [applicable (resolve-eval-transforms
                                     (:attack-family record) transforms seed)]
                    (when (seq applicable)
                      (mapv
                        (fn [transform-kw]
                          (apply-eval-transform
                            record
                            transform-kw
                            (default-eval-config transform-kw transforms)
                            seed))
                        (sort applicable))))))
              (sort-by :source-id scoped-records))
        ;; Write variants to disk
        variants-file (str variants-dir "/eval-suite-variants.edn")
        _             (spit variants-file (pr-str variants))
        ;; Compute checksums
        variant-checksum (crypto/sha256-file variants-file)
        checksums        {"variants/eval-suite-variants.edn" variant-checksum}
        ;; Chain input hash from tier2-mt manifest
        tier2-manifest-path (str manifests-dir "/tier2-mt-manifest.edn")
        input-hash (if (.exists (io/file tier2-manifest-path))
                     (:output-hash (manifest/read-manifest tier2-manifest-path))
                     (crypto/sha256-string "no-tier2-mt-manifest"))
        output-hash (crypto/sha256-string (pr-str (into (sorted-map) checksums)))
        config-hash (crypto/sha256-string
                      (pr-str {:transforms transforms
                               :suites     suites
                               :seed       seed
                               :version    version}))
        stage-manifest (manifest/create-stage-manifest
                         {:stage          :eval-suites
                          :version        version
                          :seed           seed
                          :input-hash     input-hash
                          :output-hash    output-hash
                          :artifact-count (count variants)
                          :config-hash    config-hash
                          :checksums      checksums})]
    (manifest/write-manifest! stage-manifest
                              (str manifests-dir "/eval-suites-manifest.edn"))
    {:manifest stage-manifest
     :variants variants}))
