(ns promptbench.transform.mt
  "Machine translation transform implementation.

   Routes through open-hax-openai-proxy at 127.0.0.1:8789.
     Uses temperature=0 and includes seed for maximum reproducibility.
     Sets reasoning_effort=none to avoid models spending the entire output budget
     on thinking traces (which can yield empty assistant content).
     :deterministic is false because LLM outputs are not guaranteed identical."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ============================================================
;; Configuration
;; ============================================================

(def ^:private default-proxy-url "http://127.0.0.1:8789/v1/chat/completions")
(def ^:private default-model "glm-5")
(def ^:private default-max-tokens 1024)
(def ^:private default-reasoning-effort "none")

(defn- estimate-max-tokens
  "Rough max_tokens estimate based on character length.

   Uses a conservative char->token heuristic (4 chars/token) with a buffer.
   Clamped to a reasonable range for local proxy models." 
  [text]
  (let [n (count (or text ""))
        approx (long (Math/ceil (/ (double n) 4.0)))
        raw (+ approx 128)]
    (long (max 256 (min 2048 raw)))))

(defn- engine->model
  "Normalize an :engine value (keyword or string) into a model id string." 
  [engine]
  (cond
    (keyword? engine) (name engine)
    (string? engine) engine
    :else nil))

(defn- normalize-reasoning-effort
  "Normalize reasoning effort config into an OpenAI-style string.

   Returns nil when no value can be derived." 
  [effort]
  (cond
    (nil? effort) nil
    (keyword? effort) (name effort)
    (string? effort) (let [s (str/trim effort)] (when-not (str/blank? s) s))
    :else nil))

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

(defn- strip-code-fences
  "Remove common markdown code fences from a model output string." 
  [s]
  (let [t (str/trim (or s ""))]
    (cond
      (and (str/starts-with? t "```")
           (str/includes? t "```"))
      (let [;; Drop first fence line, drop trailing fence
            lines (->> (str/split-lines t)
                       (drop 1)
                       (remove #(= "```" (str/trim %))))]
        (str/trim (str/join "\n" lines)))

      :else t)))

(defn- extract-json-array-substring
  "Extract the substring between the first '[' and the last ']'.

   Useful when providers wrap JSON with extra text. If we cannot find
   both delimiters, returns the original string." 
  [s]
  (let [t (str/trim (or s ""))
        start (.indexOf t "[")
        end   (.lastIndexOf t "]")]
    (if (and (>= start 0) (>= end 0) (> end start))
      (subs t start (inc end))
      t)))

(defn- parse-json-array
  "Parse a JSON array from model output.

   Hard failures:
   - malformed JSON
   - not an array

   Soft normalization (does not change semantics):
   - strip markdown code fences
   - trim
   - extract JSON-array substring if extra prefix/suffix text exists" 
  [s]
  (let [t (-> s strip-code-fences extract-json-array-substring str/trim)]
    (try
      (let [v (json/parse-string t true)]
        (when-not (sequential? v)
          (throw (ex-info "Expected JSON array" {:type :proxy-error :got (type v)})))
        v)
      (catch Exception e
        (throw (ex-info "Failed to parse JSON array from MT output"
                        {:type :proxy-error
                         :output-preview (subs t 0 (min 200 (count t)))}
                        e))))))

(defn- estimate-max-tokens-batch
  "Estimate max_tokens for a batch of strings." 
  [texts]
  (let [n (reduce + 0 (map (comp count #(or % "")) texts))
        approx (long (Math/ceil (/ (double n) 4.0)))
        raw (+ approx 512)]
    (long (max 512 (min 8192 raw)))))

(defn- translate-via-proxy
  "Send a single translation request to the proxy.

   Always uses temperature=0 and includes the seed parameter
   for maximum reproducibility." 
  [text target-lang seed & {:keys [proxy-url model max-tokens reasoning-effort]
                            :or {proxy-url default-proxy-url
                                 model default-model
                                 max-tokens default-max-tokens
                                 reasoning-effort default-reasoning-effort}}]
  (let [lang-name (get lang-names target-lang (name target-lang))
        auth-token (get-auth-token)
        reasoning-effort (normalize-reasoning-effort reasoning-effort)
        request-body {:model model
                      :messages [{:role "system"
                                  :content (str "You are a professional translator. "
                                                "Translate the following text to " lang-name ". "
                                                "Output ONLY the translation, nothing else. "
                                                "Preserve the meaning and tone.")}
                                 {:role "user"
                                  :content text}]
                      :temperature 0
                      :seed seed
                      :max_tokens max-tokens
                      :reasoning_effort (or reasoning-effort default-reasoning-effort)}
        response (try
                   (http/post proxy-url
                              {:body (json/generate-string request-body)
                               :content-type :json
                               :accept :json
                               :headers {"Authorization" (str "Bearer " auth-token)}
                               :socket-timeout 600000
                               :connection-timeout 60000})
                   (catch Exception e
                     (throw (ex-info (str "MT proxy request failed: " (.getMessage e))
                                     {:type :proxy-error
                                      :target-lang target-lang
                                      :model model
                                      :proxy-url proxy-url}
                                     e))))
        body (json/parse-string (:body response) true)
        translated-text (get-in body [:choices 0 :message :content])]
    (when (str/blank? translated-text)
      (throw (ex-info "MT proxy returned empty translation"
                      {:type :proxy-error
                       :response body})))
    (str/trim translated-text)))

(defn translate-batch-via-proxy
  "Translate a batch of texts to `target-lang` in ONE proxy call.

   Returns:
     {:texts [translated...]
      :metadata {:target-lang ... :engine ... :seed ... :batch-size N :max-tokens ...}}

   Contract:
   - output MUST be a JSON array of strings of the same length.
   - any mismatch throws (caller should retry or fail the stage).

   This is a stage-level optimization; it is not wired as a generic transform
   because the transform engine is currently single-input." 
  [texts target-lang seed & {:keys [proxy-url model max-tokens reasoning-effort]
                             :or {proxy-url default-proxy-url
                                  model default-model
                                  reasoning-effort default-reasoning-effort}}]
  (let [texts (vec (map #(str (or % "")) texts))
        lang-name (get lang-names target-lang (name target-lang))
        auth-token (get-auth-token)
        reasoning-effort (normalize-reasoning-effort reasoning-effort)
        max-tokens (long (or max-tokens (estimate-max-tokens-batch texts)))
        request-body {:model model
                      :messages [{:role "system"
                                  :content (str "You are a professional translator. "
                                                "Translate each item in the provided JSON array into " lang-name ". "
                                                "Output ONLY a valid JSON array of strings of the SAME LENGTH, "
                                                "in the SAME ORDER. No markdown. No backticks. No commentary.")}
                                 {:role "user"
                                  :content (json/generate-string texts)}]
                      :temperature 0
                      :seed seed
                      :max_tokens max-tokens
                      :reasoning_effort (or reasoning-effort default-reasoning-effort)}
        response (try
                   (http/post proxy-url
                              {:body (json/generate-string request-body)
                               :content-type :json
                               :accept :json
                               :headers {"Authorization" (str "Bearer " auth-token)}
                               :socket-timeout 600000
                               :connection-timeout 60000})
                   (catch Exception e
                     (throw (ex-info (str "MT proxy batch request failed: " (.getMessage e))
                                     {:type :proxy-error
                                      :target-lang target-lang
                                      :model model
                                      :proxy-url proxy-url
                                      :batch-size (count texts)}
                                     e))))
        body (json/parse-string (:body response) true)
        content (get-in body [:choices 0 :message :content])
        out (parse-json-array content)]
    (when (not= (count out) (count texts))
      (throw (ex-info "MT proxy batch size mismatch"
                      {:type :proxy-error
                       :expected (count texts)
                       :actual (count out)})))
    {:texts (mapv str/trim out)
     :metadata {:target-lang target-lang
                :engine (keyword model)
                :seed seed
                :batch-size (count texts)
                :max-tokens max-tokens
                :reasoning-effort (normalize-reasoning-effort reasoning-effort)}}))

;; ============================================================
;; Public API
;; ============================================================

(defn apply-mt
  "Apply machine translation transform.

   Arguments map:
   - :text   — input text
    - :config — {:target-lang (keyword, required)
                 :engine (keyword|string, default :glm-5)
                 :max-tokens (int, optional)
                 :reasoning-effort (keyword|string, optional; default none)
                 :proxy-url (string, override proxy URL for testing)}
    - :seed   — integer seed

   Returns {:text ... :metadata {:target-lang :engine :seed ...}}.
   Throws ex-info with :type :proxy-error on failure."
  [{:keys [text config seed]}]
  (let [target-lang (or (:target-lang config)
                         (throw (ex-info "MT requires :target-lang in config"
                                         {:type :config-error})))
        proxy-url (or (:proxy-url config) default-proxy-url)
        model (or (engine->model (:engine config)) default-model)
        raw-max (or (:max_tokens config) (:max-tokens config))
        max-tokens (cond
                     (number? raw-max) (long raw-max)
                     (string? raw-max) (or (parse-long raw-max) (estimate-max-tokens text))
                     :else (estimate-max-tokens text))
        reasoning-effort (or (:reasoning-effort config)
                             (:reasoning_effort config)
                             (:reasoningEffort config)
                             default-reasoning-effort)
         translated (translate-via-proxy text target-lang seed
                                          :proxy-url proxy-url
                                          :model model
                                          :max-tokens max-tokens
                                          :reasoning-effort reasoning-effort)]
      {:text translated
       :metadata {:target-lang target-lang
                  :engine (keyword model)
                  :seed seed
                  :max-tokens max-tokens
                  :reasoning-effort (normalize-reasoning-effort reasoning-effort)
                  :source-text-length (count text)}}))
