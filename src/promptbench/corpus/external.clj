(ns promptbench.corpus.external
  "External multilingual dataset source registrations.

   Registers four external adversarial prompt datasets:
   1. aya-redteaming  — HuggingFace Parquet, Apache-2.0, 8 languages
   2. harmbench       — GitHub CSV, MIT, English-only
   3. advbench        — GitHub CSV, MIT, English-only
   4. toxicchat       — HuggingFace Parquet, CC-BY-NC-4.0, mostly English

   Usage:
     (external/register-all!)  ;; registers all 4 external sources"
  (:require [promptbench.pipeline.sources :as sources]))

;; ============================================================
;; Source Registration
;; ============================================================

(defn register-sources!
  "Register all four external multilingual dataset sources.

   Each source includes:
   - :url pointing to the downloadable file (Parquet or CSV)
   - :format matching the download format
   - :license as a keyword
   - :schema mapping describing the source columns
   - :taxonomy-mapping for resolving source labels to taxonomy keywords
   - :field-mapping for renaming source columns to canonical fields
     (:text, :language, :harm-category)"
  []
  ;; --- aya-redteaming ---
  ;; Multilingual adversarial prompts from CohereForAI.
  ;; 7419 prompts across 8 languages (en, hi, fr, es, ru, ar, sr, fil).
  ;; Parquet format from HuggingFace API.
  (sources/register-source! :aya-redteaming
    {:description      "Aya Red Team dataset — multilingual adversarial prompts from CohereForAI"
     :url              "https://huggingface.co/api/datasets/CohereForAI/aya_redteaming/parquet/default/train/0.parquet"
     :version          "1.0.0"
     :license          :apache-2.0
     :format           :parquet
     :schema           {:prompt :string :language :string :harm_category :string}
     :field-mapping    {:prompt        :text
                        :language      :language
                        :harm_category :harm-category}
     :taxonomy-mapping {:harm_category {}}})

  ;; --- harmbench ---
  ;; HarmBench behaviors text dataset from Center for AI Safety.
  ;; ~510 behaviors, English-only.
  ;; CSV format from GitHub raw URL.
  (sources/register-source! :harmbench
    {:description      "HarmBench behaviors text dataset — English adversarial behaviors"
     :url              "https://raw.githubusercontent.com/centerforaisafety/HarmBench/main/data/behavior_datasets/harmbench_behaviors_text_all.csv"
     :version          "1.0.0"
     :license          :mit
     :format           :csv
     :schema           {:Behavior :string :Category :string}
     :field-mapping    {:Behavior :text
                        :Category :harm-category}
     :default-language "en"
     :taxonomy-mapping {:harm_category {}}})

  ;; --- advbench ---
  ;; AdvBench harmful behaviors dataset from llm-attacks.
  ;; ~520 behaviors, English-only.
  ;; CSV format from GitHub raw URL.
  (sources/register-source! :advbench
    {:description      "AdvBench harmful behaviors dataset — English adversarial goals"
     :url              "https://raw.githubusercontent.com/llm-attacks/llm-attacks/main/data/advbench/harmful_behaviors.csv"
     :version          "1.0.0"
     :license          :mit
     :format           :csv
     :schema           {:goal :string}
     :field-mapping    {:goal :text}
     :default-language "en"
     :taxonomy-mapping {:harm_category {}}})

  ;; --- toxicchat ---
  ;; ToxicChat dataset from LMSYS.
  ;; ~10K prompts, mostly English.
  ;; CC-BY-NC-4.0 (non-commercial) — must be documented in datasheet.
  ;; Parquet format from HuggingFace API.
  ;; Intent labels derived from toxicity + jailbreaking columns.
  (sources/register-source! :toxicchat
    {:description      "ToxicChat dataset — user-model conversations with toxicity labels"
     :url              "https://huggingface.co/api/datasets/lmsys/toxic-chat/parquet/toxicchat0124/train/0.parquet"
     :version          "1.0.0"
     :license          :cc-by-nc-4.0
     :format           :parquet
     :schema           {:user_input :string :toxicity :int :jailbreaking :int}
     :field-mapping    {:user_input :text}
     :default-language "en"
     :taxonomy-mapping {:harm_category {}}}))

;; ============================================================
;; Unified Registration
;; ============================================================

(defn register-all!
  "Register all external multilingual dataset sources.

   Idempotent guard: skips if aya-redteaming is already registered."
  []
  (when-not (sources/get-source :aya-redteaming)
    (register-sources!)))
