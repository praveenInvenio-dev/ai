"""
Manim render service for AI Story Studio (CPU only).

POST /api/render  {"template": "function_plot", "params": {...}, "seconds": 12, "theme": "reference",
                   "width": 1280, "height": 720, "fps": 30, "preview": false}
  -> video/mp4 (the animation, paced to `seconds`) or image/png (preview: the finished frame, rendered much faster)
GET  /health, GET /api/templates

The caller never sends code: only a template name and parameters. Every parameter is validated and clamped here (and the
formula is parsed through safe_expr), then the scene is rendered in a separate manim process, one at a time.
"""
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time

import numpy as np
from flask import Flask, Response, jsonify, request

from safe_expr import safe_function

HERE = os.path.dirname(os.path.abspath(__file__))
SCENES = os.path.join(HERE, "studio_scenes.py")
CACHE_DIR = os.environ.get("MANIM_CACHE_DIR", os.path.join(tempfile.gettempdir(), "manim-studio-cache"))
CACHE_MAX = 60
LOCK = threading.Lock()
app = Flask(__name__)

SIZES = {(1280, 720), (720, 1280), (1920, 1080), (1080, 1920), (854, 480), (480, 854)}
THEMES = {"reference", "neon", "storyboard", "clinical", "kids", "toddler", "teen", "blueprint3d", "reel", "sketchnote"}

DOCS = {
    "function_plot": "Plot y = f(x). params: expr, xmin, xmax, [ymin, ymax], points[{x,label}] (max 5), area[a,b], tangent x, caption",
    "vector_add": "Vectors tip-to-tail with the resultant. params: vectors[{x,y,label}] (max 4), resultant (bool)",
    "projectile": "Projectile motion with H, R, t. params: v0 (m/s), angle (deg), g",
    "equation_steps": "Step-by-step algebra. params: lines[] (max 6), notes[] (one per line, shown beside the arrow)",
    "wave": "Waves and their sum. params: waves[{amp,freq,phase}] (max 3), sum (bool)",
    "unit_circle": "Unit circle with sin and cos. params: angle (deg)",
    "pythagoras": "Squares on the sides of a right triangle. params: a, b",
}


# --------------------------------------------------------------------------------------------------------- validation
class Bad(ValueError):
    pass


def num(v, lo, hi, name, default=None):
    if v is None or v == "":
        if default is None:
            raise Bad("%s is required" % name)
        return default
    try:
        x = float(v)
    except (TypeError, ValueError):
        raise Bad("%s must be a number" % name)
    if not np.isfinite(x) or x < lo or x > hi:
        raise Bad("%s must be between %s and %s" % (name, lo, hi))
    return x


def txt(v, n, name="text", required=False):
    s = re.sub(r"[\x00-\x1f\x7f]", " ", str(v if v is not None else ""))
    s = re.sub(r"\s+", " ", s).strip()
    if required and not s:
        raise Bad("%s is required" % name)
    return s[:n]


def items(v, n, name):
    if v is None:
        return []
    if not isinstance(v, list):
        raise Bad("%s must be a list" % name)
    return v[:n]


def v_function_plot(p):
    expr = txt(p.get("expr"), 120, "expr", True)
    try:
        f = safe_function(expr)
    except (ValueError, SyntaxError) as e:
        raise Bad("formula not allowed: %s" % e)
    xmin = num(p.get("xmin"), -1000, 1000, "xmin", -5.0)
    xmax = num(p.get("xmax"), -1000, 1000, "xmax", 5.0)
    if xmax - xmin < 0.1:
        raise Bad("xmax must be larger than xmin")
    ys = f(np.linspace(xmin, xmax, 200))
    if not np.isfinite(ys).any():
        raise Bad("the formula has no real values between xmin and xmax")
    out = {"expr": expr, "xmin": xmin, "xmax": xmax}
    if p.get("ymin") is not None and p.get("ymax") is not None:
        lo, hi = num(p["ymin"], -1e6, 1e6, "ymin"), num(p["ymax"], -1e6, 1e6, "ymax")
        if hi > lo:
            out["ymin"], out["ymax"] = lo, hi
    pts = []
    for q in items(p.get("points"), 5, "points"):
        if isinstance(q, dict) and q.get("x") is not None:
            x = num(q["x"], xmin, xmax, "point x")
            pts.append({"x": x, "label": txt(q.get("label"), 24)})
    out["points"] = pts
    if p.get("area") is not None:
        a = items(p["area"], 2, "area")
        if len(a) == 2:
            out["area"] = [num(a[0], xmin, xmax, "area start"), num(a[1], xmin, xmax, "area end")]
    if p.get("tangent") is not None:
        out["tangent"] = num(p["tangent"], xmin, xmax, "tangent")
    out["caption"] = txt(p.get("caption"), 90)
    return out


def v_vector_add(p):
    vs = []
    for i, v in enumerate(items(p.get("vectors"), 4, "vectors")):
        if not isinstance(v, dict):
            raise Bad("each vector needs x and y")
        vs.append({"x": num(v.get("x"), -50, 50, "vector x"), "y": num(v.get("y"), -50, 50, "vector y"),
                   "label": txt(v.get("label"), 12) or chr(65 + i)})
    if not vs:
        raise Bad("vector_add needs at least one vector")
    return {"vectors": vs, "resultant": bool(p.get("resultant", True))}


def v_projectile(p):
    return {"v0": num(p.get("v0"), 1, 200, "v0", 20.0), "angle": num(p.get("angle"), 5, 85, "angle", 45.0), "g": num(p.get("g"), 1, 30, "g", 9.8)}


def v_equation_steps(p):
    lines = [txt(s, 60) for s in items(p.get("lines"), 6, "lines")]
    lines = [s for s in lines if s]
    if not lines:
        raise Bad("equation_steps needs at least one line")
    return {"lines": lines, "notes": [txt(s, 40) for s in items(p.get("notes"), 6, "notes")]}


def v_wave(p):
    ws = []
    for w in items(p.get("waves"), 3, "waves") or [{}]:
        w = w if isinstance(w, dict) else {}
        ws.append({"amp": num(w.get("amp"), 0.1, 3, "amp", 1.0), "freq": num(w.get("freq"), 0.2, 5, "freq", 1.0), "phase": num(w.get("phase"), -6.3, 6.3, "phase", 0.0)})
    return {"waves": ws, "sum": bool(p.get("sum", True))}


def v_unit_circle(p):
    return {"angle": num(p.get("angle"), 0, 360, "angle", 50.0)}


def v_pythagoras(p):
    return {"a": num(p.get("a"), 1, 20, "a", 3.0), "b": num(p.get("b"), 1, 20, "b", 4.0)}


VALIDATORS = {"function_plot": v_function_plot, "vector_add": v_vector_add, "projectile": v_projectile, "equation_steps": v_equation_steps,
              "wave": v_wave, "unit_circle": v_unit_circle, "pythagoras": v_pythagoras}


def validate(body):
    if not isinstance(body, dict):
        raise Bad("send a JSON object")
    template = str(body.get("template", ""))
    if template not in VALIDATORS:
        raise Bad("unknown template '%s' (use one of: %s)" % (template[:30], ", ".join(sorted(VALIDATORS))))
    params = body.get("params")
    if not isinstance(params, dict):
        raise Bad("params must be an object")
    size = (int(body.get("width", 1280) or 1280), int(body.get("height", 720) or 720))
    if size not in SIZES:
        raise Bad("size must be one of %s" % sorted(SIZES))
    fps = int(body.get("fps", 30) or 30)
    if fps not in (24, 30):
        raise Bad("fps must be 24 or 30")
    theme = str(body.get("theme", "reference"))
    return {"template": template, "params": VALIDATORS[template](params), "seconds": num(body.get("seconds"), 2, 60, "seconds", 8.0),
            "theme": theme if theme in THEMES else "reference", "width": size[0], "height": size[1], "fps": fps,
            "preview": bool(body.get("preview", False))}


# --------------------------------------------------------------------------------------------------------- rendering
def _cache_path(spec):
    return os.path.join(CACHE_DIR, hashlib.sha1(json.dumps(spec, sort_keys=True).encode()).hexdigest() + (".png" if spec["preview"] else ".mp4"))


def _trim_cache():
    files = sorted(glob.glob(os.path.join(CACHE_DIR, "*")), key=os.path.getmtime)
    for f in files[:-CACHE_MAX]:
        try:
            os.remove(f)
        except OSError:
            pass


def _clean_error(stderr):
    text = re.sub(r"\x1b\[[0-9;]*m", "", stderr or "")
    for line in reversed(text.splitlines()):
        if re.match(r"^(ValueError|TypeError|KeyError|IndexError|ZeroDivisionError|RuntimeError|AttributeError):", line.strip()):
            return line.strip()[:240]
    tail = " ".join(l.strip() for l in text.splitlines()[-6:] if l.strip())
    return (tail or "the scene could not be rendered")[:240]


def render(spec):
    os.makedirs(CACHE_DIR, exist_ok=True)
    cached = _cache_path(spec)
    if os.path.isfile(cached):
        os.utime(cached, None)
        return open(cached, "rb").read()
    work = tempfile.mkdtemp(prefix="manim-")
    try:
        pj = os.path.join(work, "params.json")
        json.dump({"template": spec["template"], "params": spec["params"], "seconds": spec["seconds"], "theme": spec["theme"]}, open(pj, "w", encoding="utf-8"))
        cmd = [sys.executable, "-m", "manim", "render", "-r", "%d,%d" % (spec["width"], spec["height"]), "--fps", str(spec["fps"]),
               "--disable_caching", "--media_dir", os.path.join(work, "media"), "-o", "out"]
        if spec["preview"]:
            cmd.append("-s")        # save only the last frame: no movie, much faster
        cmd += [SCENES, "StudioScene"]
        timeout = min(900, 120 + spec["seconds"] * 8)
        r = subprocess.run(cmd, env=dict(os.environ, STUDIO_PARAMS=pj), capture_output=True, text=True, timeout=timeout, cwd=work)
        if r.returncode != 0:
            raise RuntimeError(_clean_error(r.stderr + "\n" + r.stdout))
        pattern = "images/**/*.png" if spec["preview"] else "videos/**/*.mp4"
        found = [p for p in glob.glob(os.path.join(work, "media", pattern), recursive=True) if "partial_movie_files" not in p]
        if not found:
            raise RuntimeError("manim produced no output")
        data = open(found[0], "rb").read()
        if len(data) < 500:
            raise RuntimeError("manim produced an empty file")
        shutil.copyfile(found[0], cached)
        _trim_cache()
        return data
    finally:
        shutil.rmtree(work, ignore_errors=True)


@app.get("/health")
def health():
    try:
        import manim
        version = manim.__version__
    except Exception:   # noqa: BLE001
        version = "unavailable"
    return jsonify({"ok": True, "manim": version, "latex": bool(shutil.which("latex")), "templates": sorted(VALIDATORS)})


@app.get("/api/templates")
def templates():
    return jsonify({"templates": DOCS})


@app.post("/api/validate")
def api_validate():
    """Checks a request without rendering it (used at planning time, so a bad formula is caught before any GPU or render time)."""
    try:
        validate(request.get_json(force=True, silent=True))
    except Bad as e:
        return jsonify({"error": str(e)}), 400
    return jsonify({"ok": True})


@app.post("/api/render")
def api_render():
    try:
        spec = validate(request.get_json(force=True, silent=True))
    except Bad as e:
        return jsonify({"error": str(e)}), 400
    started = time.time()
    with LOCK:      # one render at a time: it is CPU bound and manim keeps global state
        try:
            data = render(spec)
        except subprocess.TimeoutExpired:
            return jsonify({"error": "the render took too long and was stopped"}), 504
        except Exception as e:   # noqa: BLE001
            return jsonify({"error": str(e)}), 422
    resp = Response(data, mimetype="image/png" if spec["preview"] else "video/mp4")
    resp.headers["X-Render-Seconds"] = "%.1f" % (time.time() - started)
    return resp


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("MANIM_PORT", "5008")), threaded=True)
