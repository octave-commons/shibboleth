(ns promptbench.pipeline.manifest
  "Manifest generation, writing, and reading.

   Every pipeline stage produces a manifest with:
   :stage, :version, :started-at, :completed-at, :seed,
   :input-hash, :output-hash, :artifact-count, :config-hash, :checksums.

   Manifests are written as EDN files and are the backbone of the
   provenance and reproducibility system (spec §5.3)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.time Instant]))

;; ============================================================
;; Creation
;; ============================================================

(defn create-stage-manifest
  "Create a stage manifest map with all required fields.

   `opts` must include:
   :stage          — keyword stage name (e.g. :fetch, :canonicalize)
   :version        — string version of the pipeline
   :seed           — integer build seed
   :input-hash     — SHA-256 hex string of input data/config
   :output-hash    — SHA-256 hex string of output artifacts
   :artifact-count — number of output artifacts/records
   :config-hash    — SHA-256 hex string of stage configuration
   :checksums      — map of filename -> SHA-256 hex string

   Automatically adds :started-at and :completed-at timestamps."
  [{:keys [stage version seed input-hash output-hash
           artifact-count config-hash checksums]}]
  (let [now (str (Instant/now))]
    {:stage          stage
     :version        version
     :started-at     now
     :completed-at   now
     :seed           seed
     :input-hash     input-hash
     :output-hash    output-hash
     :artifact-count artifact-count
     :config-hash    config-hash
     :checksums      (or checksums {})}))

;; ============================================================
;; I/O
;; ============================================================

(defn write-manifest!
  "Write a manifest map to an EDN file at `path`.
   Creates parent directories if they don't exist."
  [manifest path]
  (let [f (io/file path)]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str manifest))))

(defn read-manifest
  "Read a manifest from an EDN file at `path`.
   Returns the manifest map."
  [path]
  (edn/read-string (slurp path)))
