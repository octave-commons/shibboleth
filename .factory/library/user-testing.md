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

## Environment: CUDA Library Path

PyTorch (torch 2.9.1+cu128) requires CUDA libraries from nvidia packages installed in pyenv site-packages. Without the correct `LD_LIBRARY_PATH`, tests that use the Python bridge (sentence-transformers, HDBSCAN) will fail with `ImportError: libcusparseLt.so.0: cannot open shared object file`.

**Fix**: Set `LD_LIBRARY_PATH` to include all nvidia lib dirs before running tests:

```bash
NVIDIA_LIBS=$(find /home/err/.pyenv/versions/3.12.1/lib/python3.12/site-packages/nvidia -name "lib" -type d 2>/dev/null | tr '\n' ':')
LD_LIBRARY_PATH="${NVIDIA_LIBS}${LD_LIBRARY_PATH}" clj -M:test
```

**Required package**: `nvidia-cusparselt-cu12` (installed via `pip3 install nvidia-cusparselt-cu12`).

The nvidia lib directories are under `/home/err/.pyenv/versions/3.12.1/lib/python3.12/site-packages/nvidia/*/lib/`.
