package com.aistorystudio.conceptexplainer;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws one Concept Explainer scene as a 1920x1080 neon infographic panel in the style of the
 * approved reference (black background, glowing neon borders, numbered header, crisp text,
 * syntax-coloured code, labelled boxes, tables, checklists).
 *
 * Text, code, tables and diagrams are drawn here - pixel exact and always spelled correctly.
 * The image model is used only for the one real-world object in analogy scenes (bottle with a
 * price tag, delivery bag ...), which is blended onto the panel.
 *
 * {@link #render} returns cumulative BUILD STEPS (step 0 = header + first element, last step =
 * full slide). The service shows each step when the narration reaches it, so what appears on
 * screen follows what the voice is saying.
 *
 * Pure Java2D: no Spring, no network, testable on its own.
 */
public final class ConceptSlideRenderer {

    public static final int W = 1920, H = 1080;

    // ---------------------------------------------------------------- model

    public record Callout(String label, String detail) { }
    public record CodePart(String token, String label, String detail) { }
    public record Item(String icon, String code, String label, Boolean ok, String text) { }
    public record BoxSpec(String label, String value) { }
    public record Mapping(String left, String right) { }

    /** One scene. Unused fields stay null. */
    public record Slide(int number, String template, String title, String text, String caption,
                        String code, List<Callout> callouts, List<CodePart> parts,
                        List<String> columns, List<List<String>> rows, List<Item> items,
                        BoxSpec box, BoxSpec after, String statement, List<Mapping> mappings,
                        String formula, String formulaResult, BufferedImage illustration) {
    }

    public static final Set<String> TEMPLATES = Set.of("definition", "analogy", "analogy_code", "code_anatomy",
            "table", "code_visual", "code_block", "example_list", "checklist", "summary");

    public static final Set<String> ICONS = Set.of("bank", "fuel", "calendar", "parking", "cart", "phone", "wallet",
            "bag", "clock", "home", "bulb", "car", "book", "money", "chart", "lock", "cloud", "box", "ticket", "food");

    // reference palette
    static final Color CYAN = new Color(0, 220, 255), MAGENTA = new Color(255, 60, 220), YELLOW = new Color(255, 225, 60),
            GREEN = new Color(70, 255, 120), ORANGE = new Color(255, 160, 40), VIOLET = new Color(170, 110, 255),
            WHITE = new Color(240, 244, 250), RED = new Color(255, 60, 80), DIM = new Color(150, 160, 180);
    private static final Color[] BORDER = {MAGENTA, CYAN, CYAN, GREEN, MAGENTA, GREEN, CYAN, MAGENTA, CYAN, YELLOW, GREEN, MAGENTA};
    private static final Color[] TITLE = {YELLOW, CYAN, YELLOW, GREEN, YELLOW, GREEN, CYAN, YELLOW, CYAN, YELLOW, GREEN, YELLOW};
    private static final Color[] ACCENT = {MAGENTA, CYAN, YELLOW, GREEN, ORANGE, VIOLET};

    // ---------------------------------------------------------------- fonts

    private final Font titleFont, bodyFont, mediumFont, monoFont;
    private final Map<Character.UnicodeScript, Font> scriptFonts = new LinkedHashMap<>();

    public ConceptSlideRenderer() {
        titleFont = load("BarlowSemiCondensed-SemiBold.ttf", new Font(Font.SANS_SERIF, Font.BOLD, 10));
        mediumFont = load("BarlowSemiCondensed-Medium.ttf", new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        bodyFont = load("BarlowSemiCondensed-Regular.ttf", new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        monoFont = load("FiraMono-Medium.ttf", new Font(Font.MONOSPACED, Font.PLAIN, 10));
        Object[][] indic = {
                {Character.UnicodeScript.KANNADA, "NotoSansKannada.ttf"}, {Character.UnicodeScript.DEVANAGARI, "NotoSansDevanagari.ttf"},
                {Character.UnicodeScript.TAMIL, "NotoSansTamil.ttf"}, {Character.UnicodeScript.TELUGU, "NotoSansTelugu.ttf"},
                {Character.UnicodeScript.MALAYALAM, "NotoSansMalayalam.ttf"}, {Character.UnicodeScript.BENGALI, "NotoSansBengali.ttf"},
                {Character.UnicodeScript.GUJARATI, "NotoSansGujarati.ttf"}, {Character.UnicodeScript.GURMUKHI, "NotoSansGurmukhi.ttf"},
                {Character.UnicodeScript.ORIYA, "NotoSansOriya.ttf"}};
        for (Object[] e : indic) {
            Font f = load((String) e[1], null);
            if (f != null) scriptFonts.put((Character.UnicodeScript) e[0], f);
        }
    }

    private static Font load(String name, Font fallback) {
        try (InputStream in = ConceptSlideRenderer.class.getResourceAsStream("/concept-fonts/" + name)) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception ignored) { }
        try { // dev / test fallback
            Path p = Path.of(System.getProperty("concept.fonts", "concept-fonts"), name);
            if (Files.isRegularFile(p)) return Font.createFont(Font.TRUETYPE_FONT, p.toFile());
        } catch (Exception ignored) { }
        return fallback;
    }

    // ---------------------------------------------------------------- public API

    /** Number of build steps this slide will have (always >= 1). */
    public int stepCount(Slide s) {
        return switch (s.template()) {
            case "definition", "analogy", "analogy_code", "code_visual", "summary" -> 3;
            case "code_anatomy" -> 1 + Math.min(4, size(s.parts()));
            case "table" -> 1 + Math.min(4, Math.max(1, (size(s.rows()) + 1) / 2));
            case "code_block" -> Math.max(1, Math.min(5, lines(s.code()).size()));
            case "example_list" -> 1 + Math.min(5, size(s.items()));
            case "checklist" -> Math.max(1, Math.min(6, size(s.items())));
            default -> 1;
        };
    }

    /** Cumulative build steps; the last one is the complete slide. */
    public List<BufferedImage> render(Slide s) {
        int n = stepCount(s);
        List<BufferedImage> out = new ArrayList<>();
        for (int k = 0; k < n; k++) out.add(renderStep(s, k, n));
        return out;
    }

    // ---------------------------------------------------------------- frame

    private BufferedImage renderStep(Slide s, int step, int steps) {
        BufferedImage sharp = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        BufferedImage glow = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Canvas c = new Canvas(sharp.createGraphics(), glow.createGraphics());
        int idx = Math.floorMod(s.number() - 1, BORDER.length);
        Color border = BORDER[idx];

        // Premium reference-style frame: black canvas, luminous rounded frame, numbered badge,
        // strong editorial title and restrained inner glow. Keep every scene crisp and uncluttered.
        c.neonRect(28, 24, W - 56, H - 48, 38, border, 4.5f);
        c.neonCircle(112, 108, 50, border, 5.5f);
        c.text(String.valueOf(s.number()), 112 - widthOf(String.valueOf(s.number()), titleFont, 58) / 2f, 129, titleFont, 58, WHITE, true);
        String title = fit(s.title(), titleFont, 70, W - 360);
        c.text(title, 188, 132, titleFont, 70, TITLE[idx], true);
        c.line(188, 158, (int)Math.min(W - 90, 188 + widthOf(title, titleFont, 70)), 158, new Color(border.getRed(), border.getGreen(), border.getBlue(), 110), 2f);

        switch (s.template()) {
            case "definition" -> definition(c, s, step);
            case "analogy" -> analogy(c, s, step, false);
            case "analogy_code" -> analogy(c, s, step, true);
            case "code_anatomy" -> codeAnatomy(c, s, step);
            case "table" -> table(c, s, step, steps);
            case "code_visual" -> codeVisual(c, s, step);
            case "code_block" -> codeBlock(c, s, step, steps);
            case "example_list" -> exampleList(c, s, step);
            case "checklist" -> checklist(c, s, step, steps);
            case "summary" -> summary(c, s, step);
            default -> paragraph(c, s.text(), 120, 260, W - 240, 44, 12);
        }
        c.dispose();
        return compose(sharp, glow);
    }

    // ---------------------------------------------------------------- templates

    private void definition(Canvas c, Slide s, int step) {
        // Progressive teaching composition matching the approved legacy video while preserving
        // the newer premium reference layout: step 0 introduces the idea and mental model;
        // step 1 adds the explanation callouts; step 2 adds code and the final memory formula.
        int top = 205;
        paragraph(c, s.text(), 120, top + 20, W - 240, 52, 3, WHITE, bodyFont);

        int boxTop = 405;
        BoxSpec b = s.box() == null ? new BoxSpec("name", "value") : s.box();
        c.panel(100, boxTop - 10, 820, 470, 28, CYAN);
        c.text("REAL-WORLD MENTAL MODEL", 140, boxTop + 42, mediumFont, 30, CYAN, true);
        isoBox(c, 175, boxTop + 72, 640, 335, b.label(), b.value());

        if (step == 1 && s.callouts() != null) {
            int x = 980, y = 455;
            for (int i = 0; i < Math.min(3, s.callouts().size()); i++) {
                Callout co = s.callouts().get(i);
                Color col = i == 0 ? MAGENTA : i == 1 ? GREEN : YELLOW;
                c.curveArrow(925, y + 24, x - 25, y + 24, col);
                c.text(nz(co.label()), x, y, mediumFont, 36, col, true);
                int end = paragraph(c, co.detail(), x, y + 20, 690, 32, 2, WHITE, bodyFont);
                y = Math.max(y + 98, end + 45);
            }
        }

        if (step >= 2) {
            // Once the narrator has finished the mental model, reveal the code in the open
            // lower-right area and keep the final formula visible. The reveal is still a single
            // cumulative slide, so earlier elements never disappear.
            int codeX = 935, codeY = 700, codeW = 825, codeH = 150;
            c.panel(codeX, codeY, codeW, codeH, 24, CYAN);
            c.dot(codeX + 35, codeY + 32, 7, RED); c.dot(codeX + 59, codeY + 32, 7, YELLOW); c.dot(codeX + 83, codeY + 32, 7, GREEN);
            c.text("JAVA CODE", codeX + 115, codeY + 42, mediumFont, 25, WHITE, false);
            c.line(codeX + 20, codeY + 60, codeX + codeW - 20, codeY + 60, new Color(0, 220, 255, 90), 1.5f);
            String code = nz(s.code());
            int fs = 58;
            while (fs > 38 && widthOf(code, monoFont, fs) > codeW - 70) fs -= 2;
            syntax(c, code, codeX + 35, codeY + 120, fs);

            c.neonRect(95, 930, W - 190, 70, 18, GREEN, 3f);
            c.text("REMEMBER", 130, 975, titleFont, 29, GREEN, true);
            c.text("TYPE", 350, 975, mediumFont, 23, MAGENTA, true);
            c.text("+", 425, 975, mediumFont, 23, WHITE, true);
            c.text("NAME", 470, 975, mediumFont, 23, CYAN, true);
            c.text("+", 565, 975, mediumFont, 23, WHITE, true);
            c.text("VALUE", 610, 975, mediumFont, 23, YELLOW, true);
            c.text("= Java variable", 750, 975, mediumFont, 27, WHITE, false);
        }
    }

    private void analogy(Canvas c, Slide s, int step, boolean withCode) {
        int y = paragraph(c, s.text(), 120, 250, W - 240, 52, 3);
        int top = Math.max(y + 30, 420);
        int imgW = withCode ? 700 : 820, imgH = H - 90 - top;
        if (step >= 1) {
            c.panel(100, top - 10, imgW + 30, imgH + 20, 26, CYAN);
            c.text("REAL-WORLD CONNECTION", 135, top + 34, mediumFont, 28, CYAN, true);
            illustration(c, s.illustration(), 125, top + 48, imgW - 20, imgH - 55);
        }
        if (step >= 2) {
            if (withCode) {
                codeBox(c, s.code(), 900, top + 20, W - 900 - 120, imgH - 40, 44, CYAN, Integer.MAX_VALUE);
            } else if (s.callouts() != null) {
                int ly = top + 70;
                for (int i = 0; i < Math.min(4, s.callouts().size()); i++) {
                    Callout co = s.callouts().get(i);
                    Color col = i % 2 == 0 ? GREEN : YELLOW;
                    int tx = 140 + imgW + 160;
                    c.curveArrow(tx - 20, ly - 14, 140 + imgW - 60, top + imgH / 3 + i * 70, col);
                    c.text(co.label(), tx, ly, mediumFont, 60, col, true);
                    if (co.detail() != null && !co.detail().isBlank()) {
                        ly += 20;
                        ly = paragraph(c, co.detail(), tx, ly, W - tx - 120, 46, 2, WHITE, bodyFont) - 10;
                    }
                    ly += 90;
                }
                if (s.caption() != null) {
                    rightText(c, s.caption(), W - 120, H - 110, 48, MAGENTA);
                }
            }
        }
    }

    private void codeAnatomy(Canvas c, Slide s, int step) {
        String code = s.code() == null ? "" : s.code().split("\\R")[0];
        int size = 96;
        float cw = widthOf(code, monoFont, size);
        while (cw > W - 400 && size > 48) { size -= 4; cw = widthOf(code, monoFont, size); }
        int boxX = (int) (W / 2f - cw / 2f - 70), boxY = 260, boxW = (int) cw + 140, boxH = 190;
        c.neonRect(boxX, boxY, boxW, boxH, 26, CYAN, 4f);
        float codeX = W / 2f - cw / 2f;
        int baseline = boxY + boxH / 2 + size / 3;
        syntax(c, code, codeX, baseline, size);
        List<CodePart> parts = s.parts() == null ? List.of() : s.parts();
        int n = Math.min(4, parts.size());
        if (n == 0) return;
        int gap = 40, lw = Math.min(420, (W - 240 - gap * (n - 1)) / n);
        int startX = (W - (lw * n + gap * (n - 1))) / 2, ly = 640, lh = 190;
        int searchFrom = 0;
        for (int i = 0; i < n; i++) {
            CodePart p = parts.get(i);
            int at = p.token() == null ? -1 : code.indexOf(p.token(), searchFrom);
            if (at < 0 && p.token() != null) at = code.indexOf(p.token());
            if (at >= 0) searchFrom = at + p.token().length();
            if (step < i + 1) continue;
            Color col = ACCENT[i % 3 == 0 ? 0 : i % 3 == 1 ? 1 : 2];
            if (i == 3) col = GREEN;
            int x = startX + i * (lw + gap);
            c.neonRect(x, ly, lw, lh, 18, col, 3.5f);
            centered(c, p.label(), x + lw / 2, ly + 80, mediumFont, 54, WHITE, lw - 30);
            centered(c, "(" + nz(p.detail()) + ")", x + lw / 2, ly + 145, bodyFont, 42, WHITE, lw - 30);
            if (at >= 0) {
                float tx = codeX + widthOf(code.substring(0, at), monoFont, size) + widthOf(p.token(), monoFont, size) / 2f;
                c.curveArrow(x + lw / 2, ly - 10, (int) tx, boxY + boxH - 30, col);
            }
        }
    }

    private void table(Canvas c, Slide s, int step, int steps) {
        List<String> cols = s.columns() == null ? List.of() : s.columns();
        List<List<String>> rows = s.rows() == null ? List.of() : s.rows();
        if (cols.isEmpty()) return;
        int x = 140, y = 230, w = W - 280;
        int[] cw = new int[cols.size()];
        int total = 0;
        for (int i = 0; i < cols.size(); i++) { cw[i] = i == 0 ? 3 : 4; total += cw[i]; }
        int headH = 100, rowH = Math.min(140, (H - 120 - y - headH) / Math.max(1, rows.size()));
        int shownRows = step == 0 ? 0 : Math.min(rows.size(), (int) Math.ceil(rows.size() * step / (double) (steps - 1)));
        int tableH = headH + rowH * Math.max(1, shownRows);
        c.neonRect(x, y, w, tableH, 14, GREEN, 3.5f);
        int cx = x;
        for (int i = 0; i < cols.size(); i++) {
            int colW = w * cw[i] / total;
            centered(c, cols.get(i), cx + colW / 2, y + 66, mediumFont, 50, WHITE, colW - 20);
            if (i > 0) c.line(cx, y, cx, y + tableH, GREEN, 2f);
            cx += colW;
        }
        c.line(x, y + headH, x + w, y + headH, GREEN, 2.5f);
        for (int r = 0; r < shownRows; r++) {
            int ry = y + headH + r * rowH;
            if (r > 0) c.line(x, ry, x + w, ry, new Color(70, 255, 120, 120), 1.5f);
            cx = x;
            for (int i = 0; i < cols.size(); i++) {
                int colW = w * cw[i] / total;
                String v = i < rows.get(r).size() ? rows.get(r).get(i) : "";
                Color col = i == 0 ? ACCENT[r % ACCENT.length] : i == cols.size() - 1 ? YELLOW : WHITE;
                Font f = i == 0 ? mediumFont : bodyFont;
                int fs = rowH >= 120 ? 48 : 42;
                List<String> wrapped = wrap(v, f, fs, colW - 40);
                int nl = Math.min(2, wrapped.size());
                int ty = ry + rowH / 2 + fs / 3 - (nl - 1) * (fs / 2 + 2);
                for (String line : wrapped.subList(0, nl)) {
                    centered(c, line, cx + colW / 2, ty, f, fs, col, colW - 30);
                    ty += fs + 4;
                }
                cx += colW;
            }
        }
    }

    private void codeVisual(Canvas c, Slide s, int step) {
        int y = paragraph(c, s.text(), 120, 250, W - 240, 50, 2);
        int codeH = Math.min(260, 60 + lines(s.code()).size() * 66);
        codeBox(c, s.code(), 380, y + 30, W - 760, codeH, 54, CYAN, Integer.MAX_VALUE);
        int vy = y + 30 + codeH + 40;
        if (step >= 1) {
            int boxH = Math.max(200, H - (s.caption() == null ? 110 : 175) - vy);
            if (s.after() != null) {
                BoxSpec b = s.box() == null ? new BoxSpec("x", "") : s.box();
                isoBox(c, 330, vy, 440, boxH, b.label(), b.value());
                c.arrow(870, vy + boxH / 2, 1030, vy + boxH / 2, MAGENTA, 8f);
                isoBox(c, 1130, vy, 440, boxH, s.after().label(), s.after().value());
            } else {
                BoxSpec b = s.box() == null ? new BoxSpec("x", "") : s.box();
                isoBox(c, 640, vy, 560, boxH, b.label(), b.value());
            }
        }
        if (step >= 2 && s.caption() != null) {
            rightText(c, s.caption(), W - 130, H - 85, 46, YELLOW);
        }
    }

    private void codeBlock(Canvas c, Slide s, int step, int steps) {
        int y = 230;
        if (s.text() != null && !s.text().isBlank()) y = paragraph(c, s.text(), 120, 250, W - 240, 50, 2) + 20;
        List<String> ls = lines(s.code());
        int shown = steps <= 1 ? ls.size() : (int) Math.ceil(ls.size() * (step + 1) / (double) steps);
        int size = ls.size() > 8 ? 44 : 54;
        codeBox(c, s.code(), 180, y, W - 360, H - 100 - y, size, CYAN, shown);
    }

    private void exampleList(Canvas c, Slide s, int step) {
        int y = 230;
        if (s.text() != null && !s.text().isBlank()) y = paragraph(c, s.text(), 120, 250, W - 240, 50, 2) + 10;
        List<Item> items = s.items() == null ? List.of() : s.items();
        int n = Math.min(5, items.size());
        if (n == 0) return;
        int rowH = Math.min(150, (H - 100 - y) / n - 18);
        for (int i = 0; i < n; i++) {
            if (step < i + 1) break;
            Item it = items.get(i);
            Color col = ACCENT[i % ACCENT.length];
            int ry = y + i * (rowH + 18);
            c.neonRect(140, ry, rowH, rowH, 16, col, 3.5f);
            icon(c, it.icon(), 140 + rowH / 2, ry + rowH / 2, rowH * 0.58f, col);
            c.neonRect(140 + rowH + 22, ry, 1060, rowH, 16, MAGENTA, 3f);
            String code = it.code() == null ? "" : it.code();
            int fs = 50;
            while (widthOf(code, monoFont, fs) > 1000 && fs > 26) fs -= 2;
            syntax(c, code, 140 + rowH + 52, ry + rowH / 2 + fs / 3, fs);
            c.neonRect(140 + rowH + 22 + 1080, ry, W - 140 - (140 + rowH + 22 + 1080), rowH, 16, MAGENTA, 3f);
            List<String> lab = wrap(nz(it.label()), mediumFont, 46, W - 140 - (140 + rowH + 22 + 1080) - 40);
            int nl = Math.min(2, lab.size());
            int ty = ry + rowH / 2 + 16 - (nl - 1) * 25;
            for (String l : lab.subList(0, nl)) {
                c.text(l, 140 + rowH + 22 + 1100, ty, mediumFont, 46, YELLOW, false);
                ty += 50;
            }
        }
    }

    private void checklist(Canvas c, Slide s, int step, int steps) {
        List<Item> items = s.items() == null ? List.of() : s.items();
        int n = Math.min(7, items.size());
        int shown = steps <= 1 ? n : (int) Math.ceil(n * (step + 1) / (double) steps);
        int y = 240, rowH = Math.min(118, (H - 110 - y) / Math.max(1, n));
        for (int i = 0; i < shown; i++) {
            Item it = items.get(i);
            boolean ok = it.ok() == null || it.ok();
            Color col = ok ? GREEN : RED;
            int cy = y + i * rowH + rowH / 2;
            c.neonCircle(185, cy, 30, col, 4f);
            if (ok) c.polyline(new int[]{168, 181, 204}, new int[]{cy + 1, cy + 14, cy - 12}, col, 6f);
            else { c.line(172, cy - 13, 198, cy + 13, col, 6f); c.line(198, cy - 13, 172, cy + 13, col, 6f); }
            List<String> ws = wrap(nz(it.text()), bodyFont, 52, W - 400);
            int nl = Math.min(2, ws.size());
            int ty = cy + 18 - (nl - 1) * 28;
            for (String l : ws.subList(0, nl)) {
                c.text(l, 250, ty, bodyFont, 52, WHITE, false);
                ty += 56;
            }
        }
    }

    private void summary(Canvas c, Slide s, int step) {
        int y = 230;
        List<String> st = wrap(nz(s.statement()), mediumFont, 52, W - 420);
        int sh = 70 + st.size() * 62;
        c.neonRect(160, y, W - 320, sh, 22, YELLOW, 4f);
        int ty = y + 76;
        for (String l : st) { centered(c, l, W / 2, ty, mediumFont, 52, YELLOW, W - 380); ty += 62; }
        y += sh + 40;
        if (step >= 1 && s.mappings() != null) {
            for (int i = 0; i < Math.min(4, s.mappings().size()); i++) {
                Mapping m = s.mappings().get(i);
                c.neonRect(160, y, W - 320, 84, 14, MAGENTA, 3f);
                c.text(fit(m.left(), mediumFont, 50, 560), 200, y + 58, mediumFont, 50, ACCENT[(i + 1) % ACCENT.length], true);
                c.arrow(780, y + 42, 880, y + 42, WHITE, 4f);
                c.text(fit(m.right(), mediumFont, 50, W - 420 - 760), 920, y + 58, mediumFont, 50, YELLOW, false);
                y += 102;
            }
        }
        if (step >= 2 && s.formula() != null && !s.formula().isBlank()) {
            int fy = Math.min(y + 20, H - 200);
            float fw = widthOf(s.formula(), monoFont, 52);
            c.neonRect(160, fy, (int) fw + 80, 110, 18, MAGENTA, 4f);
            syntax(c, s.formula(), 200, fy + 72, 52);
            c.arrow((int) fw + 270, fy + 55, (int) fw + 380, fy + 55, YELLOW, 7f);
            paragraph(c, s.formulaResult(), (int) fw + 410, fy + 35, W - 160 - ((int) fw + 410), 52, 2, YELLOW, mediumFont);
        }
    }

    // ---------------------------------------------------------------- building blocks

    private void isoBox(Canvas c, int x, int y, int w, int h, String label, String value) {
        int depth = (int) (w * 0.16);
        int fx = x, fy = y + depth, fw = w - depth, fh = h - depth;
        Color edge = CYAN;
        // top + side faces
        c.poly(new int[]{fx, fx + depth, fx + fw + depth, fx + fw}, new int[]{fy, fy - depth, fy - depth, fy}, edge, 3.5f, new Color(0, 60, 90, 120));
        c.poly(new int[]{fx + fw, fx + fw + depth, fx + fw + depth, fx + fw}, new int[]{fy, fy - depth, fy - depth + fh, fy + fh}, edge, 3.5f, new Color(0, 40, 70, 140));
        c.neonRect(fx, fy, fw, fh, 6, edge, 4f);
        // label tag
        int tagW = (int) (fw * 0.62), tagH = (int) Math.min(110, fh * 0.28);
        int tx = fx + (fw - tagW) / 2, ty = fy + (int) (fh * 0.14);
        c.neonRect(tx, ty, tagW, tagH, 10, CYAN, 4f);
        int ls = (int) (tagH * 0.62);
        centered(c, nz(label), tx + tagW / 2, ty + tagH / 2 + ls / 3, mediumFont, ls, WHITE, tagW - 20);
        if (value != null && !value.isBlank()) {
            int vy = ty + tagH + (int) (fh * 0.08), vh = (int) Math.min(130, fh * 0.32);
            c.neonRect(tx, vy, tagW, vh, 10, YELLOW, 4f);
            int vs = (int) (vh * 0.62);
            centered(c, value, tx + tagW / 2, vy + vh / 2 + vs / 3, titleFont, vs, YELLOW, tagW - 20);
        }
    }

    private void illustration(Canvas c, BufferedImage img, int x, int y, int w, int h) {
        if (img == null) {
            c.neonRect(x + w / 4, y + h / 6, w / 2, (int) (h * 0.66), 30, CYAN, 4f);
            icon(c, "box", x + w / 2, y + h / 2, Math.min(w, h) * 0.3f, CYAN);
            return;
        }
        double scale = Math.min(w / (double) img.getWidth(), h / (double) img.getHeight());
        int dw = (int) (img.getWidth() * scale), dh = (int) (img.getHeight() * scale);
        c.screen(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh);
    }

    private void codeBox(Canvas c, String code, int x, int y, int w, int h, int size, Color border, int maxLines) {
        c.panel(x, y, w, h, 22, border);
        // editor-like top chrome, matching the reference visual language.
        c.dot(x + 34, y + 30, 7, RED); c.dot(x + 58, y + 30, 7, YELLOW); c.dot(x + 82, y + 30, 7, GREEN);
        c.text("JAVA CODE", x + 112, y + 40, mediumFont, 24, WHITE, false);
        c.line(x + 20, y + 58, x + w - 20, y + 58, new Color(border.getRed(), border.getGreen(), border.getBlue(), 90), 1.5f);
        List<String> ls = lines(code);
        int fs = Math.max(size, 76);
        while (fs > 22 && (maxLineWidth(ls, fs) > w - 80 || ls.size() * fs * 1.45 > h - 60)) fs -= 2;
        int blockH = (int) (ls.size() * fs * 1.45);
        int ly = y + Math.max(40, (h - blockH) / 2) + fs;
        for (int i = 0; i < Math.min(maxLines, ls.size()); i++) {
            syntax(c, ls.get(i), x + 40, ly, fs);
            ly += (int) (fs * 1.45);
        }
    }

    private int maxLineWidth(List<String> ls, int fs) {
        int m = 0;
        for (String l : ls) m = Math.max(m, (int) widthOf(l, monoFont, fs));
        return m;
    }

    private static final Set<String> TYPES = Set.of("int", "double", "float", "long", "short", "byte", "boolean", "char",
            "String", "void", "var", "let", "const", "def", "class", "interface", "enum", "struct", "List", "Map", "Set", "Integer", "Object");
    private static final Set<String> KEYWORDS = Set.of("public", "private", "protected", "static", "final", "return", "if", "else",
            "for", "while", "do", "new", "import", "from", "package", "try", "catch", "throw", "throws", "extends", "implements",
            "function", "async", "await", "in", "of", "and", "or", "not", "elif", "lambda", "with", "as", "print", "switch", "case", "break", "continue");
    private static final Set<String> LITERALS = Set.of("true", "false", "null", "None", "True", "False", "undefined");

    /** Reference colouring: types magenta, names cyan, values yellow/white, comments green. */
    private void syntax(Canvas c, String line, float x, int y, int size) {
        if (line == null) return;
        List<String[]> tokens = new ArrayList<>(); // [text, colorKey]
        int i = 0;
        while (i < line.length()) {
            char ch = line.charAt(i);
            if ((ch == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') || ch == '#') {
                tokens.add(new String[]{line.substring(i), "comment"});
                break;
            }
            if (ch == '"' || ch == '\'') {
                int j = line.indexOf(ch, i + 1);
                j = j < 0 ? line.length() : j + 1;
                tokens.add(new String[]{line.substring(i, j), "string"});
                i = j;
                continue;
            }
            if (Character.isLetter(ch) || ch == '_' || ch == '$') {
                int j = i;
                while (j < line.length() && (Character.isLetterOrDigit(line.charAt(j)) || line.charAt(j) == '_' || line.charAt(j) == '$')) j++;
                String w = line.substring(i, j);
                tokens.add(new String[]{w, TYPES.contains(w) ? "type" : KEYWORDS.contains(w) ? "keyword" : LITERALS.contains(w) ? "literal" : "name"});
                i = j;
                continue;
            }
            if (Character.isDigit(ch)) {
                int j = i;
                while (j < line.length() && (Character.isDigit(line.charAt(j)) || line.charAt(j) == '.' || line.charAt(j) == '_')) j++;
                tokens.add(new String[]{line.substring(i, j), "number"});
                i = j;
                continue;
            }
            tokens.add(new String[]{String.valueOf(ch), "op"});
            i++;
        }
        float cx = x;
        for (String[] t : tokens) {
            Color col = switch (t[1]) {
                case "type" -> MAGENTA;
                case "keyword" -> VIOLET;
                case "literal" -> YELLOW;
                case "name" -> CYAN;
                case "number", "string" -> YELLOW;
                case "comment" -> GREEN;
                default -> WHITE;
            };
            boolean glow = !t[1].equals("op") && !t[1].equals("comment");
            c.text(t[0], cx, y, monoFont, size, col, glow);
            cx += widthOf(t[0], monoFont, size);
        }
    }

    /** Simple neon line icons for example rows. */
    private void icon(Canvas c, String name, int cx, int cy, float s, Color col) {
        float h = s / 2;
        float st = Math.max(3f, s / 16);
        switch (name == null ? "box" : name) {
            case "bank" -> {
                c.poly(new int[]{(int) (cx - h), cx, (int) (cx + h)}, new int[]{(int) (cy - h * 0.35), (int) (cy - h), (int) (cy - h * 0.35)}, col, st, null);
                for (int k = -2; k <= 2; k++) c.line((int) (cx + k * h * 0.38), (int) (cy - h * 0.25), (int) (cx + k * h * 0.38), (int) (cy + h * 0.65), col, st);
                c.line((int) (cx - h), (int) (cy + h * 0.8), (int) (cx + h), (int) (cy + h * 0.8), col, st);
            }
            case "fuel" -> {
                c.neonRect((int) (cx - h * 0.7), (int) (cy - h), (int) (h * 1.1), (int) (h * 1.9), 6, col, st);
                c.neonRect((int) (cx - h * 0.5), (int) (cy - h * 0.75), (int) (h * 0.7), (int) (h * 0.5), 3, col, st);
                c.polyline(new int[]{(int) (cx + h * 0.4), (int) (cx + h * 0.8), (int) (cx + h * 0.8), (int) (cx + h * 0.6)},
                        new int[]{(int) (cy - h * 0.4), (int) (cy - h * 0.1), (int) (cy + h * 0.6), (int) (cy + h * 0.7)}, col, st);
            }
            case "calendar" -> {
                c.neonRect((int) (cx - h), (int) (cy - h * 0.8), (int) s, (int) (h * 1.7), 8, col, st);
                c.line((int) (cx - h), (int) (cy - h * 0.35), (int) (cx + h), (int) (cy - h * 0.35), col, st);
                for (int r = 0; r < 2; r++) for (int k = 0; k < 3; k++)
                    c.dot((int) (cx - h * 0.55 + k * h * 0.55), (int) (cy + r * h * 0.45), st * 1.2f, col);
            }
            case "parking" -> {
                c.neonRect((int) (cx - h), (int) (cy - h), (int) s, (int) s, 12, col, st);
                c.text("P", cx - widthOf("P", titleFont, (int) (s * 0.8)) / 2, (int) (cy + s * 0.28), titleFont, (int) (s * 0.8), col, true);
            }
            case "cart" -> {
                c.polyline(new int[]{(int) (cx - h), (int) (cx - h * 0.6), (int) (cx - h * 0.3), (int) (cx + h * 0.8), (int) (cx + h)},
                        new int[]{(int) (cy - h * 0.8), (int) (cy - h * 0.8), (int) (cy + h * 0.4), (int) (cy + h * 0.4), (int) (cy - h * 0.4)}, col, st);
                c.neonCircle((int) (cx - h * 0.2), (int) (cy + h * 0.75), (int) (h * 0.15), col, st);
                c.neonCircle((int) (cx + h * 0.6), (int) (cy + h * 0.75), (int) (h * 0.15), col, st);
            }
            case "phone" -> {
                c.neonRect((int) (cx - h * 0.55), (int) (cy - h), (int) (h * 1.1), (int) s, 12, col, st);
                c.dot(cx, (int) (cy + h * 0.75), st, col);
            }
            case "wallet", "money" -> {
                c.neonRect((int) (cx - h), (int) (cy - h * 0.65), (int) s, (int) (h * 1.3), 10, col, st);
                c.text("₹", cx - widthOf("₹", titleFont, (int) (s * 0.6)) / 2, (int) (cy + s * 0.2), titleFont, (int) (s * 0.6), col, true);
            }
            case "bag", "food" -> {
                c.poly(new int[]{(int) (cx - h * 0.8), (int) (cx + h * 0.8), (int) (cx + h * 0.65), (int) (cx - h * 0.65)},
                        new int[]{(int) (cy - h * 0.4), (int) (cy - h * 0.4), (int) (cy + h), (int) (cy + h)}, col, st, null);
                c.arc(cx, (int) (cy - h * 0.4), (int) (h * 0.4), col, st);
            }
            case "clock" -> {
                c.neonCircle(cx, cy, (int) h, col, st);
                c.line(cx, cy, cx, (int) (cy - h * 0.6), col, st);
                c.line(cx, cy, (int) (cx + h * 0.45), cy, col, st);
            }
            case "home" -> {
                c.poly(new int[]{(int) (cx - h), cx, (int) (cx + h)}, new int[]{(int) (cy - h * 0.1), (int) (cy - h), (int) (cy - h * 0.1)}, col, st, null);
                c.neonRect((int) (cx - h * 0.7), (int) (cy - h * 0.1), (int) (h * 1.4), (int) (h * 1.1), 4, col, st);
            }
            case "lock" -> {
                c.neonRect((int) (cx - h * 0.7), (int) (cy - h * 0.15), (int) (h * 1.4), (int) (h * 1.1), 8, col, st);
                c.arc(cx, (int) (cy - h * 0.15), (int) (h * 0.45), col, st);
            }
            case "chart" -> {
                c.line((int) (cx - h), (int) (cy + h), (int) (cx + h), (int) (cy + h), col, st);
                for (int k = 0; k < 3; k++) {
                    int bh = (int) (h * (0.6 + k * 0.6));
                    c.neonRect((int) (cx - h * 0.8 + k * h * 0.6), (int) (cy + h - bh), (int) (h * 0.35), bh, 2, col, st);
                }
            }
            case "bulb" -> {
                c.neonCircle(cx, (int) (cy - h * 0.2), (int) (h * 0.65), col, st);
                c.line((int) (cx - h * 0.3), (int) (cy + h * 0.65), (int) (cx + h * 0.3), (int) (cy + h * 0.65), col, st);
                c.line((int) (cx - h * 0.22), (int) (cy + h * 0.9), (int) (cx + h * 0.22), (int) (cy + h * 0.9), col, st);
            }
            default -> { // box / generic
                c.neonRect((int) (cx - h * 0.8), (int) (cy - h * 0.5), (int) (h * 1.6), (int) (h * 1.3), 6, col, st);
                c.line((int) (cx - h * 0.8), (int) (cy - h * 0.1), (int) (cx + h * 0.8), (int) (cy - h * 0.1), col, st);
            }
        }
    }

    // ---------------------------------------------------------------- text helpers

    private int paragraph(Canvas c, String text, int x, int y, int width, int size, int maxLines) {
        return paragraph(c, text, x, y, width, size, maxLines, WHITE, bodyFont);
    }

    /** Draws wrapped text; returns the y below the last line. */
    private int paragraph(Canvas c, String text, int x, int y, int width, int size, int maxLines, Color col, Font font) {
        if (text == null || text.isBlank()) return y - size;
        List<String> ws = wrap(text, font, size, width);
        int lineH = (int) (size * 1.22);
        int ty = y + size;
        for (int i = 0; i < Math.min(maxLines, ws.size()); i++) {
            String l = ws.get(i);
            if (i == maxLines - 1 && ws.size() > maxLines) l = fit(l + " …", font, size, width);
            c.text(l, x, ty, font, size, col, false);
            ty += lineH;
        }
        return ty - lineH + 10;
    }

    private void centered(Canvas c, String text, int cx, int baseline, Font f, int size, Color col, int maxW) {
        if (text == null) return;
        int s = size;
        while (widthOf(text, f, s) > maxW && s > 20) s -= 2;
        c.text(text, cx - widthOf(text, f, s) / 2f, baseline, f, s, col, false);
    }

    private void rightText(Canvas c, String text, int right, int baseline, int size, Color col) {
        List<String> ws = wrap(text, mediumFont, size, 760);
        int y = baseline - (ws.size() - 1) * (int) (size * 1.15);
        for (String l : ws) {
            c.text(l, right - widthOf(l, mediumFont, size), y, mediumFont, size, col, false);
            y += (int) (size * 1.15);
        }
    }

    private String fit(String text, Font f, int size, int maxW) {
        if (text == null) return "";
        if (widthOf(text, f, size) <= maxW) return text;
        String t = text;
        while (t.length() > 3 && widthOf(t + "…", f, size) > maxW) t = t.substring(0, t.length() - 1);
        return t.trim() + "…";
    }

    List<String> wrap(String text, Font f, int size, int maxW) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String para : text.split("\\R")) {
            StringBuilder cur = new StringBuilder();
            for (String w : para.trim().split("\\s+")) {
                if (w.isEmpty()) continue;
                String cand = cur.isEmpty() ? w : cur + " " + w;
                if (!cur.isEmpty() && widthOf(cand, f, size) > maxW) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    cur.append(w);
                } else {
                    cur.setLength(0);
                    cur.append(cand);
                }
            }
            if (!cur.isEmpty()) out.add(cur.toString());
        }
        return out;
    }

    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    float widthOf(String text, Font f, int size) {
        if (text == null || text.isEmpty()) return 0;
        return new TextLayout(attributed(text, f, size).getIterator(), FRC).getAdvance();
    }

    /** Base font, with Noto fallback runs for Indian scripts the base font can't draw. */
    private AttributedString attributed(String text, Font base, int size) {
        Font b = base.deriveFont((float) size);
        AttributedString as = new AttributedString(text);
        as.addAttribute(TextAttribute.FONT, b);
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            if (!b.canDisplay(cp)) {
                Font alt = null;
                try { alt = scriptFonts.get(Character.UnicodeScript.of(cp)); } catch (IllegalArgumentException ignored) { }
                if (alt == null) {
                    for (Font f : scriptFonts.values()) if (f.canDisplay(cp)) { alt = f; break; }
                }
                if (alt != null) {
                    int j = i + len;
                    while (j < text.length()) {
                        int cp2 = text.codePointAt(j);
                        Character.UnicodeScript sc = Character.UnicodeScript.of(cp2);
                        if (b.canDisplay(cp2) && sc != Character.UnicodeScript.COMMON && sc != Character.UnicodeScript.INHERITED) break;
                        j += Character.charCount(cp2);
                    }
                    as.addAttribute(TextAttribute.FONT, alt.deriveFont((float) size), i, j);
                    i = j;
                    continue;
                }
            }
            i += len;
        }
        return as;
    }

    // ---------------------------------------------------------------- utils

    private static int size(List<?> l) { return l == null ? 0 : l.size(); }

    private static String nz(String s) { return s == null ? "" : s; }

    static List<String> lines(String code) {
        List<String> out = new ArrayList<>();
        if (code == null || code.isBlank()) return out;
        for (String l : code.split("\\R")) out.add(l.replace("\t", "    "));
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) out.remove(out.size() - 1);
        return out;
    }

    /** sharp + blurred glow layer, added like light. */
    private static BufferedImage compose(BufferedImage sharp, BufferedImage glow) {
        int gw = W / 4, gh = H / 4;
        BufferedImage small = new BufferedImage(gw, gh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(glow, 0, 0, gw, gh, null);
        g.dispose();
        int[] px = small.getRGB(0, 0, gw, gh, null, 0, gw);
        for (int pass = 0; pass < 2; pass++) px = boxBlur(px, gw, gh, pass == 0 ? 2 : 3);
        small.setRGB(0, 0, gw, gh, px, 0, gw);
        BufferedImage big = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        g = big.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(small, 0, 0, W, H, null);
        g.dispose();
        int[] a = sharp.getRGB(0, 0, W, H, null, 0, W);
        int[] b = big.getRGB(0, 0, W, H, null, 0, W);
        for (int i = 0; i < a.length; i++) {
            int r = Math.min(255, ((a[i] >> 16) & 255) + (int) (((b[i] >> 16) & 255) * 0.42));
            int gg = Math.min(255, ((a[i] >> 8) & 255) + (int) (((b[i] >> 8) & 255) * 0.42));
            int bb = Math.min(255, (a[i] & 255) + (int) ((b[i] & 255) * 0.42));
            a[i] = (r << 16) | (gg << 8) | bb;
        }
        sharp.setRGB(0, 0, W, H, a, 0, W);
        return sharp;
    }

    private static int[] boxBlur(int[] src, int w, int h, int r) {
        int[] tmp = new int[src.length], out = new int[src.length];
        for (int y = 0; y < h; y++) blurLine(src, tmp, y * w, 1, w, r);
        for (int x = 0; x < w; x++) blurLine(tmp, out, x, w, h, r);
        return out;
    }

    private static void blurLine(int[] in, int[] out, int start, int step, int n, int r) {
        int sr = 0, sg = 0, sb = 0, div = 2 * r + 1;
        for (int i = -r; i <= r; i++) {
            int p = in[start + Math.min(n - 1, Math.max(0, i)) * step];
            sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
        }
        for (int i = 0; i < n; i++) {
            out[start + i * step] = ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
            int add = in[start + Math.min(n - 1, i + r + 1) * step], rem = in[start + Math.max(0, i - r) * step];
            sr += ((add >> 16) & 255) - ((rem >> 16) & 255);
            sg += ((add >> 8) & 255) - ((rem >> 8) & 255);
            sb += (add & 255) - (rem & 255);
        }
    }

    // ---------------------------------------------------------------- canvas: draws sharp + glow

    private final class Canvas {
        final Graphics2D g, gl;

        Canvas(Graphics2D g, Graphics2D gl) {
            this.g = g;
            this.gl = gl;
            for (Graphics2D x : List.of(g, gl)) {
                x.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                x.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                x.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                x.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                x.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                x.setColor(Color.BLACK);
                x.fillRect(0, 0, W, H);
            }
            g.setColor(new Color(4, 5, 10));
            g.fillRect(0, 0, W, H);
        }

        void stroke(Shape s, Color col, float width) {
            g.setColor(col);
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(s);
            gl.setColor(col);
            gl.setStroke(new BasicStroke(width * 2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            gl.draw(s);
        }

        void panel(int x, int y, int w, int h, int r, Color col) {
            RoundRectangle2D rr = new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2);
            g.setColor(new Color(5, 7, 14));
            g.fill(rr);
            // very subtle colored glass wash, not a gradient that competes with text.
            g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 12));
            g.fill(rr);
            stroke(rr, new Color(col.getRed(), col.getGreen(), col.getBlue(), 220), 3.2f);
            g.setColor(new Color(255, 255, 255, 22));
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Double(x + 2, y + 2, w - 4, h - 4, Math.max(2, r * 2 - 4), Math.max(2, r * 2 - 4)));
        }

        void neonRect(int x, int y, int w, int h, int r, Color col, float width) {
            RoundRectangle2D rr = new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2);
            boolean big = w > W / 2 && h > H / 2;
            g.setColor(big ? new Color(4, 5, 10) : new Color(col.getRed() / 28 + 3, col.getGreen() / 28 + 3, col.getBlue() / 28 + 5));
            g.fill(rr);
            stroke(rr, col, width);
            g.setColor(new Color(255, 255, 255, 18));
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Double(x + 3, y + 3, Math.max(1, w - 6), Math.max(1, h - 6), Math.max(2, r * 2 - 6), Math.max(2, r * 2 - 6)));
        }

        void neonCircle(int cx, int cy, int r, Color col, float width) {
            stroke(new Ellipse2D.Double(cx - r, cy - r, 2.0 * r, 2.0 * r), col, width);
        }

        void dot(int cx, int cy, float r, Color col) {
            Ellipse2D e = new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
            g.setColor(col); g.fill(e); gl.setColor(col); gl.fill(e);
        }

        void arc(int cx, int cy, int r, Color col, float width) {
            stroke(new java.awt.geom.Arc2D.Double(cx - r, cy - r, 2.0 * r, 2.0 * r, 0, 180, java.awt.geom.Arc2D.OPEN), col, width);
        }

        void line(int x1, int y1, int x2, int y2, Color col, float width) {
            stroke(new java.awt.geom.Line2D.Double(x1, y1, x2, y2), col, width);
        }

        void polyline(int[] xs, int[] ys, Color col, float width) {
            Path2D p = new Path2D.Double();
            p.moveTo(xs[0], ys[0]);
            for (int i = 1; i < xs.length; i++) p.lineTo(xs[i], ys[i]);
            stroke(p, col, width);
        }

        void poly(int[] xs, int[] ys, Color col, float width, Color fill) {
            Path2D p = new Path2D.Double();
            p.moveTo(xs[0], ys[0]);
            for (int i = 1; i < xs.length; i++) p.lineTo(xs[i], ys[i]);
            p.closePath();
            if (fill != null) { g.setColor(fill); g.fill(p); }
            stroke(p, col, width);
        }

        void arrow(int x1, int y1, int x2, int y2, Color col, float width) {
            line(x1, y1, x2, y2, col, width);
            double a = Math.atan2(y2 - y1, x2 - x1);
            int l = (int) (width * 4 + 10);
            polyline(new int[]{(int) (x2 - l * Math.cos(a - 0.5)), x2, (int) (x2 - l * Math.cos(a + 0.5))},
                    new int[]{(int) (y2 - l * Math.sin(a - 0.5)), y2, (int) (y2 - l * Math.sin(a + 0.5))}, col, width);
        }

        void curveArrow(int x1, int y1, int x2, int y2, Color col) {
            double mx = (x1 + x2) / 2.0, my = Math.min(y1, y2) - 40;
            stroke(new QuadCurve2D.Double(x1, y1, mx, my, x2, y2), col, 4f);
            double a = Math.atan2(y2 - my, x2 - mx);
            int l = 22;
            polyline(new int[]{(int) (x2 - l * Math.cos(a - 0.5)), x2, (int) (x2 - l * Math.cos(a + 0.5))},
                    new int[]{(int) (y2 - l * Math.sin(a - 0.5)), y2, (int) (y2 - l * Math.sin(a + 0.5))}, col, 4f);
        }

        void text(String text, float x, int baseline, Font f, int size, Color col, boolean glow) {
            if (text == null || text.isEmpty()) return;
            AttributedString as = attributed(text, f, size);
            as.addAttribute(TextAttribute.FOREGROUND, col);
            g.drawString(as.getIterator(), x, baseline);
            if (glow) {
                AttributedString gs = attributed(text, f, size);
                gs.addAttribute(TextAttribute.FOREGROUND, new Color(col.getRed(), col.getGreen(), col.getBlue()));
                gl.drawString(gs.getIterator(), x, baseline);
            }
        }

        /** Screen-blend an illustration (black = transparent) into the frame and the glow layer. */
        void screen(BufferedImage img, int x, int y, int w, int h) {
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D sg = scaled.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            sg.drawImage(img, 0, 0, w, h, null);
            sg.dispose();
            BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    int p = scaled.getRGB(xx, yy);
                    int r = (p >> 16) & 255, gg = (p >> 8) & 255, b = p & 255;
                    // lift-the-blacks: near-black noise becomes fully transparent
                    int lum = Math.max(r, Math.max(gg, b));
                    int alpha = Math.max(0, Math.min(255, (lum - 18) * 255 / 120));
                    // soft vignette so the illustration never shows a hard rectangular edge
                    double ex = Math.min(xx, w - 1 - xx) / (w * 0.08), ey = Math.min(yy, h - 1 - yy) / (h * 0.08);
                    alpha = (int) (alpha * Math.min(1.0, Math.min(ex, ey)));
                    mask.setRGB(xx, yy, (alpha << 24) | (r << 16) | (gg << 8) | b);
                }
            }
            g.setComposite(AlphaComposite.SrcOver);
            g.drawImage(mask, x, y, null);
            gl.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
            gl.drawImage(mask, x, y, null);
            gl.setComposite(AlphaComposite.SrcOver);
        }

        void dispose() {
            g.dispose();
            gl.dispose();
        }
    }
}
