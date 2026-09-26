# IndicF5 reference prompts

IndicF5 is prompt-based: it copies speaker identity and prosody from a
reference clip, so it cannot synthesise without one. Each "voice" here is a
pair of files:

    narrator.wav    5-15 seconds of one speaker, clean, 24 kHz mono
    narrator.txt    that clip's EXACT transcript, nothing else

The transcript is not optional and not cosmetic — F5 aligns the target text
against it, so an approximate transcript degrades output rather than being
ignored.

## Human-voice references (usable, ship-safe)

Two Hindi male references from the supplied dataset pack, peak-normalised to
-3 dBFS:

    hi-agri-m        10.95s   121 wpm
    hi-health-m-1     9.10s   125 wpm

Both are real recordings with verified transcripts, at durations (9-11s) that
sit in IndicF5's comfortable range. These are the ones to actually use.

The rest of that pack (4 Kannada, 2 Hindi) is not installed: the audio is fine
but the transcripts are still empty, and IndicF5 aligns against the transcript
rather than ignoring it — a guessed transcript produces worse output than no
reference at all. Drop the `.wav` and a matching `.txt` in here when the exact
text is available.

## Edge-derived evaluation clips (do not ship)

19 clips across 9 languages (Kannada, Hindi, Tamil, Telugu, Malayalam,
Bengali, Gujarati, Marathi, Indian English). Format is correct throughout:
24 kHz mono 16-bit PCM, transcripts verified against the source metadata.

**These are for evaluation, not production.** The audio was synthesised from
Microsoft Edge neural voices (see `source_voice` in the original pack's
metadata), which has two consequences worth understanding before you use any
of it in a published video:

1. **It is a copy of something you already have.** The Edge voices are already
   available directly in the Voice lab — free, instant, no model download. Using
   Edge output as an IndicF5 reference asks a 1.6 GB model to spend several
   seconds of CPU per second of speech producing a *degraded imitation* of a
   voice you can call directly. For Kannada narration specifically, `edge:kn-IN-SapnaNeural`
   will sound better than IndicF5 cloning it.

2. **The terms do not permit it.** IndicF5's model card prohibits cloning a
   voice you do not have permission to use, and Microsoft's terms do not grant
   permission to use Edge output as training or cloning input. Fine for a
   private A/B test; not something to ship.

They are genuinely useful for one thing: hearing whether IndicF5 adds anything
over Edge on your own hardware, in your own languages, before you invest in it.
Play `edge:kn-IN-SapnaNeural` and `indic:kn-in-sapna` back to back in the Voice
lab and decide.

## Making a reference you can actually ship

Record yourself for ten to fifteen seconds, reading the way you want the
narrator to sound, then write down exactly what you said.

    ffmpeg -i myvoice.m4a -ar 24000 -ac 1 -c:a pcm_s16le narrator.wav
    echo "Once upon a time, in a forest where the trees whispered secrets..." > narrator.txt

This is the intended path: it carries no licence question, and it makes the
narrator sound like you.

Notes:

- Reference quality caps output quality. Background noise, clipping or music in
  the clip all carry through into every generated line.
- A calm, evenly-paced reference suits narration. An excited reference makes
  every line sound excited.
- The reference language need not match the synthesis language, but matching
  gives better prosody.
- Multiple pairs = multiple selectable voices in the Voice lab.
- The installed clips are 3.9-6.4 seconds, at the short end of IndicF5's
  comfortable range. Your own recording at 10-15 seconds should hold identity
  better.
