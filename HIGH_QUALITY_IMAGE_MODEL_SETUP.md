# High-quality image model integration

The application now supports an optional newer ComfyUI image model without
changing the existing stable workflow. Configure:

```env
COMFYUI_HQ_WORKFLOW=<exported workflow filename without .json>
COMFYUI_HQ_MODEL=<checkpoint filename>
```

When an episode uses `QUALITY` and both values are set, the pipeline uses the
configured high-quality workflow/model. FAST and BALANCED continue using the
existing workflow. If the high-quality values are empty, the existing SD/SDXL
workflow remains unchanged.

This is intentionally configurable because ComfyUI workflows for newer image
models can require different loaders/custom nodes. Do not force an incompatible
workflow onto the RTX 3060. Export and test the model's workflow in ComfyUI,
then point the two settings above at it.
