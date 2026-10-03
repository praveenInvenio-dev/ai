# ComfyUI DNS / `Failed to resolve 'comfyui'` fix

If the backend reports:

`ComfyUI image generation failed: Failed to resolve 'comfyui' [A(1)]`

this means the backend container cannot resolve the Docker Compose service name `comfyui`. It is a Docker network/service-discovery problem, not an image prompt or model problem.

This release puts all application services on the explicit `ai-story-studio-net` network and makes the backend wait for ComfyUI's healthcheck before starting.

## Clean restart

```powershell
docker compose -f docker-compose.yml -f docker-compose.gpu.yml down --remove-orphans
docker network rm ai-story-studio-net 2>$null
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --build
```

The network removal is safe: Compose recreates it. Do not remove volumes.

## Verify

```powershell
docker compose -f docker-compose.yml -f docker-compose.gpu.yml ps
docker compose -f docker-compose.yml -f docker-compose.gpu.yml exec backend getent hosts comfyui
docker compose -f docker-compose.yml -f docker-compose.gpu.yml exec backend sh -lc "curl -fsS http://comfyui:8188/system_stats"
```

Both commands from `backend` should resolve/connect to ComfyUI. If `getent` is unavailable in the backend image, use:

```powershell
docker inspect ai-story-studio-backend-1 --format '{{json .NetworkSettings.Networks}}'
docker inspect ai-story-studio-comfyui-1 --format '{{json .NetworkSettings.Networks}}'
```

The two containers must share `ai-story-studio-net`.
