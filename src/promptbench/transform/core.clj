(ns promptbench.transform.core
  "def-transform macro and composition.

   Provides the def-transform macro for declaring transform definitions.
   Transforms register in the transform registry with spec validation."
  (:require [promptbench.transform.registry :as registry]))

(defmacro def-transform
  "Define and register a transform in the registry.

   Required keys: :description (string), :type (from #{:linguistic :obfuscation :resource-attack}),
                  :deterministic (boolean), :reversible (boolean or :approximate),
                  :params-spec (map), :provenance (keyword vector).
   Optional keys: :impl (function).

   :impl is optional at definition time — transforms can be registered as definition-only
   and have their implementation provided later.

   Throws on invalid spec or duplicate.

   Usage:
     (def-transform mt
       {:description   \"Machine translation to target language\"
        :type          :linguistic
        :deterministic false
        :reversible    :approximate
        :params-spec   {:target-lang {:type :keyword :required true}}
        :provenance    [:engine :target-lang :model-version :timestamp]
        :impl          (fn [{:keys [text config seed]}] ...)})"
  [transform-name transform-data]
  (let [kw-name (keyword (name transform-name))]
    `(registry/register-transform! ~kw-name ~transform-data)))
