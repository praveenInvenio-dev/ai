# ComfyUI model persistence fix

The GPU Compose file now uses a stable Docker volume name:
`ai-story-studio_comfyui-gpu-root`.

This is intentional for the existing installation because it already contains
`DreamShaper8_LCM.safetensors`. Rebuilding or extracting the application into a
different folder no longer creates a new project-prefixed model volume.

Override with `COMFYUI_GPU_VOLUME` only when a different model store is
intentionally required. Do not run `docker volume prune` or delete the stable
volume if it contains your models.

Verify after startup:

```cmd
docker compose -f docker-compose.yml -f docker-compose.gpu.yml exec comfyui ls -lh /root/ComfyUI/models/checkpoints
```

Expected checkpoint:
`DreamShaper8_LCM.safetensors`
