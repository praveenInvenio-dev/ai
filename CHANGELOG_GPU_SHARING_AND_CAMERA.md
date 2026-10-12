# GPU sharing fix + tutorial camera motion

## "CUDA out of memory" with Ollama + ComfyUI + tts-indic + tts-chatterbox
Cause: every engine kept its model resident after use (Ollama keep_alive 5 min, ComfyUI cache,
Chatterbox forever, IndicF5 preloaded onto the GPU at start-up). On a 16 GB card the next one to
load ran out of memory - typically the voice-over after Ollama planned the lesson.

Fix - one engine in VRAM at a time (`GPU_MODE=exclusive`, default):
- New `GpuMemoryManager`: before Ollama / ComfyUI / Chatterbox / IndicF5 / Indic-Speak runs, the
  others release VRAM (Ollama: keep_alive=0 for every loaded model via /api/ps, ComfyUI: /free,
  TTS sidecars: /api/unload). Only on engine change, so consecutive lines cost nothing. Services
  that are not running are skipped for 60 s. Hooked into Ollama, ComfyUI image + video, Chatterbox
  and the Indic voices (indic:/speak:) of the local TTS proxy.
- OOM reported by a TTS sidecar -> release everything else, @Retryable tries again.
- tts-chatterbox: `/api/unload`, idle unload (`CHATTERBOX_IDLE_UNLOAD_SECONDS`, 300), OOM -> clear
  cache + retry once, VRAM cache freed after each line, OOM answered as 503 with a clear message.
- tts-indic (IndicF5): weights stay in CPU RAM, moved to the GPU only while speaking (~1-2 s),
  `/api/unload` + idle unload (`INDICF5_IDLE_UNLOAD_SECONDS`). After an OOM fallback it no longer
  stays on CPU forever - the next line tries the GPU again.
- Classic concept explainer: all artwork first, then all narration (1 GPU switch, not 2 per scene).
- `GPU_MODE=off` restores the old behaviour.
- Note: two GPU jobs running at the SAME time can still collide; run them one after another.

## Tutorial format: camera like the reference video
- Kept elements re-declared with a new x/y glide there at scene start (0.6 s, smoothstep).
- Overview shots: elements placed beyond the frame (x or y 100-150 %) make the camera pull back
  automatically to show the whole system; it eases between framings across scenes.
- Old elements fade out, new sentence-0 elements fade in, headings cross-fade.
- Rendered as an intro image sequence overlaid on the clip start (no change to sentence sync).
- Planner prompt explains MOVE and OVERVIEW.

## Verified here
- GpuMemoryManager against fake Ollama/ComfyUI/TTS endpoints (switch frees others, repeat no-op,
  absent service skipped). Sidecar /api/unload handlers via Flask test client.
- Concept-explainer package + GpuMemoryManager compile against real Jackson; provider edits show no
  non-dependency errors. Transition clips built with the exact ffmpeg graph the service uses.
## Not verified here
- Real GPU run in Docker (VRAM numbers, reload times), full Maven build.
