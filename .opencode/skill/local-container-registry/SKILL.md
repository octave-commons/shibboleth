---
name: local-container-registry
description: Run a local Docker registry, publish reusable CUDA-capable ML base images, and handle ML container/runtime or local ML service setup issues.
---

# Skill: Local Container Registry

## Goal
Use a reusable local registry + baked ML image workflow instead of repeatedly rebuilding CUDA/torch stacks.

## Use This Skill When
- You need `localhost:5000/...` images across repos.
- CUDA/torch libraries are missing in containers.
- You need a reusable ML toolbox image.
- You need to stand up a local ML service like embeddings inference or the Open Hax proxy/model gateway.

## Steps
1. Start/validate the registry.
2. Prefer official CUDA-capable upstream images for the base.
3. Build/tag/push a baked ML image.
4. Point repo builds at `PROMPTBENCH_BASE_IMAGE`.
5. Use dedicated service images when the goal is a specific local ML API.

## Example
```bash
docker compose -f ~/.config/local-container-registry/docker-compose.yml up -d
curl -sSf http://localhost:5000/v2/_catalog

docker build -f Dockerfile.ml-base \
  -t localhost:5000/shibboleth/ml-base:cuda12.4-2026-03-18 \
  .

docker push localhost:5000/shibboleth/ml-base:cuda12.4-2026-03-18

PROMPTBENCH_BASE_IMAGE=localhost:5000/shibboleth/ml-base:cuda12.4-2026-03-18 \
  docker compose build
```
