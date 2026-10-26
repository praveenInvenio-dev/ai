#!/usr/bin/env python3
"""Optional Kokoro-82M (Apache-2.0) adapter. Reads text on stdin, writes a WAV.
Requires: pip install kokoro-onnx soundfile  and model files in --model-dir
(kokoro-v1.0.onnx, voices-v1.0.bin). Untested in CI — treat as a drop-in you validate locally."""
import argparse, os, sys
import soundfile as sf
from kokoro_onnx import Kokoro

p = argparse.ArgumentParser()
p.add_argument("--voice", default="am_michael")
p.add_argument("--speed", type=float, default=1.0)
p.add_argument("--out", required=True)
p.add_argument("--model-dir", default="/opt/kokoro")
a = p.parse_args()

text = sys.stdin.read().strip()
if not text:
    sys.exit("empty text")
k = Kokoro(os.path.join(a.model_dir, "kokoro-v1.0.onnx"), os.path.join(a.model_dir, "voices-v1.0.bin"))
samples, sr = k.create(text, voice=a.voice, speed=a.speed, lang="en-us")
sf.write(a.out, samples, sr)
