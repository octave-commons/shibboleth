(ns promptbench.python.cluster
  "HDBSCAN bridge for embedding clustering via libpython-clj."
  (:require [libpython-clj2.python :as py]
            [promptbench.python.embed :as embed]))

(defn cluster-embeddings
  "Run HDBSCAN on an embedding matrix. Returns a vector of integer cluster labels.
   Labels >= 0 are cluster assignments; -1 indicates noise.

   embeddings — vector of vectors (each inner vector is an embedding)
   opts map keys:
     :min-cluster-size — minimum cluster size (default 5)
     :metric           — distance metric (default \"cosine\")"
  [embeddings & {:keys [min-cluster-size metric]
                 :or   {min-cluster-size 5 metric "cosine"}}]
  (embed/ensure-python!)
  (let [np         (py/import-module "numpy")
        hdb-mod    (py/import-module "hdbscan")
        emb-np     (py/call-attr-kw np "array" [(py/->python embeddings)] {:dtype "float64"})
        clusterer  (py/call-attr-kw hdb-mod "HDBSCAN"
                     []
                     {:min_cluster_size min-cluster-size
                      :metric           metric})
        labels-np  (py/call-attr clusterer "fit_predict" emb-np)
        labels-list (py/call-attr labels-np "tolist")]
    (mapv long (py/->jvm labels-list))))
