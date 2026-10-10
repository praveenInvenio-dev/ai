"""Tests for tts-bakeoff.py:  python3 scripts/test_bakeoff.py"""
import hashlib
import importlib.util
import io
import json
import math
import os
import struct
import sys
import tempfile
import threading
import unittest
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("bakeoff", os.path.join(HERE, "tts-bakeoff.py"))
bo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bo)


def wav_bytes(samples, rate=24000, float32=False):
    b = io.BytesIO()
    if float32:
        data = struct.pack("<%df" % len(samples), *samples)
        b.write(b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 3, 1, rate, rate * 4, 4, 32) + b"data" + struct.pack("<I", len(data)) + data)
    else:
        w = wave.open(b, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, x)) * 32767)) for x in samples)); w.close()
    return b.getvalue()


def tone(sec, freq=220, amp=0.3, rate=24000):
    return [amp * math.sin(2 * math.pi * freq * i / rate) for i in range(int(sec * rate))]


class Metrics(unittest.TestCase):
    def test_pcm16_and_float32_wavs_are_both_read(self):
        s = tone(0.5)
        for f32 in (False, True):
            rate, out, ch = bo.read_wav(wav_bytes(s, float32=f32))
            self.assertEqual(rate, 24000); self.assertEqual(ch, 1); self.assertEqual(len(out), len(s))
            self.assertAlmostEqual(max(out), 0.3, places=2)

    def test_silence_lead_trail_and_loudness(self):
        s = [0.0] * 12000 + tone(1.0) + [0.0] * 6000          # 0.5 s lead, 1 s tone, 0.25 s trail
        m = bo.wav_metrics(24000, s)
        self.assertAlmostEqual(m["duration"], 1.75, places=2)
        self.assertAlmostEqual(m["lead_silence"], 0.5, delta=0.03)
        self.assertAlmostEqual(m["trail_silence"], 0.25, delta=0.03)
        self.assertAlmostEqual(m["silence_ratio"], 0.75 / 1.75, delta=0.03)
        self.assertAlmostEqual(m["peak_db"], bo.db(0.3), delta=0.2)
        self.assertLess(m["rms_db"], m["peak_db"])

    def test_empty_audio_does_not_crash(self):
        self.assertEqual(bo.wav_metrics(24000, [])["duration"], 0.0)

    def test_garbage_is_rejected(self):
        with self.assertRaises(ValueError):
            bo.read_wav(b"not a wav at all")


class Accuracy(unittest.TestCase):
    def test_wer_cer_basics(self):
        self.assertEqual(bo.wer("the cat sat", "the cat sat"), 0)
        self.assertAlmostEqual(bo.wer("the cat sat", "the dog sat"), 1 / 3)
        self.assertAlmostEqual(bo.wer("a b c d", "a b"), 0.5)
        self.assertEqual(bo.cer("abc", "abc"), 0)
        self.assertAlmostEqual(bo.cer("abcd", "abxd"), 0.25)

    def test_punctuation_case_and_spacing_are_ignored(self):
        self.assertEqual(bo.wer("Load balancer, shares traffic!", "load  balancer shares traffic"), 0)

    def test_indian_scripts_keep_their_vowel_signs(self):
        kn = "ಬೆಳಕು ಪ್ರತಿ ಸೆಕೆಂಡಿಗೆ ಸುಮಾರು ಮೂರು ಲಕ್ಷ ಕಿಲೋಮೀಟರ್."
        n = bo.normalize(kn)
        self.assertIn("ಬೆಳಕು", n)
        self.assertEqual(bo.wer(kn, kn), 0)
        self.assertGreater(bo.cer(kn, kn.replace("ಲಕ್ಷ", "ಲಕ")), 0)
        self.assertEqual(bo.normalize("H2O, 6:30 ₹499"), "h2o 6 30 499")

    def test_empty_reference_is_safe(self):
        self.assertEqual(bo.wer("", ""), 0)


class Picking(unittest.TestCase):
    VOICES = [
        {"id": "edge:kn-IN-GaganNeural", "gender": "male", "installed": True}, {"id": "edge:kn-IN-SapnaNeural", "gender": "female", "installed": True},
        {"id": "indic:kn-in-gagan", "gender": "male"}, {"id": "indic:kn-in-sapna", "gender": "female"},
        {"id": "speak:kn-Adarsh", "gender": "male"}, {"id": "speak:kn-Deepika", "gender": "female"},
        {"id": "speak:hi-Kavya", "gender": "female"}, {"id": "edge:en-IN-NeerjaNeural", "gender": "female"}, {"id": "edge:en-US-JennyNeural"}]

    def test_female_voice_of_each_running_engine(self):
        p = bo.pick_voices(self.VOICES, "kn", ["edge", "indicf5", "indicspeak"], False)
        self.assertEqual([(e, v["id"]) for e, v in p], [("edge", "edge:kn-IN-SapnaNeural"), ("indicf5", "indic:kn-in-sapna"), ("indicspeak", "speak:kn-Deepika")])

    def test_both_genders(self):
        self.assertEqual(len(bo.pick_voices(self.VOICES, "kn", ["indicspeak"], True)), 2)

    def test_engines_that_are_not_running_are_skipped(self):
        p = bo.pick_voices(self.VOICES, "hi", ["edge", "indicf5", "indicspeak"], False)
        self.assertEqual([e for e, _ in p], ["indicspeak"])

    def test_english_edge_uses_the_indian_voice(self):
        p = bo.pick_voices(self.VOICES, "en", ["edge"], False)
        self.assertEqual(p[0][1]["id"], "edge:en-IN-NeerjaNeural")

    def test_blind_order_is_stable_per_sentence(self):
        a = bo.blind_order(list(range(6)), "kn-narr"); b = bo.blind_order(list(range(6)), "kn-narr")
        self.assertEqual(a, b); self.assertEqual(sorted(a), list(range(6)))


class EndToEnd(unittest.TestCase):
    """Fake tts + fake video-worker in one process: the engine 'indicspeak' is made perfect, 'edge' drops a word,
    'indicf5' garbles one - so the report must rank them in that order."""

    def test_full_run_ranks_the_engines_and_writes_the_report(self):
        heard_by_hash, texts_seen = {}, {}
        calls = {"speak": 0}

        class Tts(BaseHTTPRequestHandler):
            def log_message(self, *a): pass
            def do_GET(self):
                v = [{"id": "edge:kn-IN-SapnaNeural", "gender": "female", "installed": True}, {"id": "indic:kn-in-sapna", "gender": "female"},
                     {"id": "speak:kn-Deepika", "gender": "female"}]
                body = json.dumps({"voices": v}).encode(); self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers(); self.wfile.write(body)
            def do_POST(self):
                req = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if req["voice"].startswith("speak:"):
                    calls["speak"] += 1
                    import time; time.sleep(0.6 if calls["speak"] == 1 else 0.05)           # cold start then fast
                if req["text"].startswith("FAIL"):
                    self.send_response(503); self.end_headers(); self.wfile.write(json.dumps({"error": "boom"}).encode()); return
                data = wav_bytes(tone(0.4 + 0.02 * len(req["text"].split())), 24000)
                words = req["text"].split()
                if req["voice"].startswith("edge:"): heard = " ".join(words[:-1])
                elif req["voice"].startswith("indic:"): heard = " ".join(["xxxx"] + words[1:])
                else: heard = req["text"]
                heard_by_hash[hashlib.sha1(data).hexdigest()] = heard
                self.send_response(200); self.send_header("Content-Type", "audio/wav"); self.end_headers(); self.wfile.write(data)

        class Worker(BaseHTTPRequestHandler):
            def log_message(self, *a): pass
            def do_GET(self):
                self.send_response(200); self.end_headers(); self.wfile.write(b"{}")
            def do_POST(self):
                raw = self.rfile.read(int(self.headers["Content-Length"]))
                i = raw.index(b"RIFF"); j = raw.rindex(b"\r\n--")
                wav = raw[i:j]
                text = heard_by_hash.get(hashlib.sha1(wav).hexdigest(), "")
                body = json.dumps({"text": text, "language": "kn"}).encode(); self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers(); self.wfile.write(body)

        servers = [ThreadingHTTPServer(("127.0.0.1", 0), h) for h in (Tts, Worker)]
        for s in servers: threading.Thread(target=s.serve_forever, daemon=True).start()
        tts_url, worker_url = [f"http://127.0.0.1:{s.server_address[1]}" for s in servers]
        texts = {"kn": {"name": "Kannada", "narr": "ಒಂದು ಎರಡು ಮೂರು ನಾಲ್ಕು ಐದು", "mixed": "Load balancer ಅನೇಕ servers ನಡುವೆ traffic ಹಂಚುತ್ತದೆ", "bad": "FAIL this line"}}
        with tempfile.TemporaryDirectory() as tmp:
            tp = os.path.join(tmp, "texts.json"); json.dump(texts, open(tp, "w", encoding="utf-8"), ensure_ascii=False)
            rc = bo.main(["--tts", tts_url, "--worker", worker_url, "--langs", "kn", "--kinds", "narr,mixed,bad", "--texts", tp, "--out", os.path.join(tmp, "out")])
            self.assertEqual(rc, 0)
            runs = os.listdir(os.path.join(tmp, "out")); d = os.path.join(tmp, "out", runs[0])
            res = json.load(open(os.path.join(d, "results.json"), encoding="utf-8")); s = res["summary"]
            self.assertEqual(set(s), {"edge", "indicf5", "indicspeak"})
            self.assertLess(s["indicspeak"]["cer"], s["edge"]["cer"]); self.assertLess(s["edge"]["cer"], s["indicf5"]["cer"] + 1)
            self.assertEqual(s["indicspeak"]["cer"], 0)
            self.assertGreater(s["indicspeak"]["cold_start"], 0.5, "first request is reported as cold start")
            self.assertLess(s["indicspeak"]["latency"], 0.5, "and left out of the warm latency")
            self.assertEqual(s["edge"]["failed"], 1, "the 503 line is counted as a failure, not a crash")
            html_text = open(os.path.join(d, "report.html"), encoding="utf-8").read()
            self.assertIn("Blind listening test", html_text); self.assertIn("failed: HTTP 503: boom", html_text)
            self.assertTrue(all(os.path.isfile(os.path.join(d, r["file"])) for r in res["rows"] if r.get("file")))
        for s_ in servers: s_.shutdown()


if __name__ == "__main__":
    unittest.main(verbosity=2)
