(ns promptbench.taxonomy.labels
  "def-intent-label macro.

   Registers intent labels in the taxonomy registry with spec validation.
   Each label stores: :description, :polarity (:safe/:unsafe/:contested), :requires.

   Conditional requirements based on polarity:
   - :safe    — no :requires needed
   - :unsafe  — requires [:attack-family :harm-category]
   - :contested — requires [:rationale]"
  (:require [promptbench.taxonomy.registry :as registry]))

(defmacro def-intent-label
  "Define and register an intent label in the taxonomy.

   Required keys: :description (string), :polarity (:safe, :unsafe, :contested).
   Optional keys: :requires (keyword vector, conditional on polarity).

   Polarity constraints:
   - :safe    — no :requires needed
   - :unsafe  — must have :requires with [:attack-family :harm-category]
   - :contested — must have :requires with [:rationale]

   Throws on invalid spec, missing conditional requires, or duplicate.

   Usage:
     (def-intent-label :benign
       {:description \"Legitimate user request\"
        :polarity    :safe})

     (def-intent-label :adversarial
       {:description \"Adversarial request\"
        :polarity    :unsafe
        :requires    [:attack-family :harm-category]})"
  [label-name label-data]
  `(registry/register-intent-label! ~label-name ~label-data))
