(ns promptbench.transform.mt
  "Machine translation transform implementation.

   Routes through GPT-5.2 via open-hax-openai-proxy at 127.0.0.1:8789.
   Uses temperature=0 and includes seed for maximum reproducibility.
   :deterministic is false because LLM outputs are not guaranteed identical."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ============================================================
;; Configuration
;; ============================================================

(def ^:private default-proxy-url "http://127.0.0.1:8789/v1/chat/completions")
(def ^:private default-model "gpt-5.2")

(defn- get-auth-token
  "Read the proxy auth token from environment."
  []
  (or (System/getenv "PROXY_AUTH_TOKEN")
      (throw (ex-info "PROXY_AUTH_TOKEN environment variable not set"
                      {:type :config-error}))))

;; ============================================================
;; Language names for translation prompts
;; ============================================================

(def lang-names
  "Language names for translation prompts.
   Contains all 20 languages: 10 tier-1 + 10 tier-2."
  {;; Tier-1 languages (10)
   :es "Spanish" :fr "French" :de "German" :ja "Japanese"
   :zh "Chinese" :ar "Arabic" :hi "Hindi" :pt "Portuguese"
   :ru "Russian" :ko "Korean"
   ;; Tier-2 languages (10)
   :tl "Tagalog" :sw "Swahili" :ur "Urdu" :bn "Bengali"
   :th "Thai" :vi "Vietnamese" :id "Indonesian" :tr "Turkish"
   :fa "Persian" :he "Hebrew"})

;; ============================================================
;; Translation via proxy
;; ============================================================

(defn- translate-via-proxy
  "Send a translation request to the proxy.

   Always uses temperature=0 and includes the seed parameter
   for maximum reproducibility."
  [text target-lang seed & {:keys [proxy-url model]
                             :or {proxy-url default-proxy-url
                                  model default-model}}]
  (let [lang-name (get lang-names target-lang (name target-lang))
        auth-token (get-auth-token)
        request-body {:model model
                      :messages [{:role "system"
                                  :content (str "You are a professional translator. "
                                                "Translate the following text to " lang-name ". "
                                                "Output ONLY the translation, nothing else. "
                                                "Preserve the meaning and tone.")}
                                 {:role "user"
                                  :content text}]
                      :temperature 0
                      :seed seed}
        response (try
                   (http/post proxy-url
                              {:body (json/generate-string request-body)
                               :content-type :json
                               :accept :json
                               :headers {"Authorization" (str "Bearer " auth-token)}
                               :socket-timeout 120000
                               :connection-timeout 30000})
                   (catch Exception e
                     (throw (ex-info (str "MT proxy request failed: " (.getMessage e))
                                    {:type :proxy-error
                                     :target-lang target-lang
                                     :proxy-url proxy-url}
                                    e))))
        body (json/parse-string (:body response) true)
        translated-text (get-in body [:choices 0 :message :content])]
    (when (str/blank? translated-text)
      (throw (ex-info "MT proxy returned empty translation"
                      {:type :proxy-error
                       :response body})))
    (str/trim translated-text)))

;; ============================================================
;; Public API
;; ============================================================

(defn apply-mt
  "Apply machine translation transform.

   Arguments map:
   - :text   — input text
   - :config — {:target-lang (keyword, required)
                :engine (keyword, default :gpt-5.2)
                :proxy-url (string, override proxy URL for testing)}
   - :seed   — integer seed

   Returns {:text ... :metadata {:target-lang :engine :seed ...}}.
   Throws ex-info with :type :proxy-error on failure."
  [{:keys [text config seed]}]
  (let [target-lang (or (:target-lang config)
                        (throw (ex-info "MT requires :target-lang in config"
                                        {:type :config-error})))
        proxy-url (or (:proxy-url config) default-proxy-url)
        model (or (some-> (:engine config) name) default-model)
        translated (translate-via-proxy text target-lang seed
                                        :proxy-url proxy-url
                                        :model model)]
    {:text translated
     :metadata {:target-lang target-lang
                :engine (keyword model)
                :seed seed
                :source-text-length (count text)}}))
