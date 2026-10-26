# Increment 59 — Corrupt model downloads, H3 fallback, explainer restart diagnostics

Includes Increments 55-58.

## "Tensor 'blocks.36.mlp.fc1.weight' extends past the end of the file" (H3)
Cause: `minimax_h3_ref2va_pruned_w6a8.safetensors` is a partial download. The download helpers skipped any file that
merely existed (`[ -s file ]`), so an interrupted download was never finished. Ref2VA is used by H3 character
references (Increment 56) and voice-reference shots, so those clips failed.

- **`./verify-models.sh`** (new): scans every `.safetensors` in the ComfyUI volume (reads headers only, seconds) and lists
  broken ones with how much is present. `--fix` resumes each broken file from the known URL, re-checks, and re-downloads
  from zero if resuming does not produce a valid file.
- **`comfyui-gpu/pre-start.sh`**: every model download goes through `getmodel`: verifies `.safetensors`, resumes partial
  files, retries from scratch if still broken, and only warns on failure (no ComfyUI boot loop).
- **`download-models.sh`**: a partial `.safetensors` is no longer "already present"; it is resumed and verified.
- **Backend fallback**: if Ref2VA fails with a truncated-file error on a clip that used it only for character references,
  the backend marks Ref2VA as broken, turns references off (until restart) and re-renders that clip with the normal H3
  model, so the story still finishes. Other corrupt-model errors now say which file and to run `./verify-models.sh --fix`.

## Explainer Studio: "Server restarted while this job was running"
The API now records why it stopped, and interrupted jobs show it:
- Docker stopped/recreated the container (`docker compose up --build`, restart, or changing `COMPOSE_PROFILES` while a job ran);
- the API crashed (stack trace saved);
- no record = killed without warning, usually out of memory (the message includes the commands to confirm).
A stray rejected promise is now logged instead of terminating the server.

## Verified
safetensors check against valid / truncated / HTML-junk files; scripts pass `bash -n`; backend provider compiles
(Spring-stub gaps only); explainer: 19/19 tests, exit reason written on SIGTERM.
