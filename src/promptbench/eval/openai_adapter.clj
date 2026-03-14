(ns promptbench.eval.openai-adapter
  "OpenAI-compatible model adapter for evaluation runs.

   Routes through open-hax-openai-proxy at 127.0.0.1:8789 by default.
   Keeps request format intentionally minimal and stable." 
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private default-proxy-url
  "http://127.0.0.1:8789/v1/chat/completions")

(defn get-auth-token
  "Read the proxy auth token from the environment." 
  []
  (or (System/getenv "PROXY_AUTH_TOKEN")
      (throw (ex-info "PROXY_AUTH_TOKEN environment variable not set"
                      {:type :config-error}))))

(defn- normalize-reasoning-effort
  "Normalize reasoning effort config into an OpenAI-style string.

   Returns nil when no value can be derived." 
  [effort]
  (cond
    (nil? effort) nil
    (keyword? effort) (name effort)
    (string? effort) (let [s (str/trim effort)] (when-not (str/blank? s) s))
    :else nil))

(defn chat-completions!
  "Invoke /v1/chat/completions via the proxy.

   req keys:
   - :model (string, required)
   - :messages (vector of {:role :content}, required)
   - :temperature (number, default 0)
   - :seed (int, optional)
   - :max-output-tokens (int, default 512)
   - :reasoning-effort (string|keyword, optional)
   - :proxy-url (string, optional)

   Returns:
   {:model_id ...
    :text ...
    :finish_reason ...
    :usage {:input_tokens ... :output_tokens ... :total_tokens ...}
    :latency_ms ...
    :raw <parsed-json>}

   Throws ex-info on request or response errors." 
  [{:keys [model messages temperature seed max-output-tokens reasoning-effort proxy-url]
    :or {temperature 0
         max-output-tokens 512
         proxy-url default-proxy-url}}]
  (when (str/blank? (or model ""))
    (throw (ex-info "chat-completions! requires :model" {:type :config-error})))
  (when-not (seq messages)
    (throw (ex-info "chat-completions! requires :messages" {:type :config-error})))
  (let [auth-token (get-auth-token)
        reasoning-effort (normalize-reasoning-effort reasoning-effort)
        request-body (cond->
                      {:model model
                       :messages messages
                       :temperature temperature
                       :max_tokens max-output-tokens}
                       (some? seed) (assoc :seed seed)
                       (some? reasoning-effort) (assoc :reasoning_effort reasoning-effort))
        t0 (System/nanoTime)
        response (try
                   (http/post proxy-url
                              {:body (json/generate-string request-body)
                               :content-type :json
                               :accept :json
                               :headers {"Authorization" (str "Bearer " auth-token)}
                               :socket-timeout 600000
                               :connection-timeout 60000
                               :throw-exceptions true})
                   (catch Exception e
                     (throw (ex-info (str "Proxy request failed: " (.getMessage e))
                                     {:type :proxy-error
                                      :proxy-url proxy-url
                                      :model model}
                                     e))))
        latency-ms (long (/ (- (System/nanoTime) t0) 1000000.0))
        body (json/parse-string (:body response) true)
        msg (get-in body [:choices 0 :message])
        content (when (map? msg) (get msg :content))
        reasoning (when (map? msg) (or (get msg :reasoning)
                                       (get msg :reasoning_content)
                                       (get msg :reasoningContent)))
        text (cond
               (and (string? content) (not (str/blank? content))) content
               (and (string? reasoning) (not (str/blank? reasoning))) reasoning
               :else "")
        finish-reason (get-in body [:choices 0 :finish_reason])
        usage (:usage body)
        usage* (when (map? usage)
                 {:input_tokens (:prompt_tokens usage)
                  :output_tokens (:completion_tokens usage)
                  :total_tokens (:total_tokens usage)})
        model-id (or (:model body) model)]
    (when (str/blank? (str/trim (or text "")))
      (throw (ex-info "Proxy returned empty text"
                      {:type :proxy-error
                       :model model
                       :proxy-url proxy-url
                       :response body})))
    {:model_id model-id
     :text (str/trim text)
     :finish_reason finish-reason
     :usage usage*
     :latency_ms latency-ms
     :raw body}))
