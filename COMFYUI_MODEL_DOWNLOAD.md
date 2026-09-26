# Recommended GPU image model

For the RTX 4090/24GB-class GPU setup, use **DreamShaper XL Lightning** instead of the current SD1.5 DreamShaper 8 LCM preset. The official Lykon model card documents 4-step inference with guidance scale 2, and the single-file checkpoint is about 6.94 GB.

## Download

After ComfyUI has started once:

```cmd
docker compose -f docker-compose.yml -f docker-compose.gpu.yml exec comfyui aria2c -c -x4 -d /root/ComfyUI/models/checkpoints -o DreamShaperXL_Lightning.safetensors https://huggingface.co/Lykon/dreamshaper-xl-lightning/resolve/main/DreamShaperXL_Lightning.safetensors
```

Verify:

```cmd
docker compose -f docker-compose.yml -f docker-compose.gpu.yml exec comfyui ls -lh /root/ComfyUI/models/checkpoints
```

Then set in `.env`:

```dotenv
COMFYUI_MODEL=DreamShaperXL_Lightning.safetensors
COMFYUI_WORKFLOW=dreamshaper-xl-lightning
COMFYUI_STEPS=4
COMFYUI_CFG=2.0
COMFYUI_SAMPLER=dpmpp_2m
COMFYUI_SCHEDULER=karras
COMFYUI_WIDTH=1024
COMFYUI_HEIGHT=1024
COMFYUI_EXTRA_CLI_ARGS=
```

Restart only ComfyUI/backend after changing the environment:

```cmd
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --force-recreate comfyui backend
```
