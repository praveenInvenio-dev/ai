# ComfyUI GPU startup integration

The GPU Compose override mounts `pre-start.sh` into the `yanwk/comfyui-boot` user-script location. On startup it installs `cubiq/ComfyUI_IPAdapter_plus` into the persistent `/root/ComfyUI/custom_nodes` directory before ComfyUI launches.

The bundled SDXL character-reference workflow also needs the IPAdapter model and CLIP Vision model. The GPU startup hook downloads those files automatically on the first start (about 2.3 GB total) and skips them on later starts. They are stored in the persistent GPU volume:

- `/root/ComfyUI/models/ipadapter/ip-adapter-plus_sdxl_vit-h.bin`
- `/root/ComfyUI/models/clip_vision/clip_vision_h.safetensors`

The repository installation is documented by the upstream project: `ComfyUI_IPAdapter_plus` is installed under `ComfyUI/custom_nodes`, and the SDXL IPAdapter model is placed under `models/ipadapter`. 
