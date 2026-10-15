# Cosmetic fixes finished + Hindi (and other languages) dub in the editor

## Cosmetics
- Banding in dark and light looks: DONE. Java2D's 8-bit gradients showed as rings (worse after video compression). The soft backgrounds are now
  computed in floating point and dithered with static +-2 level noise (a fixed seed, built once per size and look, so no flicker and no per-frame
  cost; truly flat backgrounds get none). Measured as flat bands per row / column after BOTH x264 encodes a lesson goes through:
  12 looks, 129.6 bands before, 0.0 now. Contrast-stretched frames in banding-before-after.png show the rings gone.
  The two x264 encodes needed a change too, because the old `-tune stillimage` smoothed the noise away again: plain slides now use no tune plus
  `-aq-mode 3` (illustrated scenes keep `film`). Measured on 5 looks: 9.9 bands -> 0.0, smaller files (446 KB vs 577 KB for a 2 s clip),
  text sharpness practically unchanged (SSIM 0.9855 vs 0.9871).
  Weakening the noise on bright looks was tried and made them worse, so every look gets full strength.
- Flowchart, sequence diagram and data structures were small in a full frame: about 25 % larger, fonts bigger.
- Energy profile and lens diagram: labels (Ea, delta H, "with catalyst", F and F') sit on soft pills of the background colour and in free space, so
  nothing is unreadable where it crosses a curve or a ray.
- Lens drawing code tidied (pixel-identical output, 0 differing pixels) and the indentation of the lesson `produce()` method fixed.

## Dub into another language (editor)
Finish panel -> "Dub into another language" (or chat: "dub this into Hindi"). It:
1. transcribes the latest rendered cut (same worker the captions use), so every line has its time on the finished timeline;
2. translates the lines with the language model, each line sized to its time slot (max words per second by language; a line the model skips
   or garbles stays silent and is reported, one failed batch does not lose the rest);
3. speaks each line with a voice of that language (the gateway maps the chosen voice to the right native voice);
4. fits each line to its slot: sped up by at most 1.45x, cut with a short fade if it still does not fit, never running into the next line;
5. stores the track as the project's voiceover with "Replace the original voice" on: the original sound is lowered to about 16 % for the whole
   cut (measured: 15.9 dB lower; the dub voice is unchanged).
"Replace the original voice" is also a checkbox and a chat command for any voiceover. Languages: Hindi, Kannada, Tamil, Telugu, Malayalam,
Marathi, Bengali, Gujarati, English. DB migration V27 (voiceover_replace). Endpoint: POST /api/video-editor/projects/{id}/dub.

## Verified
Dub core with real FFmpeg, a fake model and a fake voice (distinct pitch per line): fragments merged and tiny segments dropped; a model answer
wrapped in chatter and a code fence parsed; a skipped line silent and reported; each line starts within 50 ms of its slot with the right voice;
over-long lines sped up then cut at their slot; the track is exactly the video length; 300 segments merged to at most 160 lines; a model that
returns no JSON leaves everything silent and says so. Replace-original mixing measured (15.9 dB). The dub service type-checks against the real
signatures. Lesson regression 25/25, editor commands 83/83, Angular build.

## Not verified
Transcription itself (needs the worker with faster-whisper, not available here), real translation quality, how real Indian voices sound at
1.45x, the editor module inside Spring with a database. A dub is matched to the cut it was made from: dub again after editing.
Still not built: speaker-following reframe, caption editing, keyframes / multitrack, B-roll sourcing.
