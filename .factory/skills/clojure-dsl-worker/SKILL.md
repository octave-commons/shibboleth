---
name: clojure-dsl-worker
description: Implements Clojure DSL features including macros, registries, pipeline stages, transforms, metrics, and tests
---

# Clojure DSL Worker

NOTE: Startup and cleanup are handled by `worker-base`. This skill defines the WORK PROCEDURE.

## When to Use This Skill

Use for all Shibboleth features: DSL macro implementation, registry logic, pipeline stages, transform implementations, metrics, CLI, and tests.

## Work Procedure

1. **Read the spec**: Before ANY implementation, read `/home/err/devel/specs/drafts/guardrail-promptbench-dsl.md` — specifically the sections relevant to your feature. Also read the feature description thoroughly.

2. **Write tests first (RED)**:
   - Create test file in `test/promptbench/` matching the source namespace.
   - Write failing tests covering the feature's expected behavior from the validation contract.
   - Run `clj -M:test` to confirm tests fail (RED).
   - For registry-dependent tests, use a fixture that resets registries between tests.

3. **Implement to make tests pass (GREEN)**:
   - Create/edit source files in `src/promptbench/` following the namespace structure.
   - Implement the minimum code to pass all tests.
   - Run `clj -M:test` to confirm all tests pass (GREEN).

4. **Verify beyond tests**:
   - Run `clj -M:test` to confirm full test suite passes (not just new tests).
   - For macro features: verify REPL evaluation produces expected registry state.
   - For pipeline features: verify manifest output structure matches spec §5.
   - For Python bridge features: verify libpython-clj calls work end-to-end.

5. **Check for spec compliance**:
   - Compare your implementation against the spec DSL examples.
   - Verify namespace structure matches spec §9.
   - Verify data shapes match spec §5.1, §5.2, §5.3 as applicable.

## Python Bridge Notes

For features using libpython-clj (embedding, clustering, parquet):
- Keep Python interop in `promptbench.python.*` namespaces.
- Use `libpython-clj2.python` for direct Python calls.
- Use `libpython-clj2.require` with `require-python` for module imports.
- Python packages: `sentence-transformers`, `hdbscan`, `polars` must be importable.
- Batch sizes should be conservative (128-256) due to RAM constraints (~6 GiB free).

## Proxy MT Notes

For MT transform implementation:
- Endpoint: `http://127.0.0.1:8789/v1/chat/completions`
- Model: `gpt-5.2`
- Auth: `Authorization: Bearer $PROXY_AUTH_TOKEN` (from env)
- Always set `temperature: 0` and include `seed` in request for reproducibility.
- Use `clj-http.client` for HTTP calls.

## Example Handoff

```json
{
  "salientSummary": "Implemented def-attack-family macro with spec validation, taxonomy registry, and query functions (descendants, families-with-tag). Wrote 14 test cases in taxonomy_test.clj covering registration, field storage, validation errors, tag queries, and hierarchy traversal. All 14 tests pass via `clj -M:test`.",
  "whatWasImplemented": "src/promptbench/taxonomy/families.clj (def-attack-family macro with spec validation), src/promptbench/taxonomy/registry.clj (atom-backed registry with query functions: get-family, all-families, descendants, families-with-tag, coverage-matrix, reset!)",
  "whatWasLeftUndone": "",
  "verification": {
    "commandsRun": [
      {"command": "clj -M:test", "exitCode": 0, "observation": "Ran 14 tests in 1 namespace, 0 failures, 0 errors"},
      {"command": "clj -Stree", "exitCode": 0, "observation": "All deps resolved, no conflicts"}
    ],
    "interactiveChecks": [
      {"action": "Verified def-attack-family persona-injection from spec §2.1 loads and all fields round-trip through registry", "observed": "All fields match, tags stored as PersistentHashSet, signatures as vector of maps"},
      {"action": "Verified taxonomy/descendants :adversarial traverses 3-level hierarchy", "observed": "Returns #{:persona-injection :dan-variants :character-roleplay :authority-impersonation :developer-mode} — all leaf families"}
    ]
  },
  "tests": {
    "added": [
      {
        "file": "test/promptbench/taxonomy_test.clj",
        "cases": [
          {"name": "def-attack-family-registers", "verifies": "Family appears in registry after definition"},
          {"name": "def-attack-family-stores-all-fields", "verifies": "All fields round-trip through registration"},
          {"name": "def-attack-family-rejects-missing-description", "verifies": "Spec error on missing :description"},
          {"name": "def-attack-family-tags-are-set", "verifies": "Tags stored as set, deduplicated"},
          {"name": "descendants-traverses-hierarchy", "verifies": "Multi-level hierarchy returns leaf families"},
          {"name": "families-with-tag-filters-correctly", "verifies": "Tag query returns matching families only"}
        ]
      }
    ]
  },
  "discoveredIssues": []
}
```

## When to Return to Orchestrator

- libpython-clj fails to initialize (Python environment issue)
- Proxy at 127.0.0.1:8789 is unreachable (external dependency)
- Feature depends on a macro/registry not yet implemented
- Spec is ambiguous about data shape or behavior
- Test suite has pre-existing failures not related to this feature
