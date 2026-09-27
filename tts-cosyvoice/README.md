# CosyVoice 3 sidecar

Optional multilingual voice-cloning provider for AI Story Studio.

Start only when needed:

```bash
docker compose --profile cosyvoice up -d --build tts-cosyvoice
```

For GPU deployments use the GPU compose override. The model is intentionally
not baked into the image. Put the downloaded Fun-CosyVoice3-0.5B-2512 model
under `tts-cosyvoice/models/Fun-CosyVoice3-0.5B-2512`.

CosyVoice zero-shot cloning requires the exact transcript of the reference
recording. The Voice Library therefore stores an optional `referenceTranscript`
field; it is required when a CosyVoice voice is synthesized.

The provider is optional. Piper and ChatterBox continue to work without it.
