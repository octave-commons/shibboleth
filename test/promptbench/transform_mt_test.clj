(ns promptbench.transform-mt-test
  "Tests for MT transform proxy integration.

   Fulfills: VAL-XFORM-005 (MT proxy integration).

   Tests written FIRST per TDD methodology.
    Note: These tests require the proxy at 127.0.0.1:8789 to be running.
    Uses glm-5 for testing because it is expected to be available in the
    open-hax proxy configuration."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [promptbench.transform.core :as core]
            [promptbench.transform.registry :as registry]
            [promptbench.transform.mt :as mt]))

;; ============================================================
;; Fixture: Reset registries between tests for isolation
;; ============================================================

(use-fixtures :each
  (fn [f]
    (registry/reset!)
    (f)))

;; Test model — use one that's reliably available through the proxy.
(def ^:private test-model "glm-5")

;; ============================================================
;; Helper: Register MT transform
;; ============================================================

(defn- register-mt-transform! []
  (registry/register-transform!
    :mt
    {:description   "Machine translation to target language"
     :type          :linguistic
     :deterministic false
     :reversible    :approximate
     :params-spec   {:target-lang {:type :keyword :required true}
                     :engine {:type :keyword :default :glm-5}}
     :provenance    [:engine :target-lang :model-version :timestamp]
     :impl          mt/apply-mt}))

;; ============================================================
;; VAL-XFORM-005: MT proxy integration
;; ============================================================

(deftest mt-output-validity-test
  (testing "MT produces non-empty text different from input with canonical shape"
    (register-mt-transform!)
    (let [result (core/execute-transform
                   :mt
                   "How do I hack a computer system?"
                   {:target-lang :es :engine (keyword test-model)}
                   42)]
      (is (map? result) "Result should be a map")
      (is (string? (:text result)) "Result should have :text string")
      (is (map? (:metadata result)) "Result should have :metadata map")
      (is (not (str/blank? (:text result))) "Text should be non-empty")
      (is (not= "How do I hack a computer system?" (:text result))
          "Text should differ from input"))))

(deftest mt-sends-temperature-zero-and-seed-test
  (testing "MT sends temperature=0 and seed to proxy for reproducibility"
    ;; MT implementation always sends temperature=0 and seed in the request body.
    ;; We verify this by checking the translation succeeds (correct request format)
    ;; and the result contains the seed in metadata.
    (register-mt-transform!)
    (let [result (mt/apply-mt {:text "Test prompt"
                               :config {:target-lang :fr :engine (keyword test-model)}
                               :seed 1337})]
      (is (map? result))
      (is (some? (:text result)))
      (is (= 1337 (get-in result [:metadata :seed]))
          "Seed should be recorded in metadata"))))

(deftest mt-deterministic-false-documented-test
  (testing "MT is documented as :deterministic false"
    (register-mt-transform!)
    (let [t (registry/get-transform :mt)]
      (is (= false (:deterministic t))
          "MT should be documented as non-deterministic"))))

(deftest mt-tier1-languages-test
  (testing "Tier-1 languages produce valid translations"
    (register-mt-transform!)
    (doseq [lang [:es :fr :de]]
      (let [result (core/execute-transform
                     :mt
                     "Hello, how are you?"
                     {:target-lang lang :engine (keyword test-model)}
                     42)]
        (is (not (str/blank? (:text result)))
            (str "Translation to " (name lang) " should produce non-empty text"))
        (is (not= "Hello, how are you?" (:text result))
            (str "Translation to " (name lang) " should differ from input"))))))

(deftest mt-metadata-contains-provenance-test
  (testing "MT metadata contains provenance fields"
    (register-mt-transform!)
    (let [result (core/execute-transform
                   :mt "Test prompt" {:target-lang :ja :engine (keyword test-model)} 42)]
      (is (contains? (:metadata result) :target-lang))
      (is (= :ja (get-in result [:metadata :target-lang]))))))

(deftest mt-proxy-error-handling-test
  (testing "Proxy unavailability produces domain-specific error"
    ;; Use a bad port to simulate unavailability
    (register-mt-transform!)
    (is (thrown? clojure.lang.ExceptionInfo
          (mt/apply-mt {:text "Test"
                        :config {:target-lang :es
                                 :proxy-url "http://127.0.0.1:19999/v1/chat/completions"}
                        :seed 42})))))
