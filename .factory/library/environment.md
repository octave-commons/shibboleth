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

## Resource Constraints

- RAM: ~6 GiB available. Embedding batch sizes should be 128-256 max.
- Disk: 96 GiB free. Monitor dataset sizes.
- CPU: 22 cores available.
