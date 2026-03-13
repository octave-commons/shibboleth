# User Testing

Testing surface, resource cost classification, and validation approach.

---

## Validation Surface

This is a CLI/library project with NO web UI. Validation is entirely through automated tests.

- **Primary surface**: `clj -M:test` (cognitect test-runner)
- **Secondary surface**: CLI commands (`promptbench build/verify/coverage/rebuild`)
- **No browser testing needed**

## Validation Concurrency

Single test runner process. No concurrency constraints.

## Validation Approach

Scrutiny-only: automated test suite + code review. No user-testing validator.
