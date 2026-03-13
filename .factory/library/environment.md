# Environment

Environment variables, external dependencies, and setup notes.

**What belongs here:** Required env vars, external API keys/services, dependency quirks, platform-specific notes.
**What does NOT belong here:** Service ports/commands (use `.factory/services.yaml`).

---

## Required Environment

- JVM: OpenJDK 21+
- Clojure CLI: 1.12+
- Babashka: 1.12+
- Python: 3.12+ (via pyenv at `~/.pyenv/versions/3.12.1`)

## Dependency Coordinate Quirks

- `babashka/fs` uses Clojars group `babashka/fs`, **not** `org.babashka/fs`. Check Clojars for the canonical coordinate.

## Python Packages

- `sentence-transformers` (for embeddings)
- `hdbscan` (for clustering)
- `polars` (for parquet I/O)

## Environment Variables

- `PROXY_AUTH_TOKEN` — Auth token for open-hax-openai-proxy. Read from `.env` file.

## Known Test Issues

- **MT tests require PROXY_AUTH_TOKEN**: The 4 tests in `test/promptbench/transform_mt_test.clj` will fail with `PROXY_AUTH_TOKEN environment variable not set` if the env var is not exported. Run tests with `source .env && export PROXY_AUTH_TOKEN` before invoking the test command, or use the full command: `source .env && export PROXY_AUTH_TOKEN && clj -M:test`. This is not a code bug — the tests require a live proxy connection.

## Resource Constraints

- RAM: ~6 GiB available. Embedding batch sizes should be 128-256 max.
- Disk: 96 GiB free. Monitor dataset sizes.
- CPU: 22 cores available.
