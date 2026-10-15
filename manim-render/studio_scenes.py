"""
Manim scene templates for AI Story Studio.

The language model never writes Python: it picks a template and fills in parameters (numbers, short texts). Every parameter is
validated in server.py before this module runs, and formulas go through safe_function() (a tiny whitelisted expression
evaluator), so nothing a model or a user types can execute code.

Run by the server as:  manim render studio_scenes.py StudioScene   with STUDIO_PARAMS pointing at a JSON file:
  {"template": "function_plot", "params": {...}, "seconds": 12.0, "theme": "reference"}
Each template spreads its steps over `seconds` (so the animation is paced to the narration) and holds the last frame.
Text uses Unicode (x², √, π, θ ...) because LaTeX is optional and not required here.
"""
import json
import math
import os
import sys

import numpy as np
from manim import *  # noqa: F401,F403

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from safe_expr import safe_function  # noqa: E402

try:  # the studio's lesson font (OFL); anything missing falls back to fontconfig defaults
    import manimpango
    _FONT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fonts")
    for _f in ("Lato-Regular.ttf", "Lato-Bold.ttf"):
        _p = os.path.join(_FONT_DIR, _f)
        if os.path.isfile(_p):
            manimpango.register_font(_p)
except Exception:  # noqa: BLE001
    pass
FONT = "Lato"

# Manim keeps the 14.22 x 8 frame whatever -r says, which stretches portrait renders: make the frame match the pixel aspect ratio.
config.frame_height = 8.0
config.frame_width = 8.0 * config.pixel_width / config.pixel_height

PALETTES = {
    "reference":   dict(bg="#08202F", fg="#EAF4F7", muted="#7FA3B3", a1="#2FB3A6", a2="#FFC83D", a3="#FF6B81", a4="#8F7CFF", grid="#1B4257"),
    "neon":        dict(bg="#05060D", fg="#F2F6FF", muted="#8A93B8", a1="#3BE8FF", a2="#FFE14A", a3="#FF4FD8", a4="#7CFF9B", grid="#1A1F3A"),
    "storyboard":  dict(bg="#F7FAFC", fg="#1F2937", muted="#64748B", a1="#0EA5A4", a2="#D97706", a3="#DC2626", a4="#6D28D9", grid="#D5DEE8"),
    "clinical":    dict(bg="#F3FAF8", fg="#173B45", muted="#5E7A82", a1="#1F9AA8", a2="#D98200", a3="#E4572E", a4="#7B6CD9", grid="#CFE5E2"),
    "kids":        dict(bg="#FFF8EC", fg="#2B2340", muted="#6B6480", a1="#1EA7FD", a2="#FF8A00", a3="#FF5D8F", a4="#8A5CFF", grid="#EADFCB"),
    "teen":        dict(bg="#0D0B1E", fg="#F2F0FF", muted="#9C96C8", a1="#3BE8FF", a2="#3DFFA2", a3="#FF4FD8", a4="#9D7BFF", grid="#2A2450"),
    "blueprint3d": dict(bg="#0B1C3A", fg="#EAF1FF", muted="#8DA4C8", a1="#7FB8FF", a2="#FFC83D", a3="#FF5A6A", a4="#5BE38C", grid="#1C3A6B"),
    "reel":        dict(bg="#0A0A0B", fg="#F2F2F2", muted="#8A8A8F", a1="#4ADE80", a2="#38BDF8", a3="#F87171", a4="#FACC15", grid="#26262B"),
}
PALETTES["toddler"] = PALETTES["kids"]
PALETTES["sketchnote"] = PALETTES["storyboard"]

# ----------------------------------------------------------------------------------------------------------- helpers
def nice_step(span, target=6):
    raw = max(span / target, 1e-9)
    mag = 10 ** math.floor(math.log10(raw))
    for m in (1, 2, 5, 10):
        if raw <= m * mag:
            return m * mag
    return 10 * mag


def fmt(v):
    return ("%.3g" % v) if abs(v) < 1000 else ("%d" % round(v))


class StudioScene(Scene):
    def setup(self):
        spec = json.load(open(os.environ["STUDIO_PARAMS"], encoding="utf-8"))
        self.tpl = spec["template"]
        self.a = spec.get("params", {})
        self.total = max(2.0, float(spec.get("seconds", 8)))
        self.c = PALETTES.get(spec.get("theme", "reference"), PALETTES["reference"])
        self.W = config.frame_width
        self.H = config.frame_height
        self.portrait = self.H > self.W
        self.camera.background_color = self.c["bg"]

    # ---- text ------------------------------------------------------------------------------------------------
    def text(self, s, size=26, color=None, weight=NORMAL, max_w=None):
        t = Text(s, font=FONT, font_size=size, color=color or self.c["fg"], weight=weight)
        limit = max_w if max_w else self.W * 0.9
        if t.width > limit:
            t.scale(limit / t.width)
        return t

    def ticks(self, axes, xr, yr, xstep, ystep):
        out = VGroup()
        v = math.ceil(xr[0] / xstep) * xstep
        while v <= xr[1] + 1e-9:
            if abs(v) > 1e-9:
                out.add(Text(fmt(v), font=FONT, font_size=16, color=self.c["muted"]).next_to(axes.c2p(v, 0), DOWN, buff=0.1))
            v += xstep
        v = math.ceil(yr[0] / ystep) * ystep
        while v <= yr[1] + 1e-9:
            if abs(v) > 1e-9:
                out.add(Text(fmt(v), font=FONT, font_size=16, color=self.c["muted"]).next_to(axes.c2p(0, v), LEFT, buff=0.1))
            v += ystep
        return out

    def make_axes(self, xr, yr, w, h):
        xs, ys = nice_step(xr[1] - xr[0]), nice_step(yr[1] - yr[0])
        ax = Axes(x_range=[xr[0], xr[1], xs], y_range=[yr[0], yr[1], ys], x_length=w, y_length=h,
                  axis_config=dict(color=self.c["muted"], stroke_width=2, include_tip=False, include_numbers=False))
        return ax, self.ticks(ax, xr, yr, xs, ys)

    def curve(self, axes, xs, ys, ymin, ymax, color, width=5):
        segs, cur = [], []
        for x, y in zip(xs, ys):
            if np.isfinite(y) and ymin - 1e-9 <= y <= ymax + 1e-9:
                cur.append(axes.c2p(x, y))
            else:
                if len(cur) > 1:
                    segs.append(cur)
                cur = []
        if len(cur) > 1:
            segs.append(cur)
        if not segs:
            raise ValueError("nothing to draw in this range")
        g = VGroup()
        for s in segs:
            m = VMobject(stroke_color=color, stroke_width=width)
            m.set_points_as_corners(np.array(s))
            g.add(m)
        return g

    def run_steps(self, steps):
        hold = min(1.2, max(0.4, self.total * 0.12))
        avail = max(0.6, self.total - hold)
        wsum = sum(w for w, _ in steps)
        for w, fn in steps:
            fn(max(0.15, avail * w / wsum))
        self.wait(hold)

    def construct(self):
        getattr(self, "t_" + self.tpl)()

    # ================================================================================================ function_plot
    def t_function_plot(self):
        a, c = self.a, self.c
        f = safe_function(a["expr"])
        xmin, xmax = float(a.get("xmin", -5)), float(a.get("xmax", 5))
        if xmax <= xmin:
            xmax = xmin + 1
        if "ymin" in a and "ymax" in a and a["ymax"] > a["ymin"]:
            ymin, ymax = float(a["ymin"]), float(a["ymax"])
        else:
            ys = f(np.linspace(xmin, xmax, 500))
            ys = ys[np.isfinite(ys)]
            if ys.size == 0:
                raise ValueError("expression has no finite values in range")
            lo, hi = np.percentile(ys, [2, 98])
            if hi - lo < 1e-6:
                lo, hi = lo - 1, hi + 1
            pad = 0.15 * (hi - lo)
            ymin, ymax = float(lo - pad), float(hi + pad)
        xr = [min(xmin, 0.0), max(xmax, 0.0)]
        yr = [min(ymin, 0.0), max(ymax, 0.0)]
        w, h = (self.W * 0.82, min(self.H * 0.42, self.W * 0.95)) if self.portrait else (self.W * 0.74, self.H * 0.58)
        ax, tick = self.make_axes(xr, yr, w, h)
        ax.move_to(DOWN * (0.15 if self.portrait else 0.35))
        tick = self.ticks(ax, xr, yr, nice_step(xr[1] - xr[0]), nice_step(yr[1] - yr[0]))
        xs = np.linspace(xmin, xmax, 600)
        graph = self.curve(ax, xs, f(xs), yr[0], yr[1], c["a2"], 5)
        steps = [(2, lambda t: self.play(Create(ax), FadeIn(tick), run_time=t)),
                 (3, lambda t: self.play(Create(graph, lag_ratio=0, run_time=t)) if len(graph) == 1 else self.play(*[Create(g) for g in graph], run_time=t))]
        extras = []
        for p in (a.get("points") or [])[:5]:
            x = float(p["x"])
            y = float(f(np.array([x]))[0])
            if not np.isfinite(y):
                continue
            dot = Dot(ax.c2p(x, y), color=c["a3"], radius=0.09)
            lines = VGroup(DashedLine(ax.c2p(x, 0), ax.c2p(x, y), color=c["muted"], stroke_width=2),
                           DashedLine(ax.c2p(0, y), ax.c2p(x, y), color=c["muted"], stroke_width=2))
            lab = self.text(str(p.get("label") or "(%s, %s)" % (fmt(x), fmt(y))), 22, c["a3"], BOLD, self.W * 0.4).next_to(dot, UP if y >= 0 else DOWN, buff=0.15)
            extras.append(VGroup(lines, dot, lab))
        if extras:
            steps.append((2, lambda t: self.play(*[FadeIn(e, scale=0.8) for e in extras], run_time=t)))
        if a.get("area") and len(a["area"]) == 2:
            lo_x, hi_x = sorted(float(v) for v in a["area"])
            lo_x, hi_x = max(lo_x, xmin), min(hi_x, xmax)
            gx = np.linspace(lo_x, hi_x, 120)
            gy = np.clip(f(gx), yr[0], yr[1])
            pts = [ax.c2p(lo_x, 0)] + [ax.c2p(x, y) for x, y in zip(gx, gy) if np.isfinite(y)] + [ax.c2p(hi_x, 0)]
            area = Polygon(*pts, stroke_width=0, fill_color=c["a1"], fill_opacity=0.4)
            steps.append((2, lambda t: self.play(FadeIn(area), run_time=t)))
        if a.get("tangent") is not None:
            x0 = float(a["tangent"])
            hh = 1e-4
            y0 = float(f(np.array([x0]))[0])
            m = float((f(np.array([x0 + hh]))[0] - f(np.array([x0 - hh]))[0]) / (2 * hh))
            if np.isfinite(y0) and np.isfinite(m):
                dx = (xr[1] - xr[0]) * 0.18
                tl = Line(ax.c2p(x0 - dx, y0 - m * dx), ax.c2p(x0 + dx, y0 + m * dx), color=c["a4"], stroke_width=4)
                tdot = Dot(ax.c2p(x0, y0), color=c["a4"], radius=0.09)
                tlab = self.text("slope \u2248 %s" % fmt(m), 22, c["a4"], BOLD, self.W * 0.4).next_to(tdot, UP + RIGHT, buff=0.1)
                steps.append((2, lambda t: self.play(Create(tl), FadeIn(tdot), FadeIn(tlab), run_time=t)))
        if a.get("caption"):
            cap = self.text(str(a["caption"]), 24, c["fg"], NORMAL, self.W * 0.88).to_edge(DOWN, buff=0.45)
            steps.append((1, lambda t: self.play(FadeIn(cap, shift=UP * 0.2), run_time=t)))
        self.run_steps(steps)

    # ================================================================================================ vector_add
    def t_vector_add(self):
        a, c = self.a, self.c
        vecs = [(float(v["x"]), float(v["y"]), str(v.get("label") or chr(65 + i))) for i, v in enumerate((a.get("vectors") or [])[:4])]
        if not vecs:
            raise ValueError("vector_add needs at least one vector")
        pts = [(0.0, 0.0)]
        for vx, vy, _ in vecs:
            pts.append((pts[-1][0] + vx, pts[-1][1] + vy))
        xs_ = [p[0] for p in pts] + [0]
        ys_ = [p[1] for p in pts] + [0]
        pad = 1.0
        xr = [math.floor(min(xs_) - pad), math.ceil(max(xs_) + pad)]
        yr = [math.floor(min(ys_) - pad), math.ceil(max(ys_) + pad)]
        unit = min((self.W * 0.82) / (xr[1] - xr[0]), (self.H * (0.5 if self.portrait else 0.62)) / (yr[1] - yr[0]))
        w, h = unit * (xr[1] - xr[0]), unit * (yr[1] - yr[0])
        ax, _ = self.make_axes(xr, yr, w, h)
        ax.move_to(DOWN * 0.2)
        tick = self.ticks(ax, xr, yr, nice_step(xr[1] - xr[0]), nice_step(yr[1] - yr[0]))
        cols = [c["a1"], c["a3"], c["a4"], c["a2"]]
        steps = [(2, lambda t: self.play(Create(ax), FadeIn(tick), run_time=t))]
        for i, (vx, vy, lab) in enumerate(vecs):
            s, e = ax.c2p(*pts[i]), ax.c2p(*pts[i + 1])
            arr = Arrow(s, e, buff=0, color=cols[i % 4], stroke_width=7, max_tip_length_to_length_ratio=0.2)
            lt = self.text("%s = (%s, %s)" % (lab, fmt(vx), fmt(vy)), 22, cols[i % 4], BOLD, self.W * 0.45)
            lt.move_to((s + e) / 2 + rotate_vector(normalize(e - s), PI / 2) * 0.35)
            steps.append((2, (lambda arr_, lt_: lambda t: self.play(GrowArrow(arr_), FadeIn(lt_), run_time=t))(arr, lt)))
        if a.get("resultant", True) and len(vecs) > 1:
            rx, ry = pts[-1]
            res = Arrow(ax.c2p(0, 0), ax.c2p(rx, ry), buff=0, color=c["fg"], stroke_width=8, max_tip_length_to_length_ratio=0.18)
            rl = self.text("R = (%s, %s)  |R| = %s" % (fmt(rx), fmt(ry), fmt(math.hypot(rx, ry))), 24, c["fg"], BOLD, self.W * 0.8).to_edge(DOWN, buff=0.45)
            comps = VGroup(DashedLine(ax.c2p(0, 0), ax.c2p(rx, 0), color=c["muted"], stroke_width=2),
                           DashedLine(ax.c2p(rx, 0), ax.c2p(rx, ry), color=c["muted"], stroke_width=2))
            steps.append((3, lambda t: self.play(GrowArrow(res), Create(comps), FadeIn(rl, shift=UP * 0.2), run_time=t)))
        self.run_steps(steps)

    # ================================================================================================ projectile
    def t_projectile(self):
        a, c = self.a, self.c
        v0 = float(a.get("v0", 20))
        th = math.radians(float(a.get("angle", 45)))
        g = float(a.get("g", 9.8))
        vx, vy = v0 * math.cos(th), v0 * math.sin(th)
        T = 2 * vy / g
        R, H = vx * T, vy * vy / (2 * g)
        xr, yr = [0, R * 1.12], [0, max(H * 1.5, 1.0)]
        w, h = (self.W * 0.84, self.H * 0.32) if self.portrait else (self.W * 0.78, self.H * 0.42)
        ax, _ = self.make_axes(xr, yr, w, h)
        ax.move_to(UP * (0.9 if self.portrait else 0.8))
        tick = self.ticks(ax, xr, yr, nice_step(xr[1] - xr[0]), nice_step(yr[1] - yr[0]))
        ts = np.linspace(0, T, 160)
        path_pts = [ax.c2p(vx * t, vy * t - 0.5 * g * t * t) for t in ts]
        path = VMobject(stroke_color=c["a2"], stroke_width=4).set_points_as_corners(np.array(path_pts))
        ball = Dot(path_pts[0], color=c["a3"], radius=0.12)
        p0_, p1_ = ax.c2p(0, 0), ax.c2p(0.05 * vx, 0.05 * vy)          # along the curve's real starting tangent on screen
        launch = Arrow(p0_, p0_ + normalize(p1_ - p0_) * (1.1 if not self.portrait else 0.9),
                       buff=0, color=c["a1"], stroke_width=6, max_tip_length_to_length_ratio=0.3)
        info = VGroup(
            self.text("v\u2080 = %s m/s at %s\u00b0" % (fmt(v0), fmt(math.degrees(th))), 28, c["a1"], BOLD, self.W * 0.88),
            self.text("H = %s m    R = %s m    t = %s s" % (fmt(H), fmt(R), fmt(T)), 28, c["a3"], BOLD, self.W * 0.88)
        ).arrange(DOWN, buff=0.18)
        forms = VGroup(self.text("H = v\u2080\u00b2 sin\u00b2\u03b8 / 2g", 24, c["muted"], NORMAL, self.W * 0.88),
                       self.text("R = v\u2080\u00b2 sin 2\u03b8 / g", 24, c["muted"], NORMAL, self.W * 0.88)).arrange(DOWN, buff=0.12)
        VGroup(info, forms).arrange(DOWN, buff=0.35).to_edge(DOWN, buff=0.35)
        steps = [(1.5, lambda t: self.play(Create(ax), FadeIn(tick), run_time=t)),
                 (1, lambda t: self.play(GrowArrow(launch), FadeIn(ball), run_time=t)),
                 (4, lambda t: self.play(Create(path), MoveAlongPath(ball, path), run_time=t, rate_func=linear)),
                 (2, lambda t: self.play(FadeIn(info, shift=UP * 0.2), run_time=t)),
                 (2, lambda t: self.play(FadeIn(forms), run_time=t))]
        self.run_steps(steps)

    # ================================================================================================ equation_steps
    def t_equation_steps(self):
        a, c = self.a, self.c
        lines = [str(s) for s in (a.get("lines") or [])[:6]]
        notes = [str(s) for s in (a.get("notes") or [])]
        if not lines:
            raise ValueError("equation_steps needs lines")
        rows = VGroup(*[self.text(s, 54, c["fg"], BOLD, self.W * 0.86) for s in lines]).arrange(DOWN, buff=0.95 if len(lines) < 5 else 0.62)
        rows.move_to(UP * 0.1)
        steps = []
        for i, row in enumerate(rows):
            def step(t, i=i, row=row):
                anims = [Write(row)] if i == 0 else [FadeIn(row, shift=DOWN * 0.25)]
                if i > 0:
                    arrow = Arrow(rows[i - 1].get_bottom() + DOWN * 0.05, row.get_top() + UP * 0.05, buff=0.02, color=c["a1"], stroke_width=5, max_tip_length_to_length_ratio=0.3)
                    anims.append(GrowArrow(arrow))
                    if i < len(notes) and notes[i].strip():
                        n = self.text(notes[i], 26, c["a2"], NORMAL, self.W * 0.4)
                        n.next_to(arrow, RIGHT, buff=0.25)
                        anims.append(FadeIn(n))
                self.play(*anims, run_time=max(0.15, t - (0.2 if i > 0 else 0)))
                if i > 0:
                    self.play(rows[i - 1].animate.set_opacity(0.55), run_time=0.2)
            steps.append((2 if i else 1.5, step))
        box = SurroundingRectangle(rows[-1], color=c["a3"], buff=0.18, stroke_width=4, corner_radius=0.12)
        steps.append((1.5, lambda t: self.play(Create(box), run_time=t)))
        self.run_steps(steps)

    # ================================================================================================ wave
    def t_wave(self):
        a, c = self.a, self.c
        waves = [(float(w.get("amp", 1)), float(w.get("freq", 1)), float(w.get("phase", 0))) for w in (a.get("waves") or [{}])[:3]]
        amp_max = max(1.0, sum(w[0] for w in waves))
        xr, yr = [0, 4 * PI], [-amp_max * 1.15, amp_max * 1.15]
        wd, ht = (self.W * 0.86, self.H * 0.3) if self.portrait else (self.W * 0.8, self.H * 0.55)
        ax = Axes(x_range=[xr[0], xr[1], PI], y_range=[yr[0], yr[1], 1], x_length=wd, y_length=ht,
                  axis_config=dict(color=c["muted"], stroke_width=2, include_tip=False, include_numbers=False))
        ax.move_to(DOWN * 0.1)
        cols = [c["a1"], c["a3"], c["a4"]]
        xs = np.linspace(xr[0], xr[1], 500)
        steps = [(1.2, lambda t: self.play(Create(ax), run_time=t))]
        total = np.zeros_like(xs)
        legend = VGroup()
        for i, (A, fq, ph) in enumerate(waves):
            ys = A * np.sin(fq * xs + ph)
            total += ys
            g = self.curve(ax, xs, ys, yr[0], yr[1], cols[i], 4)
            lab = self.text("A = %s, f = %s" % (fmt(A), fmt(fq)), 20, cols[i], BOLD, self.W * 0.5)
            legend.add(lab)
            steps.append((2, (lambda g_: lambda t: self.play(*[Create(s) for s in g_], run_time=t))(g)))
        legend.arrange(DOWN, aligned_edge=LEFT, buff=0.1).to_corner(UL, buff=0.4) if not self.portrait else legend.arrange(DOWN, buff=0.1).to_edge(UP, buff=0.9)
        steps.insert(1, (0.6, lambda t: self.play(FadeIn(legend), run_time=t)))
        if a.get("sum", True) and len(waves) > 1:
            span = max(1e-6, float(np.max(np.abs(total))))
            gs = self.curve(ax, xs, total, yr[0], yr[1], c["a2"], 7)
            sl = self.text("sum of the waves", 24, c["a2"], BOLD, self.W * 0.6).to_edge(DOWN, buff=0.5)
            steps.append((3, lambda t: self.play(*[Create(s) for s in gs], FadeIn(sl), run_time=t)))
        self.run_steps(steps)

    # ================================================================================================ unit_circle
    def t_unit_circle(self):
        a, c = self.a, self.c
        deg = float(a.get("angle", 50)) % 360
        th = math.radians(deg)
        r = min(self.W * 0.36, self.H * 0.3) if self.portrait else min(self.H * 0.32, self.W * 0.2)
        centre = (UP * 0.5 if self.portrait else LEFT * self.W * 0.1 + UP * 0.2)
        circle = Circle(radius=r, color=c["muted"], stroke_width=3).move_to(centre)
        axes = VGroup(Line(centre + LEFT * r * 1.3, centre + RIGHT * r * 1.3, color=c["muted"], stroke_width=2),
                      Line(centre + DOWN * r * 1.3, centre + UP * r * 1.3, color=c["muted"], stroke_width=2))
        p0 = centre + RIGHT * r
        dot = Dot(p0, color=c["a2"], radius=0.1)
        tracker = ValueTracker(0)

        def pos():
            t_ = tracker.get_value()
            return centre + np.array([r * math.cos(t_), r * math.sin(t_), 0])
        radius_line = always_redraw(lambda: Line(centre, pos(), color=c["a2"], stroke_width=4))
        sin_line = always_redraw(lambda: Line(pos(), centre + np.array([pos()[0] - centre[0], 0, 0]), color=c["a3"], stroke_width=5))
        cos_line = always_redraw(lambda: Line(centre, centre + np.array([pos()[0] - centre[0], 0, 0]), color=c["a1"], stroke_width=5))
        dot.add_updater(lambda m: m.move_to(pos()))
        arc = always_redraw(lambda: Arc(radius=r * 0.28, start_angle=0, angle=max(1e-3, tracker.get_value()), color=c["a4"], stroke_width=4, arc_center=centre))
        cs, sn = math.cos(th), math.sin(th)
        l1 = self.text("cos \u03b8 = %.2f" % cs, 28, c["a1"], BOLD, self.W * 0.8)
        l2 = self.text("sin \u03b8 = %.2f" % sn, 28, c["a3"], BOLD, self.W * 0.8)
        l3 = self.text("\u03b8 = %s\u00b0" % fmt(deg), 28, c["a4"], BOLD, self.W * 0.8)
        info = VGroup(l3, l1, l2).arrange(DOWN, aligned_edge=LEFT if not self.portrait else ORIGIN, buff=0.2)
        info.to_edge(DOWN, buff=0.7) if self.portrait else info.move_to(RIGHT * self.W * 0.22 + UP * 0.2)
        ident = self.text("sin\u00b2\u03b8 + cos\u00b2\u03b8 = 1", 26, c["fg"], BOLD, self.W * 0.8)
        ident.to_edge(DOWN, buff=0.35) if not self.portrait else ident.next_to(info, DOWN, buff=0.3)
        self.add(radius_line, sin_line, cos_line, arc)
        steps = [(1.5, lambda t: self.play(Create(circle), Create(axes), FadeIn(dot), run_time=t)),
                 (4, lambda t: self.play(tracker.animate.set_value(th), run_time=t, rate_func=smooth)),
                 (2, lambda t: self.play(FadeIn(info, shift=UP * 0.2), run_time=t)),
                 (1.5, lambda t: self.play(FadeIn(ident), run_time=t))]
        self.run_steps(steps)

    # ================================================================================================ pythagoras
    def t_pythagoras(self):
        a, c = self.a, self.c
        A, B = float(a.get("a", 3)), float(a.get("b", 4))
        C = math.hypot(A, B)
        span = A + B + C if self.portrait else (A + B + C)
        s = min(self.W * 0.8 / (A + B + 0.01), self.H * (0.34 if self.portrait else 0.5) / (max(A, B) + C * 0.6 + 0.01))
        P0 = LEFT * (A * s) / 2 + DOWN * 0.1 + (UP * 0.7 if self.portrait else ORIGIN)
        # right angle at P0; leg a along +x, leg b along +y
        pa, pb, pc = P0 + RIGHT * A * s, P0 + UP * B * s, P0
        tri = Polygon(pc, pa, pb, color=c["fg"], stroke_width=4)
        sq_a = Polygon(pc, pa, pa + DOWN * A * s, pc + DOWN * A * s, color=c["a1"], fill_color=c["a1"], fill_opacity=0.28, stroke_width=3)
        sq_b = Polygon(pc, pb, pb + LEFT * B * s, pc + LEFT * B * s, color=c["a3"], fill_color=c["a3"], fill_opacity=0.28, stroke_width=3)
        hv = pb - pa
        nrm = rotate_vector(hv / np.linalg.norm(hv), -PI / 2)
        nrm = nrm if nrm[0] + nrm[1] > 0 else -nrm       # outward (away from the right angle)
        sq_c = Polygon(pa, pb, pb + nrm * C * s, pa + nrm * C * s, color=c["a2"], fill_color=c["a2"], fill_opacity=0.28, stroke_width=3)
        fig = VGroup(tri, sq_a, sq_b, sq_c)
        limit_w, limit_h = self.W * 0.88, self.H * (0.52 if self.portrait else 0.62)
        fig.scale(min(1.0, limit_w / fig.width, limit_h / fig.height))
        fig.move_to(UP * (0.9 if self.portrait else 0.55))
        la = self.text("a\u00b2 = %s" % fmt(A * A), 26, c["a1"], BOLD, self.W * 0.4).move_to(sq_a.get_center())
        lb = self.text("b\u00b2 = %s" % fmt(B * B), 26, c["a3"], BOLD, self.W * 0.4).move_to(sq_b.get_center())
        lc = self.text("c\u00b2 = %s" % fmt(C * C), 26, c["a2"], BOLD, self.W * 0.4).move_to(sq_c.get_center())
        eq = VGroup(self.text("a\u00b2 + b\u00b2 = c\u00b2", 32, c["fg"], BOLD, self.W * 0.85),
                    self.text("%s + %s = %s" % (fmt(A * A), fmt(B * B), fmt(C * C)), 28, c["muted"], NORMAL, self.W * 0.85)).arrange(DOWN, buff=0.2).to_edge(DOWN, buff=0.4)
        steps = [(2, lambda t: self.play(Create(tri), run_time=t)),
                 (1.5, lambda t: self.play(FadeIn(sq_a), FadeIn(la), run_time=t)),
                 (1.5, lambda t: self.play(FadeIn(sq_b), FadeIn(lb), run_time=t)),
                 (1.5, lambda t: self.play(FadeIn(sq_c), FadeIn(lc), run_time=t)),
                 (2, lambda t: self.play(FadeIn(eq, shift=UP * 0.2), run_time=t))]
        self.run_steps(steps)
