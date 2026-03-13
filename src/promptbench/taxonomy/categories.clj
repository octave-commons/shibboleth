(ns promptbench.taxonomy.categories
  "def-harm-category macro.

   Registers harm categories in the taxonomy registry with spec validation.
   Each category stores: :description, :parent (optional), :children (keyword collection).
   Root categories (no parent) are allowed."
  (:require [promptbench.taxonomy.registry :as registry]))

(defmacro def-harm-category
  "Define and register a harm category in the taxonomy.

   Required keys: :description (string).
   Optional keys: :parent (keyword), :children (keyword collection).

   The first argument is the category name as a keyword.
   Root categories (no :parent) are allowed.
   Throws on invalid spec or duplicate.

   Usage:
     (def-harm-category :jailbreak
       {:description \"Attempts to bypass safety training\"
        :parent      :adversarial
        :children    [:identity-manipulation :instruction-injection]})"
  [cat-name cat-data]
  `(registry/register-category! ~cat-name ~cat-data))
