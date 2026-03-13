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

The `SentenceTransformer` model object should be cached in memory (loaded once per process). The `get-or-load-model` function in `embed.clj` handles this. **Force CPU device** (`device='cpu'`) to avoid GPU OOM on machines with limited VRAM.

## HDBSCAN Cosine Metric

When using HDBSCAN with `metric='cosine'`, you **must** pass `algorithm='generic'`. The default BallTree algorithm does not support cosine distance and will throw an error. Example:

```python
hdbscan.HDBSCAN(min_cluster_size=5, metric='cosine', algorithm='generic')
```

## E5 Model Query/Passage Prefix Convention

The `multilingual-e5-large` model performs best when inputs are prefixed:
- **Queries** (search queries, short texts): prefix with `"query: "`
- **Passages** (documents, longer texts): prefix with `"passage: "`

For pipeline embedding of prompts, use `"query: "` prefix. This convention is not enforced by the model but significantly affects embedding quality.

## Parquet Keyword Columns

The `read-parquet` function requires callers to explicitly pass `:keyword-columns` (a set of column name strings) to restore keyword types from string storage. Without this, keyword values are returned as plain strings. Track which columns contain keywords when writing and pass them on read.

## NFKC Unicode Notes

NFKC normalization has some non-obvious behaviors:
- `½` (U+00BD) normalizes to `1⁄2` with fraction slash (U+2044), **not** ASCII `/` (U+002F)
- `ﬁ` (U+FB01) normalizes to `fi` (two ASCII characters)
- Always verify normalization output for special Unicode characters
