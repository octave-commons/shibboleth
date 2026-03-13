#!/usr/bin/env bash
set -euo pipefail

cd /home/err/devel/orgs/octave-commons/shibboleth

# Install Clojure deps (idempotent)
clj -P 2>/dev/null || true

# Install Python ML packages if missing (idempotent)
python3 -c "import hdbscan" 2>/dev/null || python3 -m pip install hdbscan --quiet
python3 -c "import polars" 2>/dev/null || python3 -m pip install polars --quiet
python3 -c "import sentence_transformers" 2>/dev/null || echo "WARNING: sentence-transformers not installed"

# Set up .env if it doesn't exist
if [ ! -f .env ]; then
  echo "PROXY_AUTH_TOKEN=change-me-open-hax-proxy-token" > .env
  echo "Created .env with placeholder token — update PROXY_AUTH_TOKEN before running MT transforms"
fi

# Ensure .env is gitignored
grep -q "^\.env$" .gitignore 2>/dev/null || echo ".env" >> .gitignore

echo "Shibboleth environment ready."
