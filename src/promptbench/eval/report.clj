(ns promptbench.eval.report
  "Small reporting helpers for new-pattern promptbench runs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn load-metrics
  [run-dir]
  (edn/read-string (slurp (str run-dir "/metrics.edn"))))

(defn summarize-by
  [metrics ks]
  (->> metrics
       (group-by (apply juxt ks))
       (map (fn [[k rows]]
              (let [row (first rows)]
                {:group k
                 :cells (count rows)
                 :n_total (reduce + (map #(long (or (:n_total %) 0)) rows))
                 :n_errors (reduce + (map #(long (or (:n_errors %) 0)) rows))
                 :n_adversarial (reduce + (map #(long (or (:n_adversarial %) 0)) rows))
                 :n_benign (reduce + (map #(long (or (:n_benign %) 0)) rows))
                 :n_harmful_compliance (reduce + (map #(long (or (:n_harmful_compliance %) 0)) rows))
                 :n_benign_failure (reduce + (map #(long (or (:n_benign_failure %) 0)) rows))
                 :asr (:asr row)
                 :fpr (:fpr row)
                 :benign_task_success_rate (:benign_task_success_rate row)})))
       (sort-by :group)
       vec))

(defn print-summary
  [run-dir]
  (let [metrics (load-metrics run-dir)
        groups (summarize-by metrics [:target_model :placement_mode :context_intent_label :task_intent_label])]
    (doseq [{:keys [group n_total n_errors asr fpr benign_task_success_rate]} groups]
      (println {:group group
                :n_total n_total
                :n_errors n_errors
                :asr asr
                :fpr fpr
                :benign_task_success_rate benign_task_success_rate}))
    groups))
