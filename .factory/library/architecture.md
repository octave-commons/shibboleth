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

## Spec Reference

Full DSL design: `/home/err/devel/specs/drafts/guardrail-promptbench-dsl.md`
