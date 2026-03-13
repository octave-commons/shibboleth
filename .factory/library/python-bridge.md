# Python Bridge

Notes on the libpython-clj bridge layer for sentence-transformers, HDBSCAN, and polars.

## Initialization

`promptbench.python.embed/ensure-python!` must be called before any Python interop. It:
1. Initializes libpython-clj (`py/initialize!`)
2. Adds pyenv site-packages to sys.path (embedded Python may miss it)
3. Sets NVIDIA library paths via `LD_LIBRARY_PATH` for torch/CUDA
4. Patches `MetadataPathFinder.invalidate_caches` for torch compatibility

## LD_LIBRARY_PATH Requirement

**CRITICAL:** When running tests that use the Python bridge, `LD_LIBRARY_PATH` must include the NVIDIA library directories from the pyenv site-packages. Without this, torch fails to load `libcusparseLt.so.0`.

The test command in `.factory/services.yaml` handles this automatically. If running manually:

```bash
NVIDIA_BASE="$(python3 -c "import sys,os;print(os.path.join(sys.prefix,'lib',f'python{sys.version_info.major}.{sys.version_info.minor}','site-packages','nvidia'))")"
LIBS="" && for d in "$NVIDIA_BASE"/*/lib; do [ -d "$d" ] && LIBS="$d:$LIBS"; done
export LD_LIBRARY_PATH="$LIBS${LD_LIBRARY_PATH:-}"
clj -M:test
```

## Dual Site-Packages

The pyenv Python has TWO site-packages directories:
- User: `~/.local/lib/python3.12/site-packages/` (sentence-transformers, transformers, torch)
- System: `~/.pyenv/versions/3.12.1/lib/python3.12/site-packages/` (polars, hdbscan, nvidia libs, packaging)

The embedded Python's `site.getsitepackages()` returns `dist-packages` paths (Debian convention), NOT the pyenv `site-packages`. The bridge init code appends the pyenv site-packages to sys.path.

**Path ordering matters:** User site-packages must come before pyenv site-packages to avoid version conflicts (e.g., transformers version mismatch).

## Model Cache

The `intfloat/multilingual-e5-large` model (~2.2 GB) is downloaded to `~/.cache/huggingface/hub/` on first use. Subsequent loads use the cache.
