(ns promptbench.taxonomy.registry
  "Taxonomy registry and query functions.

   Atom-backed registries for attack families, harm categories, and intent labels.
   Provides registration, retrieval, and query functions. All registrations are
   validated via clojure.spec before storage."
  (:refer-clojure :exclude [reset!])
  (:require [clojure.spec.alpha :as s]))

;; ============================================================
;; Registry Atoms
;; ============================================================

(defonce ^:private families-registry (atom {}))
(defonce ^:private categories-registry (atom {}))
(defonce ^:private intent-labels-registry (atom {}))

;; ============================================================
;; Specs — Attack Family
;; ============================================================

(s/def ::description string?)
(s/def ::category keyword?)
(s/def ::severity #{:low :medium :high :critical})
(s/def ::parent keyword?)
(s/def ::tag keyword?)
(s/def ::tags (s/coll-of ::tag :kind set?))
(s/def ::pattern keyword?)
(s/def ::signature (s/keys :req-un [::pattern ::description]))
(s/def ::signatures (s/coll-of ::signature :kind vector?))
(s/def ::affinity #{:high :medium :low :none})
(s/def ::note string?)
(s/def ::transform-affinity (s/keys :req-un [::affinity] :opt-un [::note]))
(s/def ::transforms (s/map-of keyword? ::transform-affinity))
(s/def ::gen-hints map?)

(s/def ::attack-family
  (s/keys :req-un [::description ::category]
          :opt-un [::severity ::parent ::tags ::signatures ::transforms ::gen-hints]))

;; ============================================================
;; Specs — Harm Category
;; ============================================================

(s/def ::children (s/coll-of keyword?))

(s/def ::harm-category
  (s/keys :req-un [::description]
          :opt-un [::parent ::children]))

;; ============================================================
;; Specs — Intent Label
;; ============================================================

(s/def ::polarity #{:safe :unsafe :contested})
(s/def ::requires (s/coll-of keyword? :kind vector?))

(s/def ::intent-label
  (s/keys :req-un [::description ::polarity]
          :opt-un [::requires]))

;; ============================================================
;; Registration Functions
;; ============================================================

(defn register-family!
  "Register an attack family in the registry. Throws on invalid spec or duplicate."
  [family-name family-data]
  (when-not (s/valid? ::attack-family family-data)
    (throw (ex-info (str "Invalid attack family spec for " family-name ": "
                         (s/explain-str ::attack-family family-data))
                    {:name family-name
                     :data family-data
                     :explain (s/explain-data ::attack-family family-data)})))
  (when (contains? @families-registry family-name)
    (throw (ex-info (str "Duplicate attack family: " family-name " is already registered")
                    {:name family-name})))
  ;; Normalize: ensure tags is a set, signatures is a vector
  (let [normalized (-> family-data
                       (update :tags #(set (or % #{})))
                       (update :signatures #(vec (or % [])))
                       (update :transforms #(or % {}))
                       (update :gen-hints #(or % {})))]
    (swap! families-registry assoc family-name normalized)
    normalized))

(defn register-category!
  "Register a harm category in the registry. Throws on invalid spec or duplicate."
  [cat-name cat-data]
  (when-not (s/valid? ::harm-category cat-data)
    (throw (ex-info (str "Invalid harm category spec for " cat-name ": "
                         (s/explain-str ::harm-category cat-data))
                    {:name cat-name
                     :data cat-data
                     :explain (s/explain-data ::harm-category cat-data)})))
  (when (contains? @categories-registry cat-name)
    (throw (ex-info (str "Duplicate harm category: " cat-name " is already registered")
                    {:name cat-name})))
  (let [normalized (-> cat-data
                       (update :children #(vec (or % []))))]
    (swap! categories-registry assoc cat-name normalized)
    normalized))

(defn register-intent-label!
  "Register an intent label in the registry. Throws on invalid spec, duplicate,
   or missing conditional :requires based on polarity."
  [label-name label-data]
  (when-not (s/valid? ::intent-label label-data)
    (throw (ex-info (str "Invalid intent label spec for " label-name ": "
                         (s/explain-str ::intent-label label-data))
                    {:name label-name
                     :data label-data
                     :explain (s/explain-data ::intent-label label-data)})))
  ;; Conditional :requires enforcement based on polarity
  (let [polarity (:polarity label-data)
        requires (:requires label-data)]
    (case polarity
      :unsafe
      (when (or (nil? requires) (empty? requires)
                (not (every? #{:attack-family :harm-category} requires))
                (< (count requires) 2))
        (throw (ex-info (str "Intent label " label-name " with :unsafe polarity "
                             "requires [:attack-family :harm-category]")
                        {:name label-name :polarity polarity :requires requires})))
      :contested
      (when (or (nil? requires) (empty? requires)
                (not (some #{:rationale} requires)))
        (throw (ex-info (str "Intent label " label-name " with :contested polarity "
                             "requires [:rationale]")
                        {:name label-name :polarity polarity :requires requires})))
      :safe nil ;; no requirements for safe
      ))
  (when (contains? @intent-labels-registry label-name)
    (throw (ex-info (str "Duplicate intent label: " label-name " is already registered")
                    {:name label-name})))
  (swap! intent-labels-registry assoc label-name label-data)
  label-data)

;; ============================================================
;; Query Functions
;; ============================================================

(defn get-family
  "Retrieve an attack family by keyword name."
  [family-name]
  (get @families-registry family-name))

(defn get-category
  "Retrieve a harm category by keyword name."
  [cat-name]
  (get @categories-registry cat-name))

(defn get-intent-label
  "Retrieve an intent label by keyword name."
  [label-name]
  (get @intent-labels-registry label-name))

(defn all-families
  "Return all registered attack families as a map of name -> data."
  []
  @families-registry)

(defn all-categories
  "Return all registered harm categories as a map of name -> data."
  []
  @categories-registry)

(defn all-intent-labels
  "Return all registered intent labels as a map of name -> data."
  []
  @intent-labels-registry)

;; ============================================================
;; Reset (for test isolation)
;; ============================================================

(defn reset!
  "Clear all registries. Used for test isolation."
  []
  (clojure.core/reset! families-registry {})
  (clojure.core/reset! categories-registry {})
  (clojure.core/reset! intent-labels-registry {})
  nil)
