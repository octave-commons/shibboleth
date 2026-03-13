(ns promptbench.transform.registry
  "Transform registry.

   Atom-backed registry for transform definitions. Each transform stores:
   :description, :type (from #{:linguistic :obfuscation :resource-attack}),
   :deterministic (boolean), :reversible (boolean or :approximate),
   :params-spec, :provenance (keyword vector). :impl is optional at definition time."
  (:refer-clojure :exclude [reset!])
  (:require [clojure.spec.alpha :as s]))

;; ============================================================
;; Registry Atom
;; ============================================================

(defonce ^:private transforms-registry (atom {}))

;; ============================================================
;; Specs
;; ============================================================

(s/def ::description string?)
(s/def ::type #{:linguistic :obfuscation :resource-attack})
(s/def ::deterministic boolean?)
(s/def ::reversible (s/or :bool boolean? :approx #{:approximate}))
(s/def ::params-spec map?)
(s/def ::provenance (s/coll-of keyword? :kind vector?))
(s/def ::impl (s/nilable fn?))

(s/def ::transform-data
  (s/keys :req-un [::description ::type ::deterministic ::reversible
                   ::params-spec ::provenance]
          :opt-un [::impl]))

;; ============================================================
;; Registration Functions
;; ============================================================

(defn register-transform!
  "Register a transform in the registry. Throws on invalid spec or duplicate.
   :impl is optional — transforms can be registered as definition-only."
  [transform-name transform-data]
  (when-not (s/valid? ::transform-data transform-data)
    (throw (ex-info (str "Invalid transform spec for " transform-name ": "
                         (s/explain-str ::transform-data transform-data))
                    {:name transform-name
                     :data transform-data
                     :explain (s/explain-data ::transform-data transform-data)})))
  (when (contains? @transforms-registry transform-name)
    (throw (ex-info (str "Duplicate transform: " transform-name " is already registered")
                    {:name transform-name})))
  (swap! transforms-registry assoc transform-name transform-data)
  transform-data)

;; ============================================================
;; Query Functions
;; ============================================================

(defn get-transform
  "Retrieve a transform by keyword name."
  [transform-name]
  (get @transforms-registry transform-name))

(defn all-transforms
  "Return all registered transforms as a map of name -> data."
  []
  @transforms-registry)

;; ============================================================
;; Reset (for test isolation)
;; ============================================================

(defn reset!
  "Clear the transforms registry. Used for test isolation."
  []
  (clojure.core/reset! transforms-registry {})
  nil)
