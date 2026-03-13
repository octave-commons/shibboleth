(ns promptbench.metrics.coverage
  "Coverage metrics: taxonomy-coverage, transform-coverage-matrix, language-coverage.

   All metrics operate on sequences of record maps loaded from parquet files.
   No pipeline state required — computable from dataset metadata alone.

   See spec §7.1 for coverage metric definitions."
  (:require [promptbench.metrics.core :as metrics]
            [promptbench.taxonomy.registry :as taxonomy]
            [promptbench.transform.registry :as transforms]))

;; ============================================================
;; taxonomy-coverage
;; ============================================================

(defn taxonomy-coverage
  "Compute proportion of leaf attack families with at least :min-count prompts.

   dataset — seq of maps with :attack-family key
   params  — {:min-count int} (default 10)

   Returns {:coverage ratio, :missing [families below threshold]}"
  [dataset params]
  (let [min-count (get params :min-count 10)
        ;; All registered families are leaf families
        all-fams (sort (keys (taxonomy/all-families)))
        ;; Count prompts per family
        family-counts (reduce (fn [acc record]
                                (let [fam (:attack-family record)]
                                  (if fam
                                    (update acc fam (fnil inc 0))
                                    acc)))
                              {}
                              dataset)
        ;; Find covered families
        covered (filter #(>= (get family-counts % 0) min-count) all-fams)
        missing (remove (set covered) all-fams)]
    {:coverage (if (pos? (count all-fams))
                 (/ (count covered) (count all-fams))
                 0)
     :missing (vec missing)}))

;; ============================================================
;; transform-coverage-matrix
;; ============================================================

(defn transform-coverage-matrix
  "Build a family × transform coverage matrix from a dataset.

   dataset — seq of maps with :attack-family and :variant-type keys
             (typically variant records)

   Returns: {family-kw {transform-kw count ...} ...}
   One entry per registered family, counts for each registered transform
   (zero if no variants exist)."
  [dataset]
  (let [all-fams (sort (keys (taxonomy/all-families)))
        all-xforms (sort (keys (transforms/all-transforms)))
        ;; Count (family, transform) pairs
        counts (reduce (fn [acc record]
                         (let [fam (:attack-family record)
                               xform (:variant-type record)]
                           (if (and fam xform)
                             (update-in acc [fam xform] (fnil inc 0))
                             acc)))
                       {}
                       dataset)
        ;; Build zero-filled matrix
        zero-row (zipmap all-xforms (repeat 0))]
    (into {}
          (map (fn [family-name]
                 [family-name
                  (merge zero-row (select-keys (get counts family-name {})
                                               all-xforms))]))
          all-fams)))

;; ============================================================
;; language-coverage
;; ============================================================

(defn language-coverage
  "Compute language × split × label distribution.

   dataset — seq of maps with :canonical-lang, :split, :intent-label keys

   Returns nested map: {lang {split {label count}}}"
  [dataset]
  (reduce (fn [acc record]
            (let [lang (:canonical-lang record)
                  split (:split record)
                  label (:intent-label record)]
              (if (and lang split label)
                (update-in acc [lang split label] (fnil inc 0))
                acc)))
          {}
          dataset))

;; ============================================================
;; Registration — register all coverage metrics with def-metric
;; ============================================================

(defn register-coverage-metrics!
  "Register all coverage metrics in the metrics registry.
   Call after taxonomy and transform registries are populated."
  []
  (metrics/register-metric! :taxonomy-coverage
    {:description "Proportion of leaf attack families with at least N prompts"
     :params      {:min-count {:type :int :default 10}}
     :compute     (fn [dataset params]
                    (taxonomy-coverage dataset params))})

  (metrics/register-metric! :transform-coverage-matrix
    {:description "Family × Transform coverage matrix"
     :compute     (fn [dataset _params]
                    (transform-coverage-matrix dataset))})

  (metrics/register-metric! :language-coverage
    {:description "Language × Split × Label distribution"
     :compute     (fn [dataset _params]
                    (language-coverage dataset))}))
