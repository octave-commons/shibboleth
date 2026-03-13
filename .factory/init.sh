#!/usr/bin/env bash
set -euo pipefail

cd /home/err/devel/orgs/octave-commons/shibboleth

# Install Clojure deps (idempotent)
clj -P 2>/dev/null || true

# Install Python ML packages if missing (idempotent)
python3 -c "import hdbscan" 2>/dev/null || python3 -m pip install hdbscan --quiet
python3 -c "import polars" 2>/dev/null || python3 -m pip install polars --quiet
python3 -c "import sentence_transformers" 2>/dev/null || echo "WARNING: sentence-transformers not installed"
python3 -c "import packaging" 2>/dev/null || python3 -m pip install packaging --quiet

# Export NVIDIA library paths for torch/CUDA (needed when running via libpython-clj)
NVIDIA_BASE="$(python3 -c "import sys, os; print(os.path.join(sys.prefix, 'lib', f'python{sys.version_info.major}.{sys.version_info.minor}', 'site-packages', 'nvidia'))" 2>/dev/null || true)"
if [ -d "${NVIDIA_BASE:-}" ]; then
  NVIDIA_LIBS=""
  for d in "$NVIDIA_BASE"/*/lib; do
    [ -d "$d" ] && NVIDIA_LIBS="$d:$NVIDIA_LIBS"
  done
  export LD_LIBRARY_PATH="${NVIDIA_LIBS}${LD_LIBRARY_PATH:-}"
fi

# Set up .env if it doesn't exist
if [ ! -f .env ]; then
  echo "PROXY_AUTH_TOKEN=change-me-open-hax-proxy-token" > .env
  echo "Created .env with placeholder token — update PROXY_AUTH_TOKEN before running MT transforms"
fi

# Ensure .env is gitignored
grep -q "^\.env$" .gitignore 2>/dev/null || echo ".env" >> .gitignore

echo "Shibboleth environment ready."
