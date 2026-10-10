"""Tests for the Indic-Speak sidecar with a fake model (no GPU, no download):  python3 tts-indicspeak/test_server.py"""
import importlib.util
import io
import os
import sys
import tempfile
import types
import unittest

import numpy as np
import soundfile as sf

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = tempfile.mkdtemp()
open(os.path.join(MODEL_DIR, "inference.py"), "w").write('''
import numpy as np
CALLS = []
class TTS:
    def __init__(self, path): pass
    def __call__(self, text, speaker="Amit", style=None, **kw):
        CALLS.append((text, speaker, style))
        if "BOOM" in text: raise RuntimeError("CUDA out of memory. Tried to allocate 2.00 GiB")
        n = int(24000 * (0.4 + 0.05 * len(text)))
        return (0.4 * np.sin(2 * np.pi * 220 * np.arange(n) / 24000)).astype(np.float32)
''')
GATED = {"on": False}
fake_hub = types.ModuleType("huggingface_hub")
def snapshot_download(repo, token=None):
    if GATED["on"]:
        raise RuntimeError("403 Client Error: gated repo")
    fake_hub.last = (repo, token)
    return MODEL_DIR
fake_hub.snapshot_download = snapshot_download
sys.modules["huggingface_hub"] = fake_hub
os.environ["HF_TOKEN"] = "hf_test"
spec = importlib.util.spec_from_file_location("isrv", os.path.join(HERE, "server.py"))
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)
client = srv.app.test_client()


def post(body):
    return client.post("/api/tts", json=body)


class Sidecar(unittest.TestCase):
    def setUp(self):
        srv.unload()
        GATED["on"] = False
        srv._load_error = None

    def test_catalogue_matches_the_model_card(self):
        total = sum(len(v) for v in srv.SPEAKERS.values())
        self.assertEqual(total, 95)
        self.assertEqual(sum(1 for c in srv.PRODUCTION if c in srv.SPEAKERS), 13)
        listed = client.get("/api/voices").get_json()["voices"]
        self.assertEqual(len(listed), 59, "English is trimmed to the 8 Indian teaching voices in the lists")
        ids = {v["id"] for v in listed}
        self.assertTrue({"speak:kn-Deepika", "speak:kn-Adarsh", "speak:hi-Kavya", "speak:ta-Anitha"} <= ids)

    def test_speaks_kannada_as_24khz_wav(self):
        r = post({"text": "ನಮಸ್ಕಾರ", "voice": "speak:kn-Deepika"})
        self.assertEqual(r.status_code, 200)
        data, rate = sf.read(io.BytesIO(r.data))
        self.assertEqual(rate, 24000)
        self.assertGreater(len(data), 24000 * 0.4)

    def test_speaker_and_style_reach_the_model_and_unlisted_english_voices_work(self):
        post({"text": "hello", "voice": "speak:en-Zoon", "style": "ANGER"})
        import inference
        self.assertEqual(inference.CALLS[-1], ("hello", "Zoon", "ANGER"))

    def test_unknown_voice_is_rejected_not_silently_averaged(self):
        self.assertEqual(post({"text": "x", "voice": "speak:kn-Nobody"}).status_code, 404)
        self.assertEqual(post({"text": "x", "voice": "speak:xx-Foo"}).status_code, 404)
        self.assertEqual(post({"text": "x", "voice": "indic:kn-in-sapna"}).status_code, 404)

    def test_input_validation(self):
        self.assertEqual(post({"text": "  ", "voice": "speak:kn-Deepika"}).status_code, 400)
        self.assertEqual(post({"text": "a" * 2000, "voice": "speak:kn-Deepika"}).status_code, 400)

    def test_speed_changes_length(self):
        a = sf.read(io.BytesIO(post({"text": "speed test sentence", "voice": "speak:en-Amit"}).data))
        b = sf.read(io.BytesIO(post({"text": "speed test sentence", "voice": "speak:en-Amit", "speed": 1.5}).data))
        self.assertLess(len(b[0]) / b[1], len(a[0]) / a[1] * 0.8)

    def test_gpu_out_of_memory_is_reported_clearly(self):
        r = post({"text": "BOOM", "voice": "speak:kn-Deepika"})
        self.assertEqual(r.status_code, 507)
        self.assertIn("GPU is full", r.get_json()["error"])

    def test_gated_repo_tells_you_what_to_do(self):
        GATED["on"] = True
        r = post({"text": "hi", "voice": "speak:hi-Amit"})
        self.assertEqual(r.status_code, 503)
        self.assertIn("accept the licence", r.get_json()["error"])
        self.assertIn("accept the licence", client.get("/health").get_json()["loadError"])

    def test_loads_on_demand_and_unloads_to_free_the_gpu(self):
        self.assertFalse(client.get("/health").get_json()["modelLoaded"])
        post({"text": "hi", "voice": "speak:hi-Amit"})
        self.assertTrue(client.get("/health").get_json()["modelLoaded"])
        self.assertEqual(fake_hub.last[1], "hf_test", "the token is passed to the download")
        client.post("/api/unload")
        self.assertFalse(client.get("/health").get_json()["modelLoaded"])
        post({"text": "again", "voice": "speak:hi-Amit"})
        self.assertTrue(client.get("/health").get_json()["modelLoaded"], "reloads on the next request")


if __name__ == "__main__":
    unittest.main(verbosity=1)
