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

## Spec Reference

Full DSL design: `/home/err/devel/specs/drafts/guardrail-promptbench-dsl.md`
