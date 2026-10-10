#!/usr/bin/env python3
"""
TTS bake-off: speak the same sentences with every Indian-language engine you have running and compare them.

  python3 scripts/tts-bakeoff.py                         # kn, hi, ta, te with every engine that is up
  python3 scripts/tts-bakeoff.py --langs kn --kinds mixed,formula --both-genders
  python3 scripts/tts-bakeoff.py --no-asr                # skip the speech-recognition check

Engines (picked from the `tts` service's voice list, so only the ones that are running are tested):
  edge        Microsoft Edge neural voices (cloud)        ids  edge:<lang>-...
  indicf5     IndicF5 (AI4Bharat, MIT, needs reference)   ids  indic:<lang>-in-...
  indicspeak  Indic-Speak (Bodhan AI / AI4Bharat)         ids  speak:<lang>-<Name>

What it measures, per sentence and voice
  latency      seconds until the whole audio came back; the FIRST request per engine is reported separately as
               "cold start" (model loading) and left out of the averages
  RTF          latency / audio length (below 1 = faster than real time)
  loudness     RMS in dBFS, peak, share of silence, leading/trailing silence
  CER / WER    the audio is transcribed again by faster-whisper (video-worker) and compared with the text it was given.
               Treat these as RELATIVE: whisper itself is imperfect in Indian languages, but a voice with a much
               higher CER than the others on the same text is mispronouncing or mumbling.
The report (bakeoff/<time>/report.html) also has a BLIND LISTENING TEST: voices are shuffled and unlabeled; score each
one for naturalness and pronunciation, press "Show scores" to see which engine you actually preferred. Your ear is the judge.

Standard library only.
"""
import argparse
import array
import html
import json
import math
import os
import random
import struct
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime

KINDS = ["narr", "stem", "formula", "mixed", "numbers", "long"]
ENGINES = {"edge": "edge:{code}-", "indicf5": "indic:{code}-", "indicspeak": "speak:{code}-"}


# ============================================================== pure helpers (unit-tested)

def read_wav(data: bytes):
    """-> (sample_rate, [float samples -1..1], channels). Handles PCM 8/16/24/32-bit and 32-bit float WAV."""
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError("not a WAV file")
    pos, fmt, pcm = 12, None, None
    while pos + 8 <= len(data):
        cid, size = data[pos:pos + 4], struct.unpack("<I", data[pos + 4:pos + 8])[0]
        body = data[pos + 8:pos + 8 + size]
        if cid == b"fmt ":
            fmt = struct.unpack("<HHIIHH", body[:16])
        elif cid == b"data":
            pcm = body
        pos += 8 + size + (size & 1)
    if fmt is None or pcm is None:
        raise ValueError("WAV without fmt/data")
    tag, ch, rate, _, _, bits = fmt
    if tag == 0xFFFE and len(pcm) >= 0:       # WAVE_FORMAT_EXTENSIBLE: sub-format is in the fmt extension
        tag = 3 if bits == 32 else 1
    if tag == 1 and bits == 16:
        a = array.array("h"); a.frombytes(pcm[: len(pcm) // 2 * 2]); s = [x / 32768.0 for x in a]
    elif tag == 1 and bits == 8:
        s = [(x - 128) / 128.0 for x in pcm]
    elif tag == 1 and bits == 24:
        s = [int.from_bytes(pcm[i:i + 3], "little", signed=True) / 8388608.0 for i in range(0, len(pcm) - 2, 3)]
    elif tag == 1 and bits == 32:
        a = array.array("i"); a.frombytes(pcm[: len(pcm) // 4 * 4]); s = [x / 2147483648.0 for x in a]
    elif tag == 3 and bits == 32:
        a = array.array("f"); a.frombytes(pcm[: len(pcm) // 4 * 4]); s = list(a)
    else:
        raise ValueError(f"unsupported WAV format tag={tag} bits={bits}")
    if ch > 1:                                  # mix down
        s = [sum(s[i:i + ch]) / ch for i in range(0, len(s) - ch + 1, ch)]
    return rate, s, ch


def db(x: float) -> float:
    return 20 * math.log10(x) if x > 1e-9 else -120.0


def wav_metrics(rate: int, s: list, silence_db: float = -50.0, win_ms: int = 20) -> dict:
    if not s:
        return {"duration": 0.0, "peak_db": -120.0, "rms_db": -120.0, "silence_ratio": 1.0, "lead_silence": 0.0, "trail_silence": 0.0}
    n = len(s)
    peak = max(abs(x) for x in s)
    rms = math.sqrt(sum(x * x for x in s) / n)
    w = max(1, int(rate * win_ms / 1000))
    quiet = []
    for i in range(0, n, w):
        seg = s[i:i + w]
        quiet.append(db(math.sqrt(sum(x * x for x in seg) / len(seg))) < silence_db)
    lead = 0
    while lead < len(quiet) and quiet[lead]:
        lead += 1
    trail = 0
    while trail < len(quiet) - lead and quiet[len(quiet) - 1 - trail]:
        trail += 1
    return {"duration": n / rate, "peak_db": db(peak), "rms_db": db(rms), "silence_ratio": sum(quiet) / len(quiet),
            "lead_silence": lead * win_ms / 1000, "trail_silence": trail * win_ms / 1000}


def normalize(text: str) -> str:
    """Lowercase, punctuation to spaces (letters, digits and combining marks of any script are kept), collapse spaces."""
    import unicodedata
    out = []
    for ch in unicodedata.normalize("NFC", (text or "").lower()):
        cat = unicodedata.category(ch)
        out.append(ch if cat[0] in ("L", "N", "M") else " ")
    return " ".join("".join(out).split())


def edit_distance(a, b) -> int:
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def wer(ref: str, hyp: str) -> float:
    r, h = normalize(ref).split(), normalize(hyp).split()
    return edit_distance(r, h) / max(1, len(r))


def cer(ref: str, hyp: str) -> float:
    r, h = normalize(ref).replace(" ", ""), normalize(hyp).replace(" ", "")
    return edit_distance(r, h) / max(1, len(r))


def pick_voices(voices: list, code: str, engines: list, both: bool) -> list:
    """[(engine, voice dict)] - the first (female preferred) voice of each running engine for this language."""
    out = []
    for eng in engines:
        prefix = ENGINES[eng].format(code=code)
        cands = [v for v in voices if str(v.get("id", "")).startswith(prefix) and v.get("installed", True)]
        if eng == "edge" and code == "en":
            cands = [v for v in voices if str(v.get("id", "")).startswith("edge:en-IN-")]
        if not cands:
            continue
        cands.sort(key=lambda v: 0 if v.get("gender") == "female" else 1)
        out.extend((eng, v) for v in (cands if both else cands[:1]))
    return out


def blind_order(items: list, seed: str) -> list:
    r = random.Random(seed)
    items = list(items)
    r.shuffle(items)
    return items


# ============================================================== network

def http_json(url, timeout=30):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def synth(tts_url, text, voice, timeout=900):
    body = json.dumps({"text": text, "voice": voice, "speed": 1.0, "pitch": 1.0}).encode("utf-8")
    req = urllib.request.Request(tts_url + "/api/tts", data=body, headers={"Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read()
        return data, time.time() - t0, None
    except urllib.error.HTTPError as e:
        try:
            msg = json.loads(e.read().decode("utf-8", "replace")).get("error", "")
        except Exception:  # noqa: BLE001
            msg = ""
        return None, time.time() - t0, f"HTTP {e.code}: {msg}"[:300]
    except Exception as e:  # noqa: BLE001
        return None, time.time() - t0, f"{type(e).__name__}: {e}"[:300]


def transcribe(worker_url, wav: bytes, code: str, timeout=300):
    boundary = "----bakeoff" + uuid.uuid4().hex
    parts = []
    for name, val in (("language", code if code != "en" else "en"),):
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{val}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="a.wav"\r\nContent-Type: audio/wav\r\n\r\n'.encode() + wav + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    req = urllib.request.Request(worker_url + "/api/transcribe-upload", data=b"".join(parts),
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8")).get("text", ""), None
    except urllib.error.HTTPError as e:
        return None, f"HTTP {e.code}"
    except Exception as e:  # noqa: BLE001
        return None, f"{type(e).__name__}: {e}"[:200]


# ============================================================== report

def mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else None


def fmt(x, nd=2, unit=""):
    return "-" if x is None else f"{x:.{nd}f}{unit}"


def summarise(rows: list) -> dict:
    out = {}
    for eng in sorted({r["engine"] for r in rows}):
        rs = [r for r in rows if r["engine"] == eng]
        ok = [r for r in rs if not r.get("error")]
        warm = [r for r in ok if not r["cold"]]
        out[eng] = {
            "requests": len(rs), "failed": len(rs) - len(ok),
            "cold_start": mean([r["latency"] for r in ok if r["cold"]]),
            "latency": mean([r["latency"] for r in warm]), "rtf": mean([r["rtf"] for r in warm]),
            "cer": mean([r.get("cer") for r in ok]), "wer": mean([r.get("wer") for r in ok]),
            "rms_db": mean([r["metrics"]["rms_db"] for r in ok]), "silence": mean([r["metrics"]["silence_ratio"] for r in ok]),
            "duration": mean([r["metrics"]["duration"] for r in ok]),
        }
    return out


def build_report(rows, out_dir, meta) -> str:
    summ = summarise(rows)
    esc = html.escape
    h = ["<!doctype html><meta charset='utf-8'><title>TTS bake-off</title><style>",
         "body{font-family:system-ui,Segoe UI,Arial;margin:2rem;max-width:1200px;background:#fafafa;color:#222}",
         "table{border-collapse:collapse;margin:1rem 0}td,th{border:1px solid #ccc;padding:.4rem .7rem;text-align:left}th{background:#eee}",
         ".best{background:#d9f5d6;font-weight:600}.bad{background:#fbe0e0}.row{border-top:2px solid #ddd;padding:1rem 0}",
         ".voice{display:inline-block;vertical-align:top;margin:.4rem 1rem .4rem 0;padding:.6rem;border:1px solid #ddd;border-radius:8px;background:#fff;min-width:260px}",
         ".txt{font-size:1.15rem;margin:.3rem 0 .6rem}.eng{display:none;font-weight:700;color:#06c}.reveal .eng{display:block}",
         "small{color:#666}select{margin-left:.3rem}button{padding:.5rem 1rem;margin-right:.5rem}</style>",
         f"<h1>TTS bake-off</h1><p><small>{esc(meta['when'])} · tts service {esc(meta['tts'])} · languages {esc(', '.join(meta['langs']))} · "
         f"{len(rows)} clips</small></p>",
         "<h2>1. Numbers</h2><p><small>Latency/RTF exclude each engine's first request (model loading, shown as cold start). CER/WER come from a speech-recognition "
         "round trip and are only meaningful <b>between engines on the same sentences</b>. Lower is better for latency, RTF, CER, WER.</small></p>",
         "<table><tr><th>Engine</th><th>Clips</th><th>Failed</th><th>Cold start s</th><th>Latency s</th><th>RTF</th><th>CER</th><th>WER</th><th>Loudness RMS dBFS</th><th>Silence share</th></tr>"]
    best = {k: min((v[k] for v in summ.values() if v[k] is not None), default=None) for k in ("latency", "rtf", "cer", "wer")}
    for eng, v in summ.items():
        def cell(k, nd=2, pct=False):
            val = v[k]
            txt = "-" if val is None else (f"{val * 100:.1f}%" if pct else f"{val:.{nd}f}")
            cls = " class='best'" if val is not None and best.get(k) is not None and abs(val - best[k]) < 1e-9 and len(summ) > 1 else ""
            return f"<td{cls}>{txt}</td>"
        h.append(f"<tr><td><b>{esc(eng)}</b></td><td>{v['requests']}</td><td{' class=bad' if v['failed'] else ''}>{v['failed']}</td>"
                 f"<td>{fmt(v['cold_start'], 1)}</td>{cell('latency')}{cell('rtf')}{cell('cer', pct=True)}{cell('wer', pct=True)}"
                 f"<td>{fmt(v['rms_db'], 1)}</td><td>{fmt(v['silence'] * 100 if v['silence'] is not None else None, 1, '%')}</td></tr>")
    h.append("</table>")
    h.append("<h2>2. Blind listening test</h2><p>Listen to each sentence in every voice (order is shuffled, engines hidden). Score <b>naturalness</b> and "
             "<b>pronunciation</b> from 1 (poor) to 5 (excellent). Scores are saved in this browser.</p>"
             "<p><button onclick=\"document.body.classList.toggle('reveal')\">Reveal / hide engines</button>"
             "<button onclick='scores()'>Show scores</button><button onclick='clearScores()'>Clear scores</button></p><pre id='out'></pre>")
    groups = {}
    for r in rows:
        groups.setdefault((r["lang"], r["kind"]), []).append(r)
    for (lang, kind), rs in groups.items():
        h.append(f"<div class='row'><b>{esc(rs[0]['lang_name'])} · {esc(kind)}</b><div class='txt'>{esc(rs[0]['text'])}</div>")
        for i, r in enumerate(blind_order(rs, f"{lang}-{kind}"), 1):
            key = f"{lang}|{kind}|{r['engine']}|{r['voice']}"
            if r.get("error"):
                h.append(f"<div class='voice'><b>Voice {i}</b><div class='eng'>{esc(r['engine'])} {esc(r['voice'])}</div><span style='color:#b00'>failed: {esc(r['error'])}</span></div>")
                continue
            m = r["metrics"]
            asr = f"<br><small>heard: {esc(r.get('heard') or '-')}<br>CER {fmt((r.get('cer') or 0) * 100, 0, '%') if r.get('cer') is not None else '-'}</small>"
            h.append(f"<div class='voice' data-engine='{esc(r['engine'])}' data-key='{esc(key)}'><b>Voice {i}</b><div class='eng'>{esc(r['engine'])} · {esc(r['voice'])}</div>"
                     f"<audio controls preload='none' src='{esc(r['file'])}'></audio><br>"
                     f"<small>{m['duration']:.1f}s · latency {r['latency']:.1f}s · RTF {r['rtf']:.2f}</small>{asr}<br>"
                     f"Naturalness <select onchange='save(this,\"n\")'>{''.join(f'<option>{k}</option>' for k in ['-', 1, 2, 3, 4, 5])}</select> "
                     f"Pronunciation <select onchange='save(this,\"p\")'>{''.join(f'<option>{k}</option>' for k in ['-', 1, 2, 3, 4, 5])}</select></div>")
        h.append("</div>")
    h.append("""<script>
const NS='bakeoff-%s';
function load(){return JSON.parse(localStorage.getItem(NS)||'{}');}
function save(sel,what){const d=load();const v=sel.closest('.voice');const k=v.dataset.key;d[k]=d[k]||{engine:v.dataset.engine};d[k][what]=sel.value==='-'?null:+sel.value;localStorage.setItem(NS,JSON.stringify(d));}
function scores(){const d=load();const agg={};for(const k in d){const e=d[k].engine;agg[e]=agg[e]||{n:[],p:[]};if(d[k].n)agg[e].n.push(d[k].n);if(d[k].p)agg[e].p.push(d[k].p);}
const m=a=>a.length?(a.reduce((x,y)=>x+y,0)/a.length).toFixed(2):'-';let t='Engine        naturalness  pronunciation  (rated clips)\\n';
for(const e in agg)t+=e.padEnd(14)+m(agg[e].n).padEnd(13)+m(agg[e].p).padEnd(15)+Math.max(agg[e].n.length,agg[e].p.length)+'\\n';document.getElementById('out').textContent=t||'No scores yet.';}
function clearScores(){localStorage.removeItem(NS);document.getElementById('out').textContent='Cleared.';document.querySelectorAll('select').forEach(s=>s.value='-');}
window.onload=()=>{const d=load();document.querySelectorAll('.voice[data-key]').forEach(v=>{const x=d[v.dataset.key];if(!x)return;const s=v.querySelectorAll('select');if(x.n)s[0].value=x.n;if(x.p)s[1].value=x.p;});};
</script>""" % meta["stamp"])
    path = os.path.join(out_dir, "report.html")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(h))
    return path


# ============================================================== main

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Compare Indian-language TTS engines on the same sentences.")
    ap.add_argument("--tts", default="http://localhost:5002", help="the tts service (default http://localhost:5002)")
    ap.add_argument("--worker", default="http://localhost:5010", help="video-worker for the speech-recognition check")
    ap.add_argument("--langs", default="kn,hi,ta,te")
    ap.add_argument("--kinds", default="narr,stem,formula,mixed,numbers")
    ap.add_argument("--engines", default="edge,indicf5,indicspeak")
    ap.add_argument("--both-genders", action="store_true")
    ap.add_argument("--no-asr", action="store_true")
    ap.add_argument("--texts", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "bakeoff-texts.json"))
    ap.add_argument("--out", default="bakeoff")
    a = ap.parse_args(argv)

    texts = json.load(open(a.texts, encoding="utf-8"))
    langs = [x for x in a.langs.split(",") if x]
    kinds = [x for x in a.kinds.split(",") if x]
    engines = [x for x in a.engines.split(",") if x in ENGINES]
    try:
        voices = http_json(a.tts + "/api/voices").get("voices", [])
    except Exception as e:  # noqa: BLE001
        print(f"Cannot reach the tts service at {a.tts}: {e}\nStart the stack first (docker compose up -d).")
        return 2
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = os.path.join(a.out, stamp)
    os.makedirs(os.path.join(out_dir, "audio"), exist_ok=True)

    asr_ok = not a.no_asr
    if asr_ok:
        try:
            urllib.request.urlopen(a.worker + "/health", timeout=5).read()
        except Exception:  # noqa: BLE001
            print(f"video-worker not reachable at {a.worker}: skipping the speech-recognition check (use --no-asr to silence this).")
            asr_ok = False

    rows, seen_engine = [], set()
    plan = []
    for code in langs:
        if code not in texts:
            print(f"(no texts for language '{code}' in {a.texts})")
            continue
        picks = pick_voices(voices, code, engines, a.both_genders)
        if not picks:
            print(f"[{code}] no running engine has a voice for this language")
        for kind in kinds:
            if kind not in texts[code]:
                continue
            for eng, v in picks:
                plan.append((code, kind, eng, v))
    print(f"{len(plan)} clips to make...")
    for n, (code, kind, eng, v) in enumerate(plan, 1):
        text = texts[code][kind]
        data, latency, err = synth(a.tts, text, v["id"])
        cold = eng not in seen_engine
        seen_engine.add(eng)
        row = {"lang": code, "lang_name": texts[code].get("name", code), "kind": kind, "text": text, "engine": eng, "voice": v["id"],
               "latency": latency, "cold": cold, "error": err}
        if data:
            fname = f"audio/{code}_{kind}_{eng}_{v['id'].split(':', 1)[-1]}.wav".replace(" ", "_")
            with open(os.path.join(out_dir, fname), "wb") as f:
                f.write(data)
            try:
                rate, s, _ = read_wav(data)
                row["metrics"] = wav_metrics(rate, s)
                row["file"] = fname
                row["rtf"] = latency / max(0.05, row["metrics"]["duration"])
                if asr_ok:
                    heard, aerr = transcribe(a.worker, data, code)
                    if heard is not None:
                        row["heard"], row["cer"], row["wer"] = heard, cer(text, heard), wer(text, heard)
            except Exception as e:  # noqa: BLE001
                row["error"] = f"unreadable audio: {e}"
        print(f"  {n:>3}/{len(plan)} {code} {kind:<7} {eng:<11} {v['id']:<26} " +
              (row["error"] if row.get("error") else f"{row['metrics']['duration']:.1f}s in {latency:.1f}s" + (" (cold)" if cold else "") +
               (f"  CER {row['cer'] * 100:.0f}%" if row.get("cer") is not None else "")))
        rows.append(row)
    for r in rows:
        r.setdefault("metrics", {"duration": 0, "rms_db": -120, "silence_ratio": 1, "peak_db": -120, "lead_silence": 0, "trail_silence": 0})
        r.setdefault("rtf", 0)
    meta = {"when": datetime.now().strftime("%Y-%m-%d %H:%M"), "tts": a.tts, "langs": langs, "stamp": stamp}
    with open(os.path.join(out_dir, "results.json"), "w", encoding="utf-8") as f:
        json.dump({"meta": meta, "summary": summarise(rows), "rows": rows}, f, ensure_ascii=False, indent=2)
    path = build_report(rows, out_dir, meta)
    print("\nSummary (warm latency s / RTF / CER):")
    for eng, v in summarise(rows).items():
        print(f"  {eng:<11} clips {v['requests']:>3} failed {v['failed']:>2}  latency {fmt(v['latency'])}  RTF {fmt(v['rtf'])}  "
              f"CER {fmt(v['cer'] * 100 if v['cer'] is not None else None, 1, '%')}  cold start {fmt(v['cold_start'], 1)}s")
    print(f"\nOpen {os.path.abspath(path)} in a browser for the blind listening test.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
