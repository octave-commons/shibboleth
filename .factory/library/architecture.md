# Architecture

Architectural decisions, patterns, and knowledge discovered during implementation.

**What belongs here:** Design patterns, macro expansion details, registry internals, data flow discoveries, Python bridge patterns.
**What does NOT belong here:** Service ports/commands (use `services.yaml`), environment setup (use `init.sh`).

---

## DSL Layer Model

Three layers: Taxonomy (attack families, categories, labels) → Transforms (composable mutations) → Pipeline (staged build).

Taxonomy and Transforms collapse into the same representation in Lisp — an attack family definition contains its transform affinities, and the pipeline walks the structure.

## Registry Pattern

Atom-backed registries with `reset!` for test isolation. One registry per construct type (families, categories, labels, sources, transforms, metrics).

**`reset!` shadowing**: Registry namespaces define their own `reset!` function which shadows `clojure.core/reset!`. Use `(:refer-clojure :exclude [reset!])` in the ns declaration and `clojure.core/reset!` when you need to reset an atom directly. This pattern is established in all three registries: `taxonomy/registry.clj`, `pipeline/sources.clj`, `transform/registry.clj`.

**Chain registry**: `transform/registry.clj` also contains a separate `chains-registry` atom (added during the transforms milestone) that stores named transform chains defined via `def-transform-chain`. It follows the same atom-backed pattern with `reset!` for test isolation. The chain registry is queried via `get-chain` and enumerated via `all-chains`.

**TOCTOU in registration**: All `register-*!` functions have a non-atomic check-then-swap pattern for duplicate detection. Acceptable for single-threaded DSL loading but would need `swap!` with conditional logic for concurrent use.

## Seed-Mixing in resolve-transforms

`resolve-transforms` uses a two-stage RNG seeding strategy for deterministic medium-affinity sampling:
1. Create a `mix-rng` from the global seed
2. For each transform, advance by `(rem (hash transform-name) 7)` steps
3. Create a `coin-rng` from the combined seed for the inclusion decision

This was iterated through 4 design cycles (naive XOR → splitmix64 → unchecked-math → two-stage). The current approach avoids correlation between transforms but has limited advancement positions (7). Future scaling beyond ~7 medium-affinity transforms may need a wider hash space.

## Namespace Distinction: validation/ vs verification/

- `promptbench.validation.*` — DSL registry referential integrity (cross-macro validation at definition time)
- `promptbench.verification.*` — Pipeline data integrity (cluster disjointness, split consistency, dedup at build time)

These are distinct concerns: validation checks that DSL definitions are internally consistent; verification checks that pipeline output data meets invariants.

## Cross-Registry Validation

`promptbench.validation.integrity/validate-all!` spans all registries to check referential integrity:
- Family → category parent references
- Category → category parent references
- Category → children (category or family)
- Family → transform affinity references
- Source → taxonomy-mapping target references

This is a meta-check layer above individual registry spec validation.

## Centralized SHA-256 Hashing

All SHA-256 operations MUST use `promptbench.util.crypto`. This namespace provides:
- `sha256-string` — hash a string, returns hex digest
- `sha256-file` — hash a file, returns hex digest
- `sha256-id` — generate deterministic ID from components

Do NOT create local MessageDigest instances. Previously SHA-256 was duplicated across `transform_stages.clj`, `manifest.clj`, `transform/core.clj`, and `stages.clj` — all consolidated in commit `895d605`.

## Metrics Registry

The `def-metric` macro (in `promptbench.metrics.core`) registers metrics in an atom-backed registry following the same pattern as taxonomy/transform registries. Each metric has `:description`, `:compute` (fn), optional `:params` and `:assertion`.

**Compute function interface**: Most metrics accept `(dataset, params)` where `dataset` is a flat sequence of records. However, `transform-fidelity` is asymmetric — it checks `(map? dataset)` and expects a map with `:prompts` and `:variants` keys when called with variant data. This is the only metric with this polymorphic dispatch pattern.

**Assertion evaluation**: `compute-metric` uses `with-meta` to attach `:assertion-passed` to the compute result. This requires the result to implement `IObj` (maps, vectors, etc.). All current metrics return maps, but future metrics returning primitives would need wrapping.

**Coverage metric return types**: `taxonomy-coverage` returns Clojure ratio types (e.g., `3/4`) rather than doubles. Downstream consumers (reports, JSON) may need explicit `(double ...)` conversion.

## Bundle and Reporting Quirks

**Empty-variant parquet workaround**: When no variants exist, `report/bundle.clj` writes a dummy row with empty strings to `variants.parquet` because polars does not support truly empty DataFrames. The bundle summary's `:variant-count` correctly reports 0, but consumers reading the parquet file directly will see 1 row. Check `:variant-count` in `build_manifest.edn` for the true count.

**CLI verify/coverage trigger builds**: The `promptbench verify` and `promptbench coverage` CLI commands call `pipeline/build!` internally to obtain data, rather than reading existing build artifacts from disk. If no prior build exists, these commands will execute the full pipeline. This is intentional (build! skips completed stages) but means they are not purely read-only operations.

## Curated Corpus Pattern

Local curated datasets live in `data/curated/<family-name>/prompts.jsonl`. Each JSONL file follows the schema `{prompt, language, family, harm_category}`. Sources are defined with `:format :jsonl`, `:url nil`, and `:path` pointing to the directory. License is `:gpl-3.0` for curated content.

Three curated families established: `persona-injections/`, `authority-escalation/`, `developer-mode/`.

## Cross-Source Deduplication

`promptbench.pipeline.stages/deduplicate-cross-source` detects duplicates by canonical-hash across source boundaries. **Policy: curated sources are preferred** — when a prompt appears in both a public dataset and a curated source, the curated version is kept. The function is integrated into `canonicalize!` and returns a `:dedup-report` key in the stage result map.

**Note:** `canonicalize!` now returns `{:manifest ... :records ... :dedup-report ...}` — the `:dedup-report` key was added during the curated-corpus milestone.

## Analysis Functions vs Registered Metrics

Coverage analysis added `source-contribution` and `transform-gap-analysis` as standalone functions in `promptbench.metrics.coverage`, not registered via `def-metric` / `register-coverage-metrics!`. These are analysis utilities (not pipeline metrics) and are called directly by the CLI coverage report.

## Testing Patterns

**Mock MT for proxy-free tests**: Integration tests that need MT-like behavior should use a mock MT transform to avoid dependency on the live proxy (which requires `PROXY_AUTH_TOKEN`). Pattern established in `test/promptbench/multilingual_pipeline_integration_test.clj`:
```clojure
(defn mock-mt [text config seed]
  {:text (str "[MT:" (:target-lang config) "] " text)
   :metadata {:transform :mt :target-lang (:target-lang config) :seed seed}})
```
Register as `:mt` transform type before running pipeline stages that invoke MT. The 4 pre-existing MT test errors in `transform_mt_test.clj` are from tests that intentionally use the real proxy.

**clj-http default throw behavior**: `clj-http` throws `ExceptionInfo` by default on non-2xx HTTP responses. Do NOT add manual status-code checks after `http/get` unless you also pass `:throw-exceptions false` in the options map. Without that option, a `when-not (2xx?)` guard is dead code — the exception fires before the check executes.

## Spec Reference

Full DSL design: `/home/err/devel/specs/drafts/guardrail-promptbench-dsl.md`
