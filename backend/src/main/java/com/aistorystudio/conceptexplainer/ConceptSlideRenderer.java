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

    public static final Set<String> TEMPLATES = Set.of("flow", "compare", "definition", "analogy", "analogy_code", "code_anatomy",
            "table", "code_visual", "code_block", "example_list", "checklist", "summary");

    public static final Set<String> ICONS = Set.of("bank", "fuel", "calendar", "parking", "cart", "phone", "wallet",
            "bag", "clock", "home", "bulb", "car", "book", "money", "chart", "lock", "cloud", "box", "ticket", "food");

    // reference palette
    // Palette slots (names kept from the neon design). The active STYLE fills them; WHITE means
    // "main text colour" (dark ink on paper styles).
    Color CYAN, MAGENTA, YELLOW, GREEN, ORANGE, VIOLET, WHITE, RED, DIM;
    private Color[] BORDER, TITLE, ACCENT;

    // ---------------------------------------------------------------- visual styles

    /** Look of a whole lesson. dark = light ink on a dark background (glow / screen blending). */
    public record Style(String id, String label, boolean dark, boolean glow, String stroke, Color[] palette,
                        boolean handFont, boolean popTitle, String illustrationStyle) { }

    private static Color c(int rgb) { return new Color(rgb); }

    /** palette order: cyan, magenta, yellow, green, orange, violet, text, red, dim */
    public static final Map<String, Style> STYLES = new LinkedHashMap<>();
    static {
        STYLES.put("neon", new Style("neon", "Neon glow", true, true, "neon",
                new Color[]{c(0x00DCFF), c(0xFF3CDC), c(0xFFE13C), c(0x46FF78), c(0xFFA028), c(0xAA6EFF), c(0xF0F4FA), c(0xFF3C50), c(0x96A0B4)},
                false, false, "premium glowing neon line art with subtle 3D depth, electric cyan, magenta, yellow and green outlines, isolated on a PURE BLACK background"));
        STYLES.put("reference", new Style("reference", "Technical tutorial (reference style)", true, false, "reference",
                new Color[]{c(0x52D8E8), c(0xA58BFA), c(0xF4C96B), c(0x6DD6A8), c(0xF3A879), c(0xB7A0FF), c(0xF1F6FA), c(0xFF8585), c(0x91A8B8)},
                false, false, "premium cinematic technical tutorial art direction, deep teal navy canvas, richly detailed concept-specific technical illustrations, intricate but legible architecture diagrams, layered depth, precise cyan connectors, restrained violet highlights, beautifully composed code-editor panels, subtle volumetric lighting and texture, high contrast, no neon glow, no generic template cards or decorative clutter"));
        STYLES.put("sketchnote", new Style("sketchnote", "Sketchnote (hand-drawn)", false, false, "sketch",
                new Color[]{c(0x2E9BB3), c(0xE07A5F), c(0xC9920E), c(0x4F9D5B), c(0xE8803A), c(0x8E6BC7), c(0x2A2A2A), c(0xD1495B), c(0x6B6B6B)},
                true, false, "rich hand-drawn editorial sketchnote illustration, fine-liner ink, carefully layered doodles, crosshatching, paper grain, soft peach and teal marker shading, many meaningful concept-specific details, on a warm paper background"));
        STYLES.put("storyboard", new Style("storyboard", "Clean storyboard (3D icons)", false, false, "card",
                new Color[]{c(0x2F7AE5), c(0xD63384), c(0xE09A00), c(0x2FA84F), c(0xF2711C), c(0x7B4FD6), c(0x1D2B45), c(0xE03131), c(0x5C6B80)},
                false, false, "richly detailed 3D clay editorial illustration, tactile material texture, carefully modeled shapes, soft cinematic studio lighting, ambient occlusion, multiple purposeful supporting details, polished educational film quality, on a clean light background"));
        STYLES.put("chalkboard", new Style("chalkboard", "Chalkboard classroom", true, false, "chalk",
                new Color[]{c(0x8FD3E8), c(0xF59AC3), c(0xF7E07A), c(0xA5E39A), c(0xF6B57A), c(0xC4A8F0), c(0xF2F2EC), c(0xFF8A8A), c(0xB7C4BC)},
                true, false, "detailed classroom chalk illustration with layered white and pastel chalk strokes, visible chalk dust, crosshatching and carefully drawn explanatory details on a deep green-black chalkboard"));
        STYLES.put("blueprint", new Style("blueprint", "Blueprint (engineering)", true, true, "blueprint",
                new Color[]{c(0x9FD8FF), c(0xFFB3D9), c(0xFFE08A), c(0xA8F0C0), c(0xFFC58A), c(0xD0B8FF), c(0xFFFFFF), c(0xFF9090), c(0xB8CDE8)},
                false, false, "intricate engineering blueprint illustration with precise white and cyan technical linework, sectional details, measurement marks, construction guides and layered schematic detail on deep blueprint blue"));
        STYLES.put("anime", new Style("anime", "Anime pop", false, false, "pop",
                new Color[]{c(0x1C9BEF), c(0xFF4FA3), c(0xF5B700), c(0x22B573), c(0xFF7A1A), c(0x8A5CFF), c(0x1A1A2E), c(0xFF3B3B), c(0x55556A)},
                false, true, "highly detailed anime educational illustration, expressive cel shading, bold clean outlines, rich environmental detail, dramatic but readable composition, vivid colors, polished anime key visual quality"));
    }

    private Style style = STYLES.get("neon");
    private long lessonSeed;

    public static Style style(String id) {
        return STYLES.getOrDefault(id == null ? "neon" : id.trim().toLowerCase(java.util.Locale.ROOT), STYLES.get("neon"));
    }

    private void applyStyle(Style st, long seed) {
        style = st;
        lessonSeed = seed;
        Color[] p = st.palette();
        CYAN = p[0]; MAGENTA = p[1]; YELLOW = p[2]; GREEN = p[3]; ORANGE = p[4]; VIOLET = p[5]; WHITE = p[6]; RED = p[7]; DIM = p[8];
        Color[] border = {MAGENTA, CYAN, CYAN, GREEN, MAGENTA, GREEN, CYAN, MAGENTA, CYAN, YELLOW, GREEN, MAGENTA};
        Color[] title = st.dark() ? new Color[]{YELLOW, CYAN, YELLOW, GREEN, YELLOW, GREEN, CYAN, YELLOW, CYAN, YELLOW, GREEN, YELLOW}
                : new Color[]{WHITE, CYAN, MAGENTA, GREEN, WHITE, VIOLET, CYAN, ORANGE, WHITE, MAGENTA, GREEN, CYAN};
        Color[] accent = {MAGENTA, CYAN, YELLOW, GREEN, ORANGE, VIOLET};
        // every lesson rotates the colour order, so two lessons never look identical
        int rot = (int) Math.floorMod(seed, 6);
        BORDER = rotate(border, rot);
        TITLE = rotate(title, rot);
        ACCENT = rotate(accent, rot);
        titleFont = st.popTitle() ? popFont : st.handFont() ? handBold : baseTitle;
        mediumFont = st.handFont() ? handBold : baseMedium;
        bodyFont = st.handFont() ? handRegular : baseBody;
    }

    private static Color[] rotate(Color[] in, int k) {
        Color[] out = new Color[in.length];
        for (int i = 0; i < in.length; i++) out[i] = in[(i + k) % in.length];
        return out;
    }

    /** 0 or 1 per scene: mirrored / alternative layouts so lessons are not all the same pattern. */
    private int variant(Slide s) {
        long h = lessonSeed * 0x9E3779B97F4A7C15L + s.number() * 0xBF58476D1CE4E5B9L;
        return (int) ((h >>> 29) & 1);
    }

    private boolean looksLikeCode(String t) {
        return t != null && (t.contains("=") || t.contains("(") || t.contains(";") || t.contains("{") || t.contains("->"));
    }

    // ---------------------------------------------------------------- fonts

    private Font titleFont, bodyFont, mediumFont;
    private final Font monoFont, baseTitle, baseBody, baseMedium, handBold, handRegular, popFont;
    private final Map<Character.UnicodeScript, Font> scriptFonts = new LinkedHashMap<>();

    public ConceptSlideRenderer() {
        baseTitle = load("BarlowSemiCondensed-SemiBold.ttf", new Font(Font.SANS_SERIF, Font.BOLD, 10));
        baseMedium = load("BarlowSemiCondensed-Medium.ttf", new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        baseBody = load("BarlowSemiCondensed-Regular.ttf", new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        handBold = load("Kalam-Bold.ttf", baseTitle);
        handRegular = load("Kalam-Regular.ttf", baseBody);
        popFont = load("Bangers-Regular.ttf", baseTitle);
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
        applyStyle(STYLES.get("neon"), 0);
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

    /**
     * Subtitle card for the classic storyboard video: rounded translucent panel with the spoken line
     * (Indian scripts via the bundled Noto fonts). Transparent around the card.
     */
    public synchronized BufferedImage caption(String text, String speaker, int maxWidth, int size) {
        applyStyle(STYLES.get("neon"), 0);
        String body = text == null ? "" : text.replace("**", "").trim();
        List<String> lines = wrap(body, baseMedium, size, maxWidth - 80);
        if (lines.size() > 3) {
            lines = new ArrayList<>(lines.subList(0, 3));
            lines.set(2, fit(lines.get(2) + " …", baseMedium, size, maxWidth - 80));
        }
        int lineH = (int) (size * 1.25);
        boolean named = speaker != null && !speaker.isBlank() && !speaker.equalsIgnoreCase("narrator");
        int nameH = named ? (int) (size * 0.95) : 0;
        int w = 80;
        for (String l : lines) w = Math.max(w, (int) widthOf(l, baseMedium, size) + 80);
        if (named) w = Math.max(w, (int) widthOf(speaker, baseTitle, (int) (size * 0.7)) + 80);
        w = Math.min(maxWidth, w);
        int h = nameH + lines.size() * lineH + 44;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(new Color(8, 10, 18, 185));
        g.fill(new RoundRectangle2D.Double(0, 0, w, h, 36, 36));
        g.setColor(new Color(255, 255, 255, 60));
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(1, 1, w - 2, h - 2, 36, 36));
        int y = 22;
        if (named) {
            AttributedString n = attributed(speaker, baseTitle, (int) (size * 0.7));
            n.addAttribute(TextAttribute.FOREGROUND, new Color(255, 214, 90));
            g.drawString(n.getIterator(), 40, y + (int) (size * 0.7));
            y += nameH;
        }
        for (String l : lines) {
            AttributedString as = attributed(l, baseMedium, size);
            as.addAttribute(TextAttribute.FOREGROUND, new Color(250, 250, 250));
            g.drawString(as.getIterator(), (w - widthOf(l, baseMedium, size)) / 2f, y + size);
            y += lineH;
        }
        g.dispose();
        return img;
    }

    /** Number of build steps this slide will have (always >= 1). */
    public int stepCount(Slide s) {
        return switch (s.template()) {
            case "definition", "analogy", "analogy_code", "code_visual", "summary" -> 3;
            case "code_anatomy" -> 1 + Math.min(4, size(s.parts()));
            case "table" -> 1 + Math.min(4, Math.max(1, (size(s.rows()) + 1) / 2));
            case "code_block" -> Math.max(1, Math.min(5, lines(s.code()).size()));
            case "example_list" -> 1 + Math.min(5, size(s.items()));
            case "checklist" -> Math.max(1, Math.min(6, size(s.items())));
            case "flow" -> 1 + Math.min(6, Math.max(1, size(s.items())));      // step 0 = intro, step k = node k
            case "compare" -> 1 + Math.min(8, Math.max(1, size(s.items())));   // step k = item k, in spoken order
            default -> 1;
        };
    }

    /** Cumulative build steps; the last one is the complete slide. */
    public List<BufferedImage> render(Slide s) {
        return render(s, "neon", 0);
    }

    /** styleId: neon | sketchnote | storyboard | chalkboard | blueprint | anime. seed: per lesson. */
    public synchronized List<BufferedImage> render(Slide s, String styleId, long seed) {
        applyStyle(style(styleId), seed);
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
        String num = String.valueOf(s.number());
        if (((lessonSeed >>> 3) & 1) == 0) { // badge left + title
            c.neonCircle(112, 108, 50, border, 5.5f);
            c.text(num, 112 - widthOf(num, titleFont, 58) / 2f, 129, titleFont, 58, WHITE, true);
            String title = fit(s.title(), titleFont, 70, W - 360);
            c.text(title, 188, 132, titleFont, 70, TITLE[idx], true);
            c.line(188, 158, (int) Math.min(W - 90, 188 + widthOf(title, titleFont, 70)), 158, new Color(border.getRed(), border.getGreen(), border.getBlue(), 110), 2f);
        } else { // centred title with a small step pill
            String title = fit(s.title(), titleFont, 74, W - 420);
            float tw = widthOf(title, titleFont, 74);
            c.text(title, W / 2f - tw / 2f, 136, titleFont, 74, TITLE[idx], true);
            c.neonRect((int) (W / 2f - tw / 2f) - 112, 82, 84, 64, 20, border, 3.5f);
            centered(c, num, (int) (W / 2f - tw / 2f) - 70, 128, titleFont, 44, WHITE, 70);
            c.line((int) (W / 2f - tw / 2f), 162, (int) (W / 2f + tw / 2f), 162, new Color(border.getRed(), border.getGreen(), border.getBlue(), 150), 3f);
        }

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
            case "flow" -> flow(c, s, step);
            case "compare" -> compare(c, s, step);
            case "summary" -> summary(c, s, step);
            default -> paragraph(c, s.text(), 120, 260, W - 240, 44, 12);
        }
        c.dispose();
        return style.glow() ? compose(sharp, glow) : sharp;
    }

    // ---------------------------------------------------------------- templates

    private void definition(Canvas c, Slide s, int step) {
        // step 0: explanation + mental-model diagram, step 1: callouts, step 2: code / memory line.
        // Only what the scene actually has is drawn (no hard-coded Java), and every other lesson
        // mirrors the layout (diagram right, callouts left).
        boolean mirror = variant(s) == 1;
        int top = 205;
        paragraph(c, s.text(), 120, top + 20, W - 240, 52, 3, WHITE, bodyFont);
        boolean hasCode = s.code() != null && !s.code().isBlank();
        boolean hasFormula = (s.formula() != null && !s.formula().isBlank()) || (s.formulaResult() != null && !s.formulaResult().isBlank());
        int boxTop = 405, panelX = mirror ? 1000 : 100, calloutX = mirror ? 130 : 980;
        int panelH = hasFormula ? 470 : 560;
        BoxSpec b = s.box() == null ? new BoxSpec("name", "value") : s.box();
        c.panel(panelX, boxTop - 10, 820, panelH, 28, CYAN);
        c.text("REAL-WORLD MENTAL MODEL", panelX + 40, boxTop + 42, mediumFont, 30, CYAN, true);
        isoBox(c, panelX + 75, boxTop + 72, 640, panelH - 135, b.label(), b.value());

        if (step >= 1 && s.callouts() != null) {
            int y = 455;
            int maxY = hasCode && step >= 2 ? 660 : 880;
            for (int i = 0; i < Math.min(3, s.callouts().size()) && y < maxY; i++) {
                Callout co = s.callouts().get(i);
                Color col = ACCENT[i % ACCENT.length];
                if (mirror) c.curveArrow(calloutX + 760, y + 24, panelX - 10, y + 24, col);
                else c.curveArrow(panelX + 825, y + 24, calloutX - 25, y + 24, col);
                c.text(nz(co.label()), calloutX, y, mediumFont, 40, col, true);
                int end = paragraph(c, co.detail(), calloutX, y + 20, 730, 34, 2, WHITE, bodyFont);
                y = Math.max(y + 104, end + 45);
            }
        }
        if (step >= 2 && hasCode) {
            int codeX = mirror ? 110 : 935, codeY = 700, codeW = 825, codeH = 150;
            String title = "CODE";
            codeBox(c, s.code(), codeX, codeY, codeW, codeH + (hasFormula ? 0 : 60), 58, CYAN, Integer.MAX_VALUE, title);
        }
        if (step >= 2 && hasFormula) {
            c.neonRect(95, 930, W - 190, 70, 18, GREEN, 3f);
            c.text("REMEMBER", 130, 975, titleFont, 29, GREEN, true);
            String left = nz(s.formula()), right = nz(s.formulaResult());
            String line = left.isBlank() ? right : right.isBlank() ? left : left + "  =  " + right;
            c.text(fit(line, mediumFont, 32, W - 520), 330, 976, mediumFont, 32, WHITE, false);
        }
    }

    private void analogy(Canvas c, Slide s, int step, boolean withCode) {
        int y = paragraph(c, s.text(), 120, 250, W - 240, 52, 3);
        int top = Math.max(y + 30, 420);
        int imgW = withCode ? 700 : 820, imgH = H - 90 - top;
        boolean mirror = variant(s) == 1 && !withCode;
        int imgX = mirror ? W - 130 - imgW : 100;
        if (step >= 1) {
            c.panel(imgX, top - 10, imgW + 30, imgH + 20, 26, CYAN);
            c.text("REAL-WORLD CONNECTION", imgX + 35, top + 34, mediumFont, 28, CYAN, true);
            illustration(c, s.illustration(), imgX + 25, top + 48, imgW - 20, imgH - 55);
        }
        if (step >= 2) {
            if (withCode) {
                codeBox(c, s.code(), 900, top + 20, W - 900 - 120, imgH - 40, 44, CYAN, Integer.MAX_VALUE);
            } else if (s.callouts() != null) {
                int ly = top + 70;
                for (int i = 0; i < Math.min(4, s.callouts().size()); i++) {
                    Callout co = s.callouts().get(i);
                    Color col = i % 2 == 0 ? GREEN : YELLOW;
                    int tx = mirror ? 140 : 140 + imgW + 160;
                    if (mirror) c.curveArrow(tx + 520, ly - 14, imgX + 60, top + imgH / 3 + i * 70, col);
                    else c.curveArrow(tx - 20, ly - 14, 140 + imgW - 60, top + imgH / 3 + i * 70, col);
                    c.text(co.label(), tx, ly, mediumFont, 60, col, true);
                    if (co.detail() != null && !co.detail().isBlank()) {
                        ly += 20;
                        ly = paragraph(c, co.detail(), tx, ly, mirror ? 560 : W - tx - 120, 46, 2, WHITE, bodyFont) - 10;
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
        if (variant(s) == 1 && n >= 2) { exampleGrid(c, items, n, y, step); return; }
        int rowH = Math.min(150, (H - 100 - y) / n - 18);
        int block = n * (rowH + 18) - 18;
        y = y + Math.max(0, (H - 90 - y - block) / 2); // centre the rows in the free space
        for (int i = 0; i < n; i++) {
            if (step < i + 1) break;
            Item it = items.get(i);
            Color col = ACCENT[i % ACCENT.length];
            int ry = y + i * (rowH + 18);
            c.neonRect(140, ry, rowH, rowH, 16, col, 3.5f);
            icon(c, it.icon(), 140 + rowH / 2, ry + rowH / 2, rowH * 0.58f, col);
            c.neonRect(140 + rowH + 22, ry, 1060, rowH, 16, MAGENTA, 3f);
            String code = it.code() == null ? "" : it.code();
            if (looksLikeCode(code)) {
                int fs = 50;
                while (widthOf(code, monoFont, fs) > 1000 && fs > 26) fs -= 2;
                syntax(c, code, 140 + rowH + 52, ry + rowH / 2 + fs / 3, fs);
            } else { // a fact or name, not code: normal font, no syntax colours
                int fs = 50;
                while (widthOf(code, titleFont, fs) > 1000 && fs > 28) fs -= 2;
                c.text(code, 140 + rowH + 52, ry + rowH / 2 + fs / 3, titleFont, fs, col, true);
            }
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

    /** Alternative layout: big cards in a row/grid (icon on top, value, label), like a storyboard strip. */
    private void exampleGrid(Canvas c, List<Item> items, int n, int y, int step) {
        int cols = n <= 4 ? n : 3, rows = (n + cols - 1) / cols;
        int gap = 36, cw = (W - 240 - gap * (cols - 1)) / cols;
        int ch = Math.min(560, (H - 110 - y - gap * (rows - 1)) / rows);
        int top = y + Math.max(0, (H - 100 - y - (rows * ch + (rows - 1) * gap)) / 2);
        for (int i = 0; i < n; i++) {
            if (step < i + 1) break;
            Item it = items.get(i);
            Color col = ACCENT[i % ACCENT.length];
            int x = 120 + (i % cols) * (cw + gap), yy = top + (i / cols) * (ch + gap);
            c.panel(x, yy, cw, ch, 26, col);
            icon(c, it.icon(), x + cw / 2, yy + (int) (ch * 0.30), Math.min(cw, ch) * 0.32f, col);
            String code = nz(it.code());
            if (looksLikeCode(code)) {
                int fs = 40;
                while (widthOf(code, monoFont, fs) > cw - 40 && fs > 20) fs -= 2;
                syntax(c, code, x + (cw - widthOf(code, monoFont, fs)) / 2f, yy + (int) (ch * 0.62), fs);
            } else {
                centered(c, code, x + cw / 2, yy + (int) (ch * 0.62), titleFont, 52, col, cw - 40);
            }
            List<String> lab = wrap(nz(it.label()), mediumFont, 40, cw - 50);
            int ty = yy + (int) (ch * 0.62) + 64;
            for (String l : lab.subList(0, Math.min(2, lab.size()))) {
                centered(c, l, x + cw / 2, ty, mediumFont, 40, WHITE, cw - 40);
                ty += 46;
            }
        }
    }

    /**
     * Ordered diagram (request flow, pipeline, process): node k appears with spoken sentence k, joined by
     * arrows in the same order the narrator explains them. 1 row up to 4 nodes, 2 rows (snake) for 5-6.
     */
    private void flow(Canvas c, Slide s, int step) {
        int y = 215;
        if (s.text() != null && !s.text().isBlank()) y = paragraph(c, s.text(), 120, 235, W - 240, 48, 2, WHITE, bodyFont) + 20;
        List<Item> nodes = s.items() == null ? List.of() : s.items();
        int n = Math.min(6, nodes.size());
        if (n == 0) return;
        int perRow = n <= 4 ? n : 3, rows = (n + perRow - 1) / perRow;
        int gap = 110, cw = Math.min(380, (W - 220 - gap * (perRow - 1)) / perRow);
        int ch = rows == 1 ? 440 : 300, rowGap = 90;
        int totalH = rows * ch + (rows - 1) * rowGap;
        int top = y + Math.max(10, (H - 80 - y - totalH) / 2);
        int rowW = perRow * cw + (perRow - 1) * gap;
        for (int i = 0; i < Math.min(n, step); i++) {
            Item it = nodes.get(i);
            int row = i / perRow, col = i % perRow;
            int x = (W - rowW) / 2 + col * (cw + gap), yy = top + row * (ch + rowGap);
            Color col2 = ACCENT[i % ACCENT.length];
            c.panel(x, yy, cw, ch, 26, col2);
            c.neonCircle(x + 38, yy + 38, 26, col2, 3f);
            centered(c, String.valueOf(i + 1), x + 38, yy + 50, titleFont, 34, WHITE, 40);
            icon(c, it.icon(), x + cw / 2, yy + (int) (ch * 0.30), Math.min(cw, ch) * 0.30f, col2);
            int ts = 46;
            while (ts > 26 && widthOf(nz(it.label()), titleFont, ts) > cw - 36) ts -= 2;
            centered(c, nz(it.label()), x + cw / 2, yy + (int) (ch * 0.60), titleFont, ts, col2, cw - 30);
            List<String> ws = wrap(nz(it.text()), bodyFont, 32, cw - 44);
            int ty = yy + (int) (ch * 0.60) + 46;
            for (String l : ws.subList(0, Math.min(rows == 1 ? 4 : 3, ws.size()))) {
                centered(c, l, x + cw / 2, ty, bodyFont, 32, WHITE, cw - 30);
                ty += 38;
            }
            if (i > 0) {
                if (col > 0) { // arrow from the previous card in the same row
                    c.arrow(x - gap + 14, yy + ch / 2, x - 14, yy + ch / 2, WHITE, 5f);
                } else { // snake: down from the end of the previous row to the start of this one
                    int px = (W - rowW) / 2 + (perRow - 1) * (cw + gap) + cw / 2, py = yy - rowGap - ch + ch;
                    int midY = yy - rowGap / 2;
                    c.line(px, py, px, midY, WHITE, 5f);
                    c.line(px, midY, x + cw / 2, midY, WHITE, 5f);
                    c.arrow(x + cw / 2, midY, x + cw / 2, yy - 10, WHITE, 5f);
                }
            }
        }
    }

    /** Advantages vs limits (or "use it" vs "avoid it"): items with ok=true go left, ok=false right, revealed in spoken order. */
    private void compare(Canvas c, Slide s, int step) {
        int y = 215;
        if (s.text() != null && !s.text().isBlank()) y = paragraph(c, s.text(), 120, 235, W - 240, 48, 2, WHITE, bodyFont) + 20;
        List<String> cols = s.columns() == null ? List.of() : s.columns();
        String lt = cols.size() > 0 && !cols.get(0).isBlank() ? cols.get(0) : "Advantages";
        String rt = cols.size() > 1 && !cols.get(1).isBlank() ? cols.get(1) : "Trade-offs";
        List<Item> items = s.items() == null ? List.of() : s.items();
        int pw = (W - 300) / 2, ph = H - 90 - y, lx = 120, rx = lx + pw + 60;
        c.panel(lx, y, pw, ph, 26, GREEN);
        c.panel(rx, y, pw, ph, 26, ORANGE);
        centered(c, lt, lx + pw / 2, y + 74, titleFont, 56, GREEN, pw - 60);
        centered(c, rt, rx + pw / 2, y + 74, titleFont, 56, ORANGE, pw - 60);
        c.line(lx + 40, y + 100, lx + pw - 40, y + 100, new Color(GREEN.getRed(), GREEN.getGreen(), GREEN.getBlue(), 120), 2f);
        c.line(rx + 40, y + 100, rx + pw - 40, y + 100, new Color(ORANGE.getRed(), ORANGE.getGreen(), ORANGE.getBlue(), 120), 2f);
        int leftY = y + 150, rightY = y + 150, shown = Math.min(Math.min(8, items.size()), step);
        int rowH = Math.max(90, Math.min(130, (ph - 150) / 4));
        for (int i = 0; i < shown; i++) {
            Item it = items.get(i);
            boolean left = it.ok() == null || it.ok();
            int x = left ? lx : rx, cy = left ? leftY : rightY;
            Color col = left ? GREEN : ORANGE;
            c.neonCircle(x + 56, cy + 26, 24, col, 3.5f);
            if (left) c.polyline(new int[]{x + 45, x + 54, x + 69}, new int[]{cy + 27, cy + 36, cy + 16}, col, 5f);
            else { c.line(x + 46, cy + 16, x + 66, cy + 36, col, 5f); c.line(x + 66, cy + 16, x + 46, cy + 36, col, 5f); }
            List<String> ws = wrap(nz(it.text()), bodyFont, 40, pw - 170);
            int ty = cy + 38;
            for (String l : ws.subList(0, Math.min(2, ws.size()))) { c.text(l, x + 100, ty, bodyFont, 40, WHITE, false); ty += 46; }
            if (left) leftY += rowH; else rightY += rowH;
        }
    }

    private void checklist(Canvas c, Slide s, int step, int steps) {
        List<Item> items = s.items() == null ? List.of() : s.items();
        int n = Math.min(7, items.size());
        int shown = steps <= 1 ? n : (int) Math.ceil(n * (step + 1) / (double) steps);
        boolean twoCol = variant(s) == 1 && n >= 4;
        int perCol = twoCol ? (n + 1) / 2 : n;
        int y = 240, rowH = Math.min(twoCol ? 150 : 118, (H - 110 - y) / Math.max(1, perCol));
        y += Math.max(0, (H - 100 - y - perCol * rowH) / 2);
        int colW = twoCol ? (W - 300) / 2 : W - 400;
        for (int i = 0; i < shown; i++) {
            Item it = items.get(i);
            boolean ok = it.ok() == null || it.ok();
            Color col = ok ? GREEN : RED;
            int ox = twoCol && i >= perCol ? colW + 120 : 0;
            int cy = y + (twoCol ? i % perCol : i) * rowH + rowH / 2;
            if (twoCol) c.panel(130 + ox, cy - rowH / 2 + 8, colW, rowH - 16, 20, col);
            c.neonCircle(185 + ox, cy, 30, col, 4f);
            if (ok) c.polyline(new int[]{168 + ox, 181 + ox, 204 + ox}, new int[]{cy + 1, cy + 14, cy - 12}, col, 6f);
            else { c.line(172 + ox, cy - 13, 198 + ox, cy + 13, col, 6f); c.line(198 + ox, cy - 13, 172 + ox, cy + 13, col, 6f); }
            List<String> ws = wrap(nz(it.text()), bodyFont, twoCol ? 44 : 52, colW - (twoCol ? 160 : 0));
            int nl = Math.min(2, ws.size());
            int fsz = twoCol ? 44 : 52;
            int ty = cy + fsz / 3 - (nl - 1) * (fsz / 2 + 2);
            for (String l : ws.subList(0, nl)) {
                c.text(l, 250 + ox, ty, bodyFont, fsz, WHITE, false);
                ty += fsz + 4;
            }
        }
    }

    private void summary(Canvas c, Slide s, int step) {
        if (variant(s) == 1 && s.mappings() != null && s.mappings().size() >= 3) { summaryHub(c, s, step); return; }
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

    /** Mind-map recap (like the storyboard reference): statement in the centre, ideas around it. */
    private void summaryHub(Canvas c, Slide s, int step) {
        int cx = W / 2, cy = 610, r = 150;
        List<Mapping> ms = s.mappings();
        int n = Math.min(6, ms.size());
        if (step >= 1) {
            for (int i = 0; i < n; i++) {
                double a = -Math.PI / 2 + i * 2 * Math.PI / n;
                int bx = (int) (cx + Math.cos(a) * 560), by = (int) (cy + Math.sin(a) * 300);
                Color col = ACCENT[i % ACCENT.length];
                c.line((int) (cx + Math.cos(a) * (r + 8)), (int) (cy + Math.sin(a) * (r + 8)), bx, by, col, 3f);
                c.panel(bx - 220, by - 62, 440, 124, 24, col);
                centered(c, ms.get(i).left(), bx, by - 6, titleFont, 42, col, 400);
                centered(c, ms.get(i).right(), bx, by + 40, bodyFont, 32, WHITE, 400);
            }
        }
        c.neonCircle(cx, cy, r, YELLOW, 6f);
        List<String> st = wrap(nz(s.statement()), titleFont, 40, 2 * r - 50);
        int ty = cy - (Math.min(4, st.size()) - 1) * 23 + 14;
        for (String l : st.subList(0, Math.min(4, st.size()))) { centered(c, l, cx, ty, titleFont, 40, YELLOW, 2 * r - 40); ty += 46; }
        if (step >= 2 && s.formulaResult() != null && !s.formulaResult().isBlank()) {
            centered(c, s.formulaResult(), cx, H - 70, mediumFont, 44, GREEN, W - 300);
        }
    }

    // ---------------------------------------------------------------- building blocks

    private void isoBox(Canvas c, int x, int y, int w, int h, String label, String value) {
        int depth = (int) (w * 0.16);
        int fx = x, fy = y + depth, fw = w - depth, fh = h - depth;
        Color edge = CYAN;
        // top + side faces
        c.poly(new int[]{fx, fx + depth, fx + fw + depth, fx + fw}, new int[]{fy, fy - depth, fy - depth, fy}, edge, 3.5f, new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 45));
        c.poly(new int[]{fx + fw, fx + fw + depth, fx + fw + depth, fx + fw}, new int[]{fy, fy - depth, fy - depth + fh, fy + fh}, edge, 3.5f, new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 70));
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

    private void codeBox(Canvas c, String code, int x, int y, int w, int h, int size, Color border, int maxLines, String title) {
        c.panel(x, y, w, h, 22, border);
        int bar = 56;
        c.dot(x + 32, y + bar / 2, 8, RED); c.dot(x + 56, y + bar / 2, 8, YELLOW); c.dot(x + 80, y + bar / 2, 8, GREEN);
        c.text(title, x + 110, y + bar / 2 + 9, mediumFont, 26, WHITE, false);
        c.line(x + 16, y + bar, x + w - 16, y + bar, new Color(border.getRed(), border.getGreen(), border.getBlue(), 110), 1.5f);
        List<String> ls = lines(code);
        int fs = size;
        while (fs > 22 && (maxLineWidth(ls, fs) > w - 80 || ls.size() * fs * 1.4 > h - bar - 30)) fs -= 2;
        int ly = y + bar + Math.max(20, (h - bar - (int) (ls.size() * fs * 1.4)) / 2) + fs;
        for (int i = 0; i < Math.min(maxLines, ls.size()); i++) {
            syntax(c, ls.get(i), x + 36, ly, fs);
            ly += (int) (fs * 1.4);
        }
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
            background();
        }

        /** Same seed for every build step, so textures never flicker between reveals. */
        private void background() {
            java.util.Random rnd = new java.util.Random(4242);
            switch (style.stroke()) {
                case "sketch" -> {
                    g.setColor(new Color(0xF6C9A6)); g.fillRect(0, 0, W, H);
                    speckle(rnd, new Color(120, 90, 60), 9000, 10);
                }
                case "card" -> {
                    g.setPaint(new java.awt.GradientPaint(0, 0, new Color(0xE8F1FF), W, H, new Color(0xFFF3E4)));
                    g.fillRect(0, 0, W, H);
                }
                case "chalk" -> {
                    g.setPaint(new java.awt.RadialGradientPaint(W / 2f, H / 2f, W * 0.75f, new float[]{0f, 1f},
                            new Color[]{new Color(0x2C4A3D), new Color(0x16271F)}));
                    g.fillRect(0, 0, W, H);
                    speckle(rnd, new Color(230, 240, 230), 14000, 14);
                    g.setColor(new Color(255, 255, 255, 5));
                    for (int i = 0; i < 14; i++) { // faint eraser smudges
                        g.fillOval(rnd.nextInt(W), rnd.nextInt(H), 200 + rnd.nextInt(400), 60 + rnd.nextInt(120));
                    }
                }
                case "reference" -> {
                    g.setPaint(new java.awt.GradientPaint(0, 0, new Color(0x102B38), W, H, new Color(0x091923)));
                    g.fillRect(0, 0, W, H);
                    // Restrained technical grid: enough structure to feel engineered, never competing with labels.
                    g.setStroke(new BasicStroke(1f));
                    for (int x = 0; x < W; x += 48) { g.setColor(new Color(100, 190, 205, x % 240 == 0 ? 18 : 7)); g.drawLine(x, 0, x, H); }
                    for (int y = 0; y < H; y += 48) { g.setColor(new Color(100, 190, 205, y % 240 == 0 ? 18 : 7)); g.drawLine(0, y, W, y); }
                }
                case "blueprint" -> {
                    g.setColor(new Color(0x0D3B73)); g.fillRect(0, 0, W, H);
                    g.setStroke(new BasicStroke(1f));
                    for (int x = 0; x < W; x += 40) { g.setColor(new Color(255, 255, 255, x % 200 == 0 ? 40 : 16)); g.drawLine(x, 0, x, H); }
                    for (int y = 0; y < H; y += 40) { g.setColor(new Color(255, 255, 255, y % 200 == 0 ? 40 : 16)); g.drawLine(0, y, W, y); }
                }
                case "pop" -> {
                    g.setPaint(new java.awt.GradientPaint(0, 0, new Color(0xFFE6F2), W, H, new Color(0xDDF1FF)));
                    g.fillRect(0, 0, W, H);
                    g.setColor(new Color(255, 120, 180, 40));
                    for (int y = 0; y < 260; y += 22) for (int x = 0; x < 360; x += 22) { // halftone corner
                        int r = Math.max(1, 9 - (x + y) / 60);
                        g.fillOval(W - 40 - x, H - 40 - y, r, r);
                        g.fillOval(30 + x, 30 + y, r, r);
                    }
                }
                default -> { g.setColor(new Color(4, 5, 10)); g.fillRect(0, 0, W, H); }
            }
        }

        private void speckle(java.util.Random rnd, Color col, int count, int alpha) {
            g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), alpha));
            for (int i = 0; i < count; i++) g.fillRect(rnd.nextInt(W), rnd.nextInt(H), 1 + rnd.nextInt(2), 1 + rnd.nextInt(2));
        }

        void stroke(Shape s, Color col, float width) {
            switch (style.stroke()) {
                case "sketch", "chalk" -> { // hand-drawn: two slightly different wobbly passes
                    Color ink = style.stroke().equals("sketch") ? new Color(0x2A2A2A) : col;
                    float w1 = style.stroke().equals("sketch") ? Math.max(2f, width * 0.7f) : Math.max(2.4f, width * 0.85f);
                    g.setStroke(new BasicStroke(w1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(ink);
                    g.draw(wobble(s, 1));
                    g.setColor(new Color(ink.getRed(), ink.getGreen(), ink.getBlue(), style.stroke().equals("chalk") ? 120 : 150));
                    g.setStroke(new BasicStroke(w1 * 0.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(wobble(s, 2));
                }
                case "pop" -> { // anime / comic: thick black outline, colour inside
                    g.setColor(new Color(0x1A1A2E));
                    g.setStroke(new BasicStroke(width * 1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(s);
                    g.setColor(col);
                    g.setStroke(new BasicStroke(Math.max(1.5f, width * 0.7f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(s);
                }
                case "card" -> {
                    g.setColor(col);
                    g.setStroke(new BasicStroke(Math.max(2f, width * 0.75f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(s);
                }
                default -> { // neon + blueprint
                    g.setColor(col);
                    g.setStroke(new BasicStroke(style.stroke().equals("blueprint") ? Math.max(1.6f, width * 0.6f) : width,
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(s);
                    gl.setColor(col);
                    gl.setStroke(new BasicStroke(width * 2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    gl.draw(s);
                }
            }
        }

        /** Hand-drawn line: resample the outline and jitter it a little (seeded by the shape). */
        private Shape wobble(Shape s, int pass) {
            java.awt.geom.Rectangle2D b = s.getBounds2D();
            long seed = Double.doubleToLongBits(b.getX() * 31 + b.getY() * 17 + b.getWidth() * 7 + b.getHeight()) + pass * 977L;
            java.util.Random rnd = new java.util.Random(seed);
            Path2D out = new Path2D.Double();
            double[] co = new double[6];
            double lx = 0, ly = 0, sx = 0, sy = 0;
            double amp = style.stroke().equals("chalk") ? 1.8 : 1.4;
            for (java.awt.geom.PathIterator it = s.getPathIterator(null, 1.5); !it.isDone(); it.next()) {
                int t = it.currentSegment(co);
                if (t == java.awt.geom.PathIterator.SEG_MOVETO) {
                    lx = sx = co[0] + rnd.nextGaussian() * amp; ly = sy = co[1] + rnd.nextGaussian() * amp;
                    out.moveTo(lx, ly);
                } else if (t == java.awt.geom.PathIterator.SEG_LINETO || t == java.awt.geom.PathIterator.SEG_CLOSE) {
                    double tx = t == java.awt.geom.PathIterator.SEG_CLOSE ? sx : co[0], ty = t == java.awt.geom.PathIterator.SEG_CLOSE ? sy : co[1];
                    double len = Math.hypot(tx - lx, ty - ly);
                    int steps = Math.max(1, (int) (len / 26));
                    for (int k = 1; k <= steps; k++) {
                        double f = k / (double) steps;
                        out.lineTo(lx + (tx - lx) * f + rnd.nextGaussian() * amp * 0.6, ly + (ty - ly) * f + rnd.nextGaussian() * amp * 0.6);
                    }
                    lx = tx; ly = ty;
                }
            }
            return out;
        }

        /** Fill used inside cards: dark tint on dark styles, light tint (paper / white card) on light ones. */
        private Color fillFor(Color col, boolean big) {
            switch (style.stroke()) {
                case "sketch": return big ? new Color(0xFBF6E6) : new Color(mix(col.getRed(), 255, 0.80), mix(col.getGreen(), 255, 0.80), mix(col.getBlue(), 255, 0.80));
                case "card": case "pop": return big ? new Color(255, 255, 255, 200) : new Color(mix(col.getRed(), 255, 0.88), mix(col.getGreen(), 255, 0.88), mix(col.getBlue(), 255, 0.88));
                case "chalk": return new Color(255, 255, 255, big ? 0 : 10);
                case "blueprint": return new Color(255, 255, 255, big ? 0 : 14);
                case "reference": return big ? new Color(0x102B38) : new Color(14, 38, 49, 235);
                default: return big ? new Color(4, 5, 10) : new Color(col.getRed() / 28 + 3, col.getGreen() / 28 + 3, col.getBlue() / 28 + 5);
            }
        }

        private int mix(int a, int b, double t) { return (int) Math.round(a * (1 - t) + b * t); }

        private void shadow(RoundRectangle2D rr) {
            if (!style.stroke().equals("card") && !style.stroke().equals("pop")) return;
            g.setColor(new Color(20, 30, 60, style.stroke().equals("pop") ? 60 : 28));
            int off = style.stroke().equals("pop") ? 8 : 6;
            g.fill(new RoundRectangle2D.Double(rr.getX() + off, rr.getY() + off, rr.getWidth(), rr.getHeight(), rr.getArcWidth(), rr.getArcHeight()));
        }

        void panel(int x, int y, int w, int h, int r, Color col) {
            RoundRectangle2D rr = new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2);
            if (!style.stroke().equals("neon")) {
                shadow(rr);
                g.setColor(fillFor(col, false));
                g.fill(rr);
                stroke(rr, col, 3.2f);
                return;
            }
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
            if (!big) shadow(rr);
            g.setColor(fillFor(col, big));
            g.fill(rr);
            stroke(rr, col, width);
            if (!style.stroke().equals("neon")) return;
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
            if (glow && style.glow()) {
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
            int[] corners = {scaled.getRGB(2, 2), scaled.getRGB(w - 3, 2), scaled.getRGB(2, h - 3), scaled.getRGB(w - 3, h - 3)};
            int keyR = 0, keyG = 0, keyB = 0;
            for (int cc : corners) { keyR += (cc >> 16) & 255; keyG += (cc >> 8) & 255; keyB += cc & 255; }
            keyR /= 4; keyG /= 4; keyB /= 4;
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    int p = scaled.getRGB(xx, yy);
                    int r = (p >> 16) & 255, gg = (p >> 8) & 255, b = p & 255;
                    // lift-the-blacks: near-black noise becomes fully transparent
                    // key out the image's own background colour (sampled from its corners): works for
                    // black, white, cream or pastel backgrounds instead of washing light drawings out
                    int dist = Math.abs(r - keyR) + Math.abs(gg - keyG) + Math.abs(b - keyB);
                    int alpha = Math.max(0, Math.min(255, (dist - 24) * 255 / 60));
                    // soft vignette so the illustration never shows a hard rectangular edge
                    double ex = Math.min(xx, w - 1 - xx) / (w * 0.08), ey = Math.min(yy, h - 1 - yy) / (h * 0.08);
                    alpha = (int) (alpha * Math.min(1.0, Math.min(ex, ey)));
                    mask.setRGB(xx, yy, (alpha << 24) | (r << 16) | (gg << 8) | b);
                }
            }
            g.setComposite(AlphaComposite.SrcOver);
            g.drawImage(mask, x, y, null);
            if (style.glow()) {
                gl.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
                gl.drawImage(mask, x, y, null);
                gl.setComposite(AlphaComposite.SrcOver);
            }
        }

        void dispose() {
            g.dispose();
            gl.dispose();
        }
    }
}
