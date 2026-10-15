#!/usr/bin/env python3
"""
Synthesises the studio's extra sound effects (no samples, no third-party audio: every sound is generated from sine waves, noise and
envelopes here, so there is no licence to keep). Writes backend/src/main/resources/sfx/<id>.wav (plain PCM: a lossy codec rings on sounds with a sharp start).
Needs: numpy, scipy, ffmpeg.   Run from the repo root:  python3 tools/build_sfx.py
"""
import os, wave
import numpy as np
from scipy.signal import butter, sosfilt

SR = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "backend", "src", "main", "resources", "sfx")
rng = np.random.default_rng(7)

def t(d): return np.arange(int(SR * d)) / SR
def env_exp(x, tau): return np.exp(-x / tau)
def sine(f, d): return np.sin(2 * np.pi * np.cumsum(np.full(int(SR * d), f)) / SR)
def sweep(f0, f1, d, curve=1.0):
    x = t(d); f = f0 + (f1 - f0) * (x / d) ** curve
    return np.sin(2 * np.pi * np.cumsum(f) / SR)
def noise(d): return rng.uniform(-1, 1, int(SR * d))
def lp(x, fc): return sosfilt(butter(2, fc, "low", fs=SR, output="sos"), x)
def hp(x, fc): return sosfilt(butter(2, fc, "high", fs=SR, output="sos"), x)
def bp(x, lo, hi): return sosfilt(butter(2, [lo, hi], "band", fs=SR, output="sos"), x)
def fade(x, a=0.003, b=0.01):
    n1, n2 = int(SR * a), int(SR * b); x = x.copy()
    x[:n1] *= np.linspace(0, 1, n1); x[-n2:] *= np.linspace(1, 0, n2); return x
def at(buf, x, start):
    i = int(SR * start); buf[i:i + len(x)] += x[:len(buf) - i]
def bell(f, d, tau):  # inharmonic partials, like a struck bell
    x = t(d); return sum(a * np.sin(2 * np.pi * f * r * x) * env_exp(x, tau / r ** 0.6) for r, a in ((1, 1), (2.76, 0.45), (5.4, 0.2), (8.93, 0.1)))

def pop():     x = t(0.16); return sweep(900, 260, 0.16) * env_exp(x, 0.035)
def click():   x = t(0.05); return (hp(noise(0.05), 1500) * env_exp(x, 0.006) * 0.9 + sine(2400, 0.05) * env_exp(x, 0.005) * 0.4)
def tick():    x = t(0.06); return sine(1800, 0.06) * env_exp(x, 0.012)
def ding():    return bell(880, 1.3, 0.45)
def success():
    b = np.zeros(int(SR * 0.9))
    for k, f in enumerate((523.25, 659.25, 783.99)): at(b, bell(f, 0.5, 0.18), 0.11 * k)
    return b
def error():
    b = np.zeros(int(SR * 0.42))
    for k, f in enumerate((190, 150)):
        x = t(0.16); v = np.sign(np.sin(2 * np.pi * f * x)) * 0.5 + np.sin(2 * np.pi * f * x) * 0.5; at(b, lp(v, 1400) * np.minimum(1, (0.16 - x) * 40), 0.2 * k)
    return b
def swipe():
    d = 0.4; x = t(d); n = noise(d); out = np.zeros_like(n); blocks = 16
    for i in range(blocks):
        s, e = int(len(n) * i / blocks), int(len(n) * (i + 1) / blocks)
        c = 400 * (4000 / 400) ** (i / (blocks - 1)); out[s:e] = bp(n, c * 0.7, min(c * 1.4, 20000))[s:e]
    return out * np.sin(np.pi * x / d) ** 1.5
def riser():
    d = 1.6; x = t(d); e = (x / d) ** 2.2
    return (sweep(180, 2200, d, 1.6) * 0.5 + bp(noise(d), 800, 7000) * 0.9) * e
def impact():
    d = 1.1; x = t(d)
    return sweep(75, 32, d, 0.35) * env_exp(x, 0.28) + lp(noise(d), 260) * env_exp(x, 0.09) * 1.2
def typing():
    b = np.zeros(int(SR * 0.9)); p = 0.02
    for _ in range(7):
        k = click(); at(b, k * rng.uniform(0.6, 1.0), p); p += rng.uniform(0.06, 0.12)
    return b
def shutter():
    b = np.zeros(int(SR * 0.3)); at(b, click() * 1.1 + lp(noise(0.05), 600) * 0.6, 0.0); at(b, click() * 0.9 + lp(noise(0.05), 500) * 0.5, 0.075); return b
def bubble(): x = t(0.22); return sweep(380, 1250, 0.22, 0.7) * np.sin(np.pi * x / 0.22) ** 0.6 * env_exp(x, 0.07)
def coin():
    b = np.zeros(int(SR * 0.7)); at(b, bell(988, 0.12, 0.05), 0.0); at(b, bell(1319, 0.6, 0.22), 0.085); return b
def notification():
    b = np.zeros(int(SR * 0.7)); at(b, bell(784, 0.35, 0.12), 0.0); at(b, bell(1047, 0.45, 0.16), 0.16); return b
def boing():
    d = 0.8; x = t(d); f = 220 + 160 * np.sin(2 * np.pi * 7 * x) * env_exp(x, 0.35) + 120 * env_exp(x, 0.12)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * env_exp(x, 0.3)
def tada():
    d = 1.5; b = np.zeros(int(SR * d))
    for f, s in ((523.25, 0.0), (659.25, 0.0), (783.99, 0.0), (1046.5, 0.14)): at(b, bell(f, d - s, 0.55) * 0.6, s)
    x = t(0.9); at(b, hp(noise(0.9), 5000) * env_exp(x, 0.25) * 0.25, 0.14)
    return b

SOUNDS = dict(pop=pop, click=click, tick=tick, ding=ding, success=success, error=error, swipe=swipe, riser=riser, impact=impact,
              typing=typing, shutter=shutter, bubble=bubble, coin=coin, notification=notification, boing=boing, tada=tada)

def main():
    os.makedirs(OUT, exist_ok=True)
    for name, fn in SOUNDS.items():
        x = fade(fn().astype(np.float64))
        x = x / (np.max(np.abs(x)) + 1e-9) * 0.7          # peak about -3 dBFS (no lossy codec, so no overshoot)
        with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((x * 32767).astype(np.int16).tobytes())
    print("wrote", len(SOUNDS), "sounds to", os.path.abspath(OUT))

if __name__ == "__main__":
    main()
