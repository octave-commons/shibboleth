(ns promptbench.python.parquet
  "Polars parquet I/O bridge via libpython-clj."
  (:require [libpython-clj2.python :as py]
            [promptbench.python.embed :as embed]))

(defn- records->columnar
  "Convert a vector of maps to a columnar dict {col-name -> [values]}.
   Keys are converted to strings for Python interop."
  [records]
  (let [ks (keys (first records))]
    (reduce (fn [acc k]
              (assoc acc (name k)
                     (mapv (fn [r]
                             (let [v (get r k)]
                               (if (keyword? v)
                                 (name v)
                                 v)))
                           records)))
            {}
            ks)))

(defn- infer-keyword-columns
  "Given the original records, return a set of column name strings
   whose values are keywords."
  [records]
  (let [sample (first records)]
    (->> sample
         (filter (fn [[_k v]] (keyword? v)))
         (map (fn [[k _v]] (name k)))
         set)))

(defn write-parquet
  "Write a vector of maps to a parquet file using polars.
   Keyword values are stored as strings; other types pass through.

   records — vector of maps (homogeneous keys)
   path    — output file path (string)"
  [records path]
  (embed/ensure-python!)
  (let [pl       (py/import-module "polars")
        col-data (records->columnar records)
        col-py   (py/->py-dict
                   (reduce-kv (fn [m k v]
                                (assoc m k (py/->py-list v)))
                              {}
                              col-data))
        df       (py/call-attr pl "DataFrame" col-py)]
    (py/call-attr df "write_parquet" (str path))
    path))

(defn read-parquet
  "Read a parquet file and return a vector of maps.
   Optionally restore keyword columns via :keyword-columns (set of string col names).

   path             — input file path (string)
   :keyword-columns — set of column name strings whose values should be read back as keywords"
  [path & {:keys [keyword-columns] :or {keyword-columns #{}}}]
  (embed/ensure-python!)
  (let [pl    (py/import-module "polars")
        df    (py/call-attr pl "read_parquet" (str path))
        dicts (py/call-attr df "to_dicts")
        rows  (py/->jvm dicts)]
    (mapv (fn [row]
            (reduce-kv (fn [m k v]
                         (let [kw-key (keyword k)]
                           (assoc m kw-key
                                  (if (contains? keyword-columns k)
                                    (keyword v)
                                    v))))
                       {}
                       row))
          rows)))
