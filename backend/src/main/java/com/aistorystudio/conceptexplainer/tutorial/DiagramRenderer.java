package com.aistorystudio.conceptexplainer.tutorial;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Arc2D;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Tech tutorial" look: a calm dark-teal canvas on which a running example is built up as a
 * diagram, one element per narrated sentence - actors (user / agent), labelled buttons, brand-like
 * tiles, preference cards, curved dashed connectors, chat / terminal / browser / code windows,
 * quotes and dialog arrows. No AI artwork: every pixel is drawn here, so text is always exact.
 *
 * <p>render() returns one frame per narration sentence (frame k = everything with at <= k); the
 * video assembly cross-fades frame k in when sentence k starts, which is the reference's
 * "element fades in while the narrator mentions it" motion.</p>
 *
 * Pure Java2D, no Spring / Jackson, so it can be tested standalone.
 */
public final class DiagramRenderer {

    /** Landscape lesson canvas; the 60-second short uses a 1080x1920 portrait canvas (see setCanvas). */
    public static final int LW = 1920, LH = 1080;
    private int W = LW, H = LH;
    private boolean portrait;

    // ------------------------------------------------------------------ model

    /** One thing on screen. x/y = centre in % of the frame, w/h optional size in %. */
    public record Element(String id, String type, String text, String title, String icon, String color,
                          List<String> lines, String extra, String from, String to, String style,
                          double x, double y, double w, double h, int at, int dimAt, List<Integer> highlight) {
        public Element withAt(int a) {
            return new Element(id, type, text, title, icon, color, lines, extra, from, to, style, x, y, w, h, a, dimAt, highlight);
        }
    }

    /** One scene: optional heading + elements; frames = sentences. */
    public record Scene(String heading, List<Element> elements, int sentences, String label) {
        public Scene(String heading, List<Element> elements, int sentences) { this(heading, elements, sentences, null); }
    }

    public static final List<String> TYPES = List.of("actor", "heading", "button", "tile", "card", "note", "quote", "pill",
            "label", "chat", "terminal", "browser", "code", "container", "message", "arrow", "icon",
            // 60-second short (vertical reel) vocabulary
            "metric", "value", "chips", "bars", "table", "tabs", "node", "plot", "titlecard", "outro",
            // real-world breakdown (3D blueprint) vocabulary
            "journey", "callout", "db", "server", "phone", "rows", "particles", "cables", "sql", "photo");

    public static final List<String> ICONS = List.of("user", "agent", "robot", "plane", "hotel", "server", "database", "cloud",
            "brain", "gear", "terminal", "browser", "doc", "lock", "chart", "mail", "git", "code", "search", "bolt", "cart",
            "folder", "key", "globe", "phone", "check", "warning", "api", "sparkle", "money", "clock", "book", "cpu", "network",
            "chat", "shield", "list", "cube", "padlock");

    // ------------------------------------------------------------------ palette (measured from the reference)

    // Active theme colours (set per render by applyTheme; the defaults are the reference look).
    Color BG, BG_GLOW, TEXT, MUTED, PINK, VIOLET, CYAN, TEAL_BTN, TEAL_BTN_EDGE, BTN_TEXT, GREEN, GREEN_TXT, USER_BLUE, ARROW,
            TAG_BG, LAYER_TXT, HEAD_A, HEAD_B;
    private boolean light, glow, grid, blueprint;
    private double bloomAmount = 0.55;
    /** Typewriter state while rendering reveal frames: element id -> visible characters (absent = all). */
    private Map<String, Integer> typing = Map.of();
    private float caretX = -1, caretY = -1;
    public static final double TYPE_CPS = 45;   // characters per second (the reference types fast)
    /** Highlight colour for key words, active steps, locks (yellow in the 3D blueprint look). */
    Color HL = new Color(0xFFC83D);
    private String themeId = "reference";

    /**
     * The same diagram language in every visual style: only colours, fonts, background and glow change.
     * reference = the measured reference video; neon = black + glowing lines; sketchnote / chalkboard = hand
     * lettering; storyboard = clean light cards; blueprint = navy grid; anime = bright paper + comic headings.
     */
    public static final List<String> THEMES = List.of("reference", "neon", "sketchnote", "storyboard", "chalkboard", "blueprint", "anime", "reel", "blueprint3d");

    /** Portrait 1080x1920 (60-second short) or landscape 1920x1080 (lessons). */
    public void setCanvas(boolean vertical) {
        portrait = vertical;
        W = vertical ? 1080 : LW;
        H = vertical ? 1920 : LH;
    }

    /** Bottom of the area diagrams may use (the short keeps the lower part free for captions). */
    private double contentBottom() { return portrait && !blueprint ? H * 0.74 : H - 40; }   // shorts keep room for captions

    private void applyTheme(String id) {
        String t = id == null ? "reference" : id.trim().toLowerCase(Locale.ROOT);
        if (!THEMES.contains(t)) t = "reference";
        themeId = t;
        light = false; glow = false; grid = false; blueprint = false; bloomAmount = 0.55;
        sans = baseSans; sansBold = baseBold; sansItalic = baseItalic; headFont = baseBold; mono = baseMono;
        HL = new Color(0xFFC83D);
        switch (t) {
            case "neon" -> {
                set(0x05070D, 0x0B1020, 0xF0F4FA, 0x96A0B4, 0xFF3CDC, 0xAA6EFF, 0x00DCFF, 0x04161D, 0x00DCFF, 0xF0F4FA,
                        0x46FF78, 0x7DFF9E, 0x00DCFF, 0x46FF78, 0x06141A, 0xFF8BEA, 0x00DCFF, 0xFF3CDC);
                glow = true;
            }
            case "sketchnote" -> {
                set(0xFAF7EE, 0xFFFDF6, 0x2A2A2A, 0x6B6B6B, 0xD9667F, 0x8E6BC7, 0x2E8FB0, 0xDDEFF3, 0x2E8FB0, 0x1E3A44,
                        0x4F9D5B, 0x3D7F47, 0x2E8FB0, 0x555555, 0xFFFFFF, 0x6A4FA8, 0xE07A5F, 0x8E6BC7);
                light = true;
                if (hand != null) { sans = hand; sansItalic = hand; }
                if (handBold != null) { sansBold = handBold; headFont = handBold; }
            }
            case "storyboard" -> {
                set(0xF6F9FD, 0xFFFFFF, 0x1D2B45, 0x5C6B80, 0xD63384, 0x7B4FD6, 0x2F7AE5, 0x2F7AE5, 0x2569C9, 0xFFFFFF,
                        0x2FA84F, 0x23863E, 0x2F7AE5, 0x2FA84F, 0xFFFFFF, 0x5B3BB8, 0x2F7AE5, 0xD63384);
                light = true;
            }
            case "chalkboard" -> {
                set(0x18352B, 0x214437, 0xF2F2EC, 0xB7C4BC, 0xF59AC3, 0xC4A8F0, 0x8FD3E8, 0x1F4537, 0xF2F2EC, 0xF2F2EC,
                        0xA5E39A, 0xC2EDB9, 0x8FD3E8, 0xF2F2EC, 0x1F4537, 0xF59AC3, 0xF7E07A, 0x8FD3E8);
                if (hand != null) { sans = hand; sansItalic = hand; }
                if (handBold != null) { sansBold = handBold; headFont = handBold; }
            }
            case "blueprint" -> {
                set(0x0B2A55, 0x12386C, 0xFFFFFF, 0xB8CDE8, 0xFFB3D9, 0xD0B8FF, 0x9FD8FF, 0x0F3566, 0x9FD8FF, 0xFFFFFF,
                        0xA8F0C0, 0xC8F7D6, 0x9FD8FF, 0xFFFFFF, 0x0F3566, 0xFFB3D9, 0xFFFFFF, 0x9FD8FF);
                grid = true;
            }
            case "blueprint3d" -> {   // real-world breakdown: deep navy 3D world, glowing parts, yellow highlights
                set(0x0B1C3A, 0x14366C, 0xEAF1FF, 0x8DA4C8, 0xFF5A6A, 0x9DB7FF, 0x7FB8FF, 0x0D2246, 0x7F9FD6, 0xEAF1FF,
                        0x5BE38C, 0x8EF0B0, 0x7FB8FF, 0xFFC83D, 0x0C1F40, 0xFFC83D, 0xFFC83D, 0xFFE08A);
                blueprint = true; glow = true; bloomAmount = 0.32;
                if (grotesk != null) { sans = groteskMedium != null ? groteskMedium : grotesk; sansBold = grotesk; headFont = grotesk; sansItalic = sans; }
                if (jbMono != null) mono = jbMono;
            }
            case "reel" -> {   // both reference reels: near-black, monochrome UI cards, green / cyan / red accents
                set(0x0B0B0C, 0x131417, 0xF4F4F5, 0x8B8B92, 0xF87171, 0xA78BFA, 0x38BDF8, 0x18181B, 0x3F3F46, 0xF4F4F5,
                        0x4ADE80, 0x86EFAC, 0x38BDF8, 0x71717A, 0x18181B, 0xE4E4E7, 0xF4F4F5, 0xD4D4D8);
            }
            case "anime" -> {
                set(0xFFFDF7, 0xFFF4E0, 0x1A1A2E, 0x55556A, 0xFF4FA3, 0x8A5CFF, 0x1C9BEF, 0x1C9BEF, 0x1A1A2E, 0xFFFFFF,
                        0x22B573, 0x168A56, 0x1C9BEF, 0x1A1A2E, 0xFFFFFF, 0x8A5CFF, 0xFF4FA3, 0xFF7A1A);
                light = true;
                if (pop != null) headFont = pop;
            }
            default -> set(0x08202F, 0x0D2A3B, 0xE9EEF3, 0x8DA2B3, 0xE27FD4, 0xA77BF0, 0x4FB4E6, 0x22647F, 0x3A8AA9, 0xE9EEF3,
                    0x74C77A, 0x93D68C, 0x3AA0DE, 0x6FC36F, 0x0C2E3B, 0xE7A3E4, 0xF19AD2, 0xA77BF0);
        }
    }

    private void set(int bg, int bgGlow, int text, int muted, int pink, int violet, int cyan, int btn, int btnEdge, int btnText,
                     int green, int greenTxt, int user, int arrow, int tagBg, int layerTxt, int headA, int headB) {
        BG = new Color(bg); BG_GLOW = new Color(bgGlow); TEXT = new Color(text); MUTED = new Color(muted);
        PINK = new Color(pink); VIOLET = new Color(violet); CYAN = new Color(cyan);
        TEAL_BTN = new Color(btn); TEAL_BTN_EDGE = new Color(btnEdge); BTN_TEXT = new Color(btnText);
        GREEN = new Color(green); GREEN_TXT = new Color(greenTxt); USER_BLUE = new Color(user); ARROW = new Color(arrow);
        TAG_BG = new Color(tagBg); LAYER_TXT = new Color(layerTxt); HEAD_A = new Color(headA); HEAD_B = new Color(headB);
    }

    /** tile palettes: fill, icon, label. */
    static final Map<String, Color[]> TILES = new LinkedHashMap<>();
    static {
        TILES.put("teal", new Color[]{new Color(0x15A79D), new Color(0xF6D90E), new Color(0xF6D90E)});
        TILES.put("purple", new Color[]{new Color(0x3A2343), new Color(0x9C6BD9), new Color(0xA987DB)});
        TILES.put("blue", new Color[]{new Color(0x0C79C3), Color.WHITE, Color.WHITE});
        TILES.put("orange", new Color[]{new Color(0xE8762D), Color.WHITE, Color.WHITE});
        TILES.put("green", new Color[]{new Color(0x2E8B57), new Color(0xE8F5E9), new Color(0xE8F5E9)});
        TILES.put("red", new Color[]{new Color(0xC0392B), Color.WHITE, Color.WHITE});
        TILES.put("white", new Color[]{new Color(0xF4F6F8), new Color(0x1D3557), new Color(0x1D3557)});
        TILES.put("dark", new Color[]{new Color(0x1B2633), new Color(0x7FD1F0), new Color(0xCFE6F2)});
        TILES.put("yellow", new Color[]{new Color(0xF2C230), new Color(0x2A2A2A), new Color(0x2A2A2A)});
    }

    Color accent(String name, Color fallback) {
        if (name == null) return fallback;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "pink", "magenta" -> PINK;
            case "violet", "purple" -> VIOLET;
            case "cyan", "blue" -> CYAN;
            case "green" -> GREEN;
            case "teal" -> new Color(0x2FB3A6);
            case "orange" -> new Color(0xF0A060);
            case "yellow" -> new Color(0xF2D46B);
            case "red" -> new Color(0xF07070);
            case "white" -> TEXT;
            case "gray", "grey" -> MUTED;
            default -> fallback;
        };
    }

    // ------------------------------------------------------------------ fonts

    private Font sans, sansBold, sansItalic, headFont, mono;
    private final Font baseSans, baseBold, baseItalic, baseMono, hand, handBold, pop, grotesk, groteskMedium, jbMono;
    private final Map<Character.UnicodeScript, Font> scriptFonts = new LinkedHashMap<>();
    private static final Font SYMBOLS = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    public DiagramRenderer() {
        Font fallback = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
        baseSans = load("Lato-Regular.ttf", load("BarlowSemiCondensed-Regular.ttf", fallback));
        baseBold = load("Lato-Bold.ttf", load("BarlowSemiCondensed-SemiBold.ttf", fallback.deriveFont(Font.BOLD)));
        baseItalic = load("Lato-Italic.ttf", baseSans.deriveFont(Font.ITALIC));
        hand = load("Kalam-Regular.ttf", null);
        handBold = load("Kalam-Bold.ttf", null);
        pop = load("Bangers-Regular.ttf", null);
        baseMono = load("FiraMono-Medium.ttf", new Font(Font.MONOSPACED, Font.PLAIN, 10));
        mono = baseMono;
        grotesk = load("SpaceGrotesk-Bold.ttf", null);
        groteskMedium = load("SpaceGrotesk-Medium.ttf", null);
        jbMono = load("JetBrainsMono-Medium.ttf", null);
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
        applyTheme("reference");
    }

    private static Font load(String name, Font fallback) {
        try (InputStream in = DiagramRenderer.class.getResourceAsStream("/concept-fonts/" + name)) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception ignored) { }
        try {
            Path p = Path.of(System.getProperty("concept.fonts", "concept-fonts"), name);
            if (Files.isRegularFile(p)) return Font.createFont(Font.TRUETYPE_FONT, p.toFile());
        } catch (Exception ignored) { }
        return fallback;
    }

    // ------------------------------------------------------------------ public API

    /** One frame per sentence (at least one). Frame k shows every element with at <= k. */
    public synchronized List<BufferedImage> render(Scene scene) {
        return render(scene, "reference");
    }

    /** Same diagrams, drawn in the given visual style (see THEMES). */
    public synchronized List<BufferedImage> render(Scene scene, String styleId) {
        return render(scene, styleId, false);
    }

    /** vertical = 1080x1920 short-video canvas. */
    public synchronized List<BufferedImage> render(Scene scene, String styleId, boolean vertical) {
        setCanvas(vertical);
        applyTheme(styleId);
        int n = Math.max(1, scene.sentences());
        Map<String, Rectangle2D> bounds = layout(scene.elements());
        List<BufferedImage> out = new ArrayList<>();
        for (int k = 0; k < n; k++) out.add(frame(scene, bounds, k));
        return out;
    }

    // ------------------------------------------------------------------ layout

    /** Pixel bounds of every non-arrow element (needed before drawing so arrows can attach). */
    Map<String, Rectangle2D> layout(List<Element> els) {
        Map<String, Rectangle2D> b = new HashMap<>();
        int auto = 0;
        for (Element e : els) {
            if (e.type().equals("arrow")) continue;
            double[] sz = size(e);
            double cx = e.x() > 0 ? e.x() / 100.0 * W : 0, cy = e.y() > 0 ? e.y() / 100.0 * H : 0;
            if (e.x() <= 0 || e.y() <= 0) { // no position: simple left-to-right grid under the heading
                int col = auto % 4, row = auto / 4;
                cx = W * (0.16 + 0.225 * col);
                cy = H * (0.36 + 0.28 * row);
                auto++;
            }
            // inside the frame (0-100 %): keep it fully on screen as before. Beyond 100 %: the element lives
            // outside the first view and the camera pulls back to show it (overview shot, see camera()).
            double x = e.x() > 100 ? cx - sz[0] / 2 : clamp(cx - sz[0] / 2, 30, W - 30 - sz[0]);
            double y = e.y() > 100 ? cy - sz[1] / 2 : clamp(cy - sz[1] / 2, 30, (full(e) ? H - 30 : contentBottom()) - sz[1]);
            if (backdrop(e) && e.w() <= 0) { x = 0; y = 0; }     // full-bleed picture
            b.put(e.id(), new Rectangle2D.Double(x, y, sz[0], sz[1]));
        }
        resolveOverlaps(els, b);
        return b;
    }

    /**
     * LLM layouts sometimes stack two things on the same spot. Later elements are nudged down (or,
     * at the bottom edge, right) until they no longer cover an earlier one. Containers are skipped:
     * other elements are meant to sit inside them.
     */
    /** Background layers: never pushed around by the overlap fixer, never framed by the camera. */
    private static boolean backdrop(Element e) { return e.type().equals("photo") || e.type().equals("particles"); }

    private static boolean full(Element e) { return e.type().equals("titlecard") || e.type().equals("outro"); }

    private void resolveOverlaps(List<Element> els, Map<String, Rectangle2D> b) {
        List<Rectangle2D> placed = new ArrayList<>();
        for (Element e : els) {
            Rectangle2D r = b.get(e.id());
            if (r == null || e.type().equals("container") || backdrop(e)) continue;
            for (int guard = 0; guard < 40; guard++) {
                Rectangle2D hit = null;
                for (Rectangle2D p : placed) {
                    if (p.intersects(r.getX() + 4, r.getY() + 4, r.getWidth() - 8, r.getHeight() - 8)) { hit = p; break; }
                }
                if (hit == null) break;
                double ny = hit.getMaxY() + 18;
                double bottom = r.getMaxY() > H ? 1.6 * H : H - 30;   // world elements may extend below the first view
                if (ny + r.getHeight() <= bottom) r = new Rectangle2D.Double(r.getX(), ny, r.getWidth(), r.getHeight());
                else {
                    double nx = hit.getMaxX() + 24;
                    if (nx + r.getWidth() > (r.getMaxX() > W ? 1.6 * W : W - 30)) break; // nowhere to go: leave it
                    r = new Rectangle2D.Double(nx, r.getY(), r.getWidth(), r.getHeight());
                }
            }
            b.put(e.id(), r);
            placed.add(r);
        }
    }

    /** Natural size of an element in pixels (w/h in % override). */
    double[] size(Element e) {
        double w = e.w() > 0 ? e.w() / 100.0 * W : 0, h = e.h() > 0 ? e.h() / 100.0 * H : 0;
        String t = nz(e.text());
        switch (e.type()) {
            case "actor" -> {
                boolean user = "user".equals(icon(e, "agent"));
                double ic = user ? 82 : 170;
                double lw = t.isEmpty() ? 0 : widthOf(t, sans, user ? 30 : 56) + 18;
                return new double[]{w > 0 ? w : ic + lw, h > 0 ? h : ic};
            }
            case "heading" -> { return new double[]{w > 0 ? w : Math.min(W - 120, widthOf(t, headFont, 66) + 10), h > 0 ? h : 84}; }
            case "button" -> {
                double bw = w > 0 ? w : Math.max(260, Math.min(560, widthOf(t, sans, 28) + 70));
                return new double[]{bw, h > 0 ? h : 64};
            }
            case "tile" -> { double s = w > 0 ? w : 170; return new double[]{s, h > 0 ? h : s}; }
            case "card" -> {
                double cw = w > 0 ? w : 360;
                int rows = Math.max(1, e.lines() == null ? 0 : e.lines().size());
                return new double[]{cw, h > 0 ? h : 92 + rows * 40};
            }
            case "note" -> {
                double nw = w > 0 ? w : Math.min(560, Math.max(300, widthOf(t, sans, 28) / 2 + 80));
                int lines = wrap(t, sans, 28, (int) nw - 40).size();
                return new double[]{nw, h > 0 ? h : 34 + lines * 36};
            }
            case "quote" -> {
                double qw = w > 0 ? w : 640;
                int lines = wrap("\u201C" + t + "\u201D", sansItalic, 30, (int) qw).size();
                return new double[]{qw, h > 0 ? h : lines * 38 + 10};
            }
            case "pill" -> { return new double[]{w > 0 ? w : widthOf(t, sans, 24) + 44, h > 0 ? h : 48}; }
            case "label" -> {
                int sz = labelSize(e);
                double lw = w > 0 ? w : Math.min(900, widthOf(t, sans, sz) + 8);
                int lines = wrap(t, sans, sz, (int) lw).size();
                return new double[]{lw, h > 0 ? h : lines * sz * 1.25 + 6};
            }
            case "chat" -> { return new double[]{w > 0 ? w : 640, h > 0 ? h : 470}; }
            case "terminal" -> {
                int lines = 2 + lines(e.extra()).size();
                return new double[]{w > 0 ? w : 760, h > 0 ? h : Math.min(560, 70 + lines * 34)};
            }
            case "browser" -> { return new double[]{w > 0 ? w : 820, h > 0 ? h : 520}; }
            case "code" -> {
                int lines = Math.max(3, lines(e.extra()).size());
                return new double[]{w > 0 ? w : 760, h > 0 ? h : Math.min(820, 80 + lines * 34)};
            }
            case "container" -> { return new double[]{w > 0 ? w : 380, h > 0 ? h : 720}; }
            case "message" -> { return new double[]{w > 0 ? w : 560, h > 0 ? h : 70}; }
            case "icon" -> { double s = w > 0 ? w : 110; return new double[]{s, h > 0 ? h : s}; }
            case "metric" -> { return new double[]{w > 0 ? w : Math.max(360, widthOf(t, sansBold, 150) + 40), h > 0 ? h : 230}; }
            case "value" -> { return new double[]{w > 0 ? w : Math.max(260, widthOf(t, mono, 64) + 70), h > 0 ? h : 170}; }
            case "chips" -> {
                double cw = 0;
                for (String grp : listOrEmpty(e)) cw += chipGroupWidth(grp) + 40;
                return new double[]{w > 0 ? w : Math.max(200, cw - 40), h > 0 ? h : 120};
            }
            case "bars", "table" -> {
                int rows = Math.max(1, listOrEmpty(e).size());
                return new double[]{w > 0 ? w : Math.min(W - 80, 900), h > 0 ? h : 96 + rows * 58 + (e.type().equals("bars") ? 20 : 0)};
            }
            case "tabs" -> {
                double tw = 0;
                for (String l : listOrEmpty(e)) tw += widthOf(l, sans, 26) + 56 + 14;
                return new double[]{w > 0 ? w : Math.max(120, tw - 14), h > 0 ? h : 56};
            }
            case "node" -> { return new double[]{w > 0 ? w : Math.max(230, Math.max(widthOf(t, sansBold, 32), widthOf(nz(e.title()), mono, 20)) + 50), h > 0 ? h : 250}; }
            case "plot" -> { return new double[]{w > 0 ? w : (portrait ? 900 : 760), h > 0 ? h : (portrait ? 560 : 460)}; }
            case "titlecard" -> { return new double[]{W * 0.9, h > 0 ? h : H * (portrait ? 0.55 : 0.75)}; }
            case "journey" -> { return new double[]{w > 0 ? w : W - 120, h > 0 ? h : 80}; }
            case "callout" -> {
                double cw = w > 0 ? w : W - 120;
                String st = nz(e.style()).toLowerCase(Locale.ROOT);
                int size = st.contains("named") ? 64 : st.contains("stat") ? 60 : 50;
                int lines = richLines(t, sansBold, size, (int) cw - 60).size();
                double extra = listOrEmpty(e).size() * 58 + (nz(e.title()).isEmpty() ? 0 : 44);
                return new double[]{cw, h > 0 ? h : 56 + lines * size * 1.18 + extra};
            }
            case "db" -> { double d = w > 0 ? w : 260; return new double[]{d, h > 0 ? h : d * 1.25 + 70}; }
            case "server" -> { double d = w > 0 ? w : 200; return new double[]{d, h > 0 ? h : d * 1.7 + 50}; }
            case "phone" -> { double d = w > 0 ? w : (portrait ? 420 : 360); return new double[]{d, h > 0 ? h : d * 2.05}; }
            case "rows" -> { int n = Math.max(1, listOrEmpty(e).size()); return new double[]{w > 0 ? w : 640, h > 0 ? h : n * 84 + 10}; }
            case "particles" -> { return new double[]{w > 0 ? w : W - 80, h > 0 ? h : 520}; }
            case "sql" -> { int n = Math.max(1, lines(e.extra()).size()); return new double[]{w > 0 ? w : W - 160, h > 0 ? h : 70 + n * 42 + (nz(e.title()).isEmpty() ? 0 : 36)}; }
            case "photo" -> { return new double[]{w > 0 ? w : W, h > 0 ? h : H}; }
            case "outro" -> { return new double[]{W * 0.86, h > 0 ? h : 300}; }
            default -> { return new double[]{w > 0 ? w : 300, h > 0 ? h : 80}; }
        }
    }

    // ------------------------------------------------------------------ frame

    private BufferedImage frame(Scene scene, Map<String, Rectangle2D> bounds, int k) {
        double[] cam = camera(scene, bounds);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            hints(g);
            background(g);
            headingText(g, scene.heading(), scene.label(), 1f);
            Graphics2D cg = (Graphics2D) g.create();
            try {
                applyCamera(cg, cam);
                drawLayers(cg, scene.elements(), bounds, e -> e.at() <= k ? 1f : 0f, k);
            } finally {
                cg.dispose();
            }
        } finally {
            g.dispose();
        }
        return glow ? bloom(img) : img;
    }

    // ------------------------------------------------------------------ camera + scene-to-scene motion

    /** Identity camera: {zoom, worldCentreX, worldCentreY, screenCentreX, screenCentreY}. */
    private double[] identity() { return new double[]{1, W / 2.0, H / 2.0, W / 2.0, H / 2.0}; }

    /**
     * Frames the whole final picture of a scene. If everything fits the screen, the camera does nothing.
     * If the picture has grown beyond the frame (elements placed past 100 %), the camera pulls back just
     * enough to show all of it - the reference video's "overview" shot of the whole system.
     */
    double[] camera(Scene scene, Map<String, Rectangle2D> b) {
        Rectangle2D box = null;
        for (Element e : scene.elements()) {
            Rectangle2D r = b.get(e.id());
            if (r == null || backdrop(e)) continue;
            box = box == null ? (Rectangle2D) r.clone() : box.createUnion(r);
        }
        if (box == null) return identity();
        boolean head = !nz(scene.heading()).isEmpty() || !nz(scene.label()).isEmpty();
        double top = portrait ? (head ? 330 : 60) : (head ? 130 : 30), bottom = contentBottom(), left = 30, right = W - 30;
        if (box.getX() >= left - 1 && box.getMaxX() <= right + 1 && box.getY() >= top - 40 && box.getMaxY() <= bottom + 1) return identity();
        double z = Math.min(1.0, Math.min((right - left) / box.getWidth(), (bottom - top) / box.getHeight()));
        z = Math.max(0.42, z);
        return new double[]{z, box.getCenterX(), box.getCenterY(), W / 2.0, (top + bottom) / 2.0};
    }

    private void applyCamera(Graphics2D g, double[] c) {
        if (c[0] == 1 && c[1] == c[3] && c[2] == c[4]) return;
        g.translate(c[3], c[4]);
        g.scale(c[0], c[0]);
        g.translate(-c[1], -c[2]);
    }

    /**
     * The scene's opening motion (default 0.6 s at 30 fps), played over its first frame:
     * elements of the previous scene that are not kept fade out, kept elements glide from their old
     * place to the new one, new elements of sentence 0 fade in, the camera eases between the two framings
     * and the heading cross-fades. Empty list = nothing changes (a hard cut would look identical).
     */
    public synchronized List<BufferedImage> renderIntro(Scene prev, Scene scene, String styleId, int frames) {
        return renderIntro(prev, scene, styleId, frames, false);
    }

    public synchronized List<BufferedImage> renderIntro(Scene prev, Scene scene, String styleId, int frames, boolean vertical) {
        setCanvas(vertical);
        applyTheme(styleId);
        Map<String, Rectangle2D> b1 = layout(scene.elements());
        double[] cam1 = camera(scene, b1);
        Map<String, Rectangle2D> b0 = prev == null ? Map.of() : layout(prev.elements());
        double[] cam0 = prev == null ? cam1 : camera(prev, b0);
        java.util.Set<String> now = new java.util.HashSet<>();
        for (Element e : scene.elements()) now.add(e.id());
        java.util.Set<String> before = new java.util.HashSet<>();
        if (prev != null) for (Element e : prev.elements()) before.add(e.id());

        boolean change = !java.util.Arrays.equals(cam0, cam1) || !nz(prev == null ? "" : prev.heading()).equals(nz(scene.heading()))
                || !nz(prev == null ? "" : prev.label()).equals(nz(scene.label()));
        for (Element e : scene.elements()) {
            if (e.at() > 0) continue;
            if (!before.contains(e.id())) { change = true; break; }
            Rectangle2D a = b0.get(e.id()), c = b1.get(e.id());
            if (a != null && c != null && (Math.abs(a.getX() - c.getX()) > 2 || Math.abs(a.getY() - c.getY()) > 2)) { change = true; break; }
        }
        if (prev != null) for (Element e : prev.elements()) if (!now.contains(e.id())) { change = true; break; }
        double typeSecs = blueprint ? typingSeconds(scene, 0) : 0;
        if (typeSecs > 0) { change = true; frames = Math.max(frames, (int) Math.ceil(typeSecs * 30 / 0.7) + 3); }
        if (!change || frames <= 0) return List.of();

        List<BufferedImage> out = new ArrayList<>();
        String h0 = prev == null ? "" : nz(prev.heading()), h1 = nz(scene.heading());
        String l0 = prev == null ? "" : nz(prev.label()), l1 = nz(scene.label());
        for (int f = 1; f <= frames; f++) {
            double lin = f / (double) frames, t = lin * lin * (3 - 2 * lin);   // smoothstep
            double[] cam = new double[5];
            for (int i = 0; i < 5; i++) cam[i] = cam0[i] + (cam1[i] - cam0[i]) * t;
            Map<String, Rectangle2D> bi = new HashMap<>(b1);
            for (Map.Entry<String, Rectangle2D> en : b1.entrySet()) {
                Rectangle2D a = b0.get(en.getKey()), c = en.getValue();
                if (a != null) bi.put(en.getKey(), new Rectangle2D.Double(a.getX() + (c.getX() - a.getX()) * t, a.getY() + (c.getY() - a.getY()) * t,
                        a.getWidth() + (c.getWidth() - a.getWidth()) * t, a.getHeight() + (c.getHeight() - a.getHeight()) * t));
            }
            Map<String, Integer> lim = new HashMap<>();
            if (typeSecs > 0 && f < frames)
                for (Element e : scene.elements())
                    if (e.at() == 0 && e.type().equals("callout") && !before.contains(e.id()))
                        lim.put(e.id(), (int) Math.max(0, Math.min(visibleLength(e.text()), Math.round((f / 30.0 - frames / 30.0 * 0.3) * TYPE_CPS))));
            typing = lim;
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                hints(g);
                background(g);
                if (h0.equals(h1) && l0.equals(l1)) headingText(g, h1, l1, 1f);
                else { headingText(g, h0, l0, (float) (1 - t)); headingText(g, h1, l1, (float) t); }
                Graphics2D cg = (Graphics2D) g.create();
                try {
                    applyCamera(cg, cam);
                    if (prev != null) {
                        final float fade = (float) Math.max(0, 1 - lin / 0.45);   // old things clear in the first half
                        int last = Math.max(0, prev.sentences() - 1);
                        drawLayers(cg, prev.elements(), b0, e -> now.contains(e.id()) ? 0f : fade, last);
                    }
                    final float in = (float) Math.max(0, Math.min(1, (lin - 0.3) / 0.5));   // new things arrive after
                    drawLayers(cg, scene.elements(), bi, e -> e.at() > 0 ? 0f : before.contains(e.id()) ? 1f : in, -1);
                } finally {
                    cg.dispose();
                }
            } finally {
                g.dispose();
                typing = Map.of();
            }
            out.add(glow ? bloom(img) : img);
        }
        return out;
    }

    /** Containers first (they sit behind), then connectors, then everything else; alpha per element. */
    private void drawLayers(Graphics2D g, List<Element> els, Map<String, Rectangle2D> bounds,
                            java.util.function.ToDoubleFunction<Element> alpha, int k) {
        for (int pass = 0; pass < 3; pass++) {
            for (Element e : els) {
                float a = (float) alpha.applyAsDouble(e);
                if (a <= 0.004f) continue;
                boolean container = e.type().equals("container") || e.type().equals("photo") || e.type().equals("particles");
                boolean arrow = e.type().equals("arrow") || e.type().equals("cables");
                int layer = container ? 0 : arrow ? 1 : 2;
                if (layer != pass) continue;
                boolean dim = k >= 0 ? e.dimAt() >= 0 && e.dimAt() <= k : e.dimAt() == 0;
                boolean hl = k >= 0 && e.highlight() != null && e.highlight().contains(k);
                float total = Math.min(1f, a) * (dim ? 0.28f : 1f);
                var old = g.getComposite();
                if (total < 0.999f) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, total));
                try {
                    if (arrow) { if (e.type().equals("cables")) cables(g, e, bounds); else connector(g, e, bounds); }
                    else {
                        Rectangle2D r = bounds.get(e.id());
                        if (r == null) continue;
                        if (hl) glow(g, r);
                        draw(g, e, r);
                    }
                } finally {
                    g.setComposite(old);
                }
            }
        }
    }

    private void headingText(Graphics2D g, String heading, String label, float alpha) {
        if ((nz(heading).isEmpty() && nz(label).isEmpty()) || alpha <= 0.004f) return;
        var old = g.getComposite();
        if (alpha < 0.999f) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        if (blueprint) {
            // breakdown header: "SERIES · 007" mono label top-left (part after the dot in yellow),
            // the scene heading as a small "—— THE BEST PART" kicker
            float top = portrait ? 120 : 64;
            String l = nz(label);
            if (!l.isEmpty()) {
                int dot = l.indexOf('\u00b7');
                String a = dot > 0 ? l.substring(0, dot + 1) + " " : l, b = dot > 0 ? l.substring(dot + 1).trim() : "";
                text(g, a.toUpperCase(Locale.ROOT), 60, top, mono, portrait ? 24 : 20, MUTED);
                if (!b.isEmpty()) text(g, b.toUpperCase(Locale.ROOT), 60 + widthOf(a.toUpperCase(Locale.ROOT), mono, portrait ? 24 : 20), top, mono, portrait ? 24 : 20, HL);
            }
            String h = nz(heading);
            if (!h.isEmpty()) {
                float y = top + (portrait ? 120 : 70);
                g.setColor(HL);
                g.setStroke(new BasicStroke(2f));
                g.draw(new Line2D.Float(60, y - 9, 120, y - 9));
                text(g, h.toUpperCase(Locale.ROOT), 136, y, mono, portrait ? 26 : 22, HL);
            }
            g.setComposite(old);
            return;
        }
        if (portrait) {
            // reel layout: small mono section label, then a large title (max 2 lines)
            if (!nz(label).isEmpty()) {
                String l = fit(label, mono, 26, W - 120);
                text(g, l, W / 2f - widthOf(l, mono, 26) / 2f, 190, mono, 26, MUTED);
            }
            float y = 265;
            List<String> ls = wrap(nz(heading), sans, 58, W - 120);
            for (int i = 0; i < Math.min(2, ls.size()); i++) {
                String l = i == 1 && ls.size() > 2 ? fit(ls.get(1) + " \u2026", sans, 58, W - 120) : ls.get(i);
                text(g, l, W / 2f - widthOf(l, sans, 58) / 2f, y, sans, 58, TEXT);
                y += 70;
            }
        } else {
            if (!nz(label).isEmpty()) {
                String l = fit(label, mono, 22, W - 200);
                text(g, l, W / 2f - widthOf(l, mono, 22) / 2f, 40, mono, 22, MUTED);
            }
            String t = fit(heading, sans, 46, W - 200);
            text(g, t, W / 2f - widthOf(t, sans, 46) / 2f, 86, sans, 46, TEXT);
        }
        g.setComposite(old);
    }

    /** Neon look: blur a quarter-size copy and screen-add it, so every line and label gets a soft halo. */
    private BufferedImage bloom(BufferedImage src) {
        int w = W / 4, h = H / 4;
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = small.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        sg.drawImage(src, 0, 0, w, h, null);
        sg.dispose();
        int[] px = small.getRGB(0, 0, w, h, null, 0, w);
        for (int pass = 0; pass < 3; pass++) px = boxBlur(px, w, h, 3);
        small.setRGB(0, 0, w, h, px, 0, w);
        BufferedImage halo = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D hg = halo.createGraphics();
        hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        hg.drawImage(small, 0, 0, W, H, null);
        hg.dispose();
        int[] a = src.getRGB(0, 0, W, H, null, 0, W), b = halo.getRGB(0, 0, W, H, null, 0, W);
        double k = bloomAmount;
        for (int i = 0; i < a.length; i++) {
            int r = Math.min(255, ((a[i] >> 16) & 255) + (int) (((b[i] >> 16) & 255) * k));
            int gg = Math.min(255, ((a[i] >> 8) & 255) + (int) (((b[i] >> 8) & 255) * k));
            int bb = Math.min(255, (a[i] & 255) + (int) ((b[i] & 255) * k));
            a[i] = (r << 16) | (gg << 8) | bb;
        }
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, W, H, a, 0, W);
        return out;
    }

    private static int[] boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length], out = new int[px.length];
        for (int pass = 0; pass < 2; pass++) {
            int[] in = pass == 0 ? px : tmp, dst = pass == 0 ? tmp : out;
            boolean horiz = pass == 0;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int sr = 0, sg = 0, sb = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = horiz ? x + k : x, yy = horiz ? y : y + k;
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                    int c = in[yy * w + xx];
                    sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; n++;
                }
                dst[y * w + x] = ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
            }
        }
        return out;
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private void background(Graphics2D g) {
        if (blueprint) { blueprintBackground(g); return; }
        g.setColor(BG);
        g.fillRect(0, 0, W, H);
        // very soft lift in the middle, like the reference's slight vignette
        g.setPaint(new RadialGradientPaint(new Point2D.Float(W * 0.5f, H * 0.45f), W * 0.7f,
                new float[]{0f, 1f}, new Color[]{new Color(BG_GLOW.getRed(), BG_GLOW.getGreen(), BG_GLOW.getBlue(), 150), new Color(BG_GLOW.getRed(), BG_GLOW.getGreen(), BG_GLOW.getBlue(), 0)}));
        g.fillRect(0, 0, W, H);
        if (grid) { // blueprint grid paper
            g.setStroke(new BasicStroke(1f));
            for (int x = 0; x < W; x += 40) { g.setColor(new Color(255, 255, 255, x % 200 == 0 ? 34 : 14)); g.drawLine(x, 0, x, H); }
            for (int y = 0; y < H; y += 40) { g.setColor(new Color(255, 255, 255, y % 200 == 0 ? 34 : 14)); g.drawLine(0, y, W, y); }
        }
    }

    private void glow(Graphics2D g, Rectangle2D r) {
        for (int i = 6; i >= 1; i--) {
            g.setColor(new Color(255, 214, 102, 14 + i * 4));
            g.setStroke(new BasicStroke(i * 3f));
            g.draw(new RoundRectangle2D.Double(r.getX() - 10, r.getY() - 10, r.getWidth() + 20, r.getHeight() + 20, 24, 24));
        }
        g.setColor(new Color(255, 214, 102));
        g.setStroke(new BasicStroke(2.5f));
        g.draw(new RoundRectangle2D.Double(r.getX() - 10, r.getY() - 10, r.getWidth() + 20, r.getHeight() + 20, 24, 24));
    }

    // ------------------------------------------------------------------ elements

    private void draw(Graphics2D g, Element e, Rectangle2D r) {
        switch (e.type()) {
            case "actor" -> actor(g, e, r);
            case "heading" -> heading(g, e, r);
            case "button" -> button(g, e, r);
            case "tile" -> tile(g, e, r);
            case "card" -> card(g, e, r);
            case "note" -> note(g, e, r);
            case "quote" -> quote(g, e, r);
            case "pill" -> pill(g, e, r);
            case "label" -> label(g, e, r);
            case "chat" -> chat(g, e, r);
            case "terminal" -> terminal(g, e, r);
            case "browser" -> browser(g, e, r);
            case "code" -> code(g, e, r);
            case "container" -> container(g, e, r);
            case "message" -> message(g, e, r);
            case "icon" -> icon(g, icon(e, "sparkle"), r.getCenterX(), r.getCenterY(), r.getWidth(), accent(e.color(), CYAN));
            case "metric" -> metric(g, e, r);
            case "value" -> value(g, e, r);
            case "chips" -> chips(g, e, r);
            case "bars" -> bars(g, e, r);
            case "table" -> table(g, e, r);
            case "tabs" -> tabs(g, e, r);
            case "node" -> node(g, e, r);
            case "plot" -> plot(g, e, r);
            case "titlecard" -> titlecard(g, e, r);
            case "journey" -> journey(g, e, r);
            case "callout" -> callout(g, e, r);
            case "db" -> database3d(g, e, r);
            case "server" -> server3d(g, e, r);
            case "phone" -> phone(g, e, r);
            case "rows" -> rows3d(g, e, r);
            case "particles" -> particles(g, e, r);
            case "sql" -> sql(g, e, r);
            case "photo" -> photo(g, e, r);
            case "outro" -> outro(g, e, r);
            default -> button(g, e, r);
        }
    }

    private void actor(Graphics2D g, Element e, Rectangle2D r) {
        String ic = icon(e, "agent");
        boolean user = ic.equals("user");
        double s = user ? 82 : 170;
        double cx = r.getX() + s / 2, cy = r.getCenterY();
        Color col = user ? USER_BLUE : accent(e.color(), PINK);
        if (ic.equals("agent")) agentFigure(g, cx, cy, s, col);
        else if (user) userFigure(g, cx, cy, s, col);
        else icon(g, ic, cx, cy, s, col);
        String t = nz(e.text());
        if (!t.isEmpty()) {
            int size = user ? 30 : 56;
            text(g, t, (float) (r.getX() + s + 14), (float) (cy + size * 0.35), sans, size, col);
        }
    }

    private void heading(Graphics2D g, Element e, Rectangle2D r) {
        String t = fit(nz(e.text()), headFont, 66, (int) r.getWidth());
        float tw = widthOf(t, headFont, 66);
        float x = (float) (r.getCenterX() - tw / 2), y = (float) (r.getCenterY() + 24);
        Color a = accent(e.color(), null);
        if (a == null) g.setPaint(new GradientPaint(x, 0, HEAD_A, x + tw, 0, HEAD_B));
        else g.setColor(a);
        drawText(g, t, x, y, headFont, 66, null);
    }

    private void button(Graphics2D g, Element e, Rectangle2D r) {
        Color fill = TEAL_BTN, edge = TEAL_BTN_EDGE;
        Color a = accent(e.color(), null);
        Color txt = BTN_TEXT;
        if (a != null && !"teal".equalsIgnoreCase(e.color())) {
            fill = light ? new Color(mix(a.getRed(), 255, 0.82), mix(a.getGreen(), 255, 0.82), mix(a.getBlue(), 255, 0.82))
                    : new Color(a.getRed() / 3, a.getGreen() / 3, Math.min(255, a.getBlue() / 3 + 10));
            edge = a;
            if (light) txt = TEXT;
        }
        RoundRectangle2D rr = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 10, 10);
        g.setColor(fill);
        g.fill(rr);
        g.setColor(edge);
        g.setStroke(new BasicStroke(1.6f));
        g.draw(rr);
        centeredLines(g, nz(e.text()), r, sans, 28, txt);
    }

    private static int mix(int a, int b, double t) { return (int) Math.round(a * (1 - t) + b * t); }

    private void tile(Graphics2D g, Element e, Rectangle2D r) {
        Color[] p = TILES.getOrDefault(e.color() == null ? "teal" : e.color().toLowerCase(Locale.ROOT), TILES.get("teal"));
        RoundRectangle2D rr = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 14, 14);
        g.setColor(p[0]);
        g.fill(rr);
        double s = r.getWidth();
        String t = nz(e.text());
        icon(g, icon(e, "box"), r.getCenterX(), r.getY() + r.getHeight() * (t.isEmpty() ? 0.5 : 0.40), s * 0.46, p[1]);
        if (!t.isEmpty()) {
            int size = (int) Math.max(18, Math.min(34, s * 0.19));
            String f = fit(t, sansBold, size, (int) (s - 16));
            text(g, f, (float) (r.getCenterX() - widthOf(f, sansBold, size) / 2), (float) (r.getY() + r.getHeight() * 0.82), sansBold, size, p[2]);
        }
    }

    private void card(Graphics2D g, Element e, Rectangle2D r) {
        Color col = accent(e.color(), CYAN);
        RoundRectangle2D rr = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 14, 14);
        g.setColor(light ? Color.WHITE : new Color(col.getRed(), col.getGreen(), col.getBlue(), 18));
        g.fill(rr);
        g.setColor(col);
        g.setStroke(new BasicStroke(2f));
        g.draw(rr);
        String title = nz(e.title()).isEmpty() ? nz(e.text()) : e.title();
        float y = (float) r.getY() + 46;
        if (!title.isEmpty()) {
            String t = fit(title, sansBold, 30, (int) r.getWidth() - 30);
            float tw = widthOf(t, sansBold, 30), tx = (float) (r.getCenterX() - tw / 2);
            text(g, t, tx, y, sansBold, 30, col);
            g.setStroke(new BasicStroke(2f));
            g.draw(new Line2D.Float(tx, y + 6, tx + tw, y + 6));
            y += 50;
        }
        if (e.lines() != null) {
            for (String l : e.lines()) {
                if (y > r.getMaxY() - 8) break;
                g.setColor(col);
                g.fill(new Ellipse2D.Double(r.getX() + 30, y - 12, 7, 7));
                text(g, fit(l, sans, 28, (int) r.getWidth() - 100), (float) r.getX() + 56, y, sans, 28, col);
                y += 40;
            }
        }
        if (e.icon() != null && !e.icon().isBlank()) icon(g, e.icon(), r.getMaxX() - 40, r.getMaxY() - 36, 46, col);
    }

    private void note(Graphics2D g, Element e, Rectangle2D r) {
        Color col = accent(e.color(), GREEN);
        Color txt = col == GREEN ? GREEN_TXT : col;
        if (light) {
            g.setColor(new Color(mix(col.getRed(), 255, 0.88), mix(col.getGreen(), 255, 0.88), mix(col.getBlue(), 255, 0.88)));
            g.fill(new Rectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight()));
        }
        g.setColor(col);
        g.setStroke(new BasicStroke(1.8f));
        g.draw(new Rectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight()));
        centeredLines(g, nz(e.text()), new Rectangle2D.Double(r.getX() + 20, r.getY(), r.getWidth() - 40, r.getHeight()), sans, 28, txt);
    }

    private void quote(Graphics2D g, Element e, Rectangle2D r) {
        List<String> ls = wrap("\u201C" + nz(e.text()) + "\u201D", sansItalic, 30, (int) r.getWidth());
        float y = (float) r.getY() + 30;
        for (String l : ls) {
            text(g, l, (float) (r.getCenterX() - widthOf(l, sansItalic, 30) / 2), y, sansItalic, 30, TEXT);
            y += 38;
        }
    }

    private void pill(Graphics2D g, Element e, Rectangle2D r) {
        Color col = accent(e.color(), VIOLET);
        RoundRectangle2D rr = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 12, 12);
        boolean solid = "solid".equalsIgnoreCase(e.style());
        g.setColor(solid ? col : new Color(col.getRed(), col.getGreen(), col.getBlue(), 40));
        g.fill(rr);
        g.setColor(col);
        g.setStroke(new BasicStroke(2f));
        g.draw(rr);
        centeredLines(g, nz(e.text()), r, sans, 24, solid ? Color.WHITE : brighten(col));
    }

    private int labelSize(Element e) {
        return switch (nz(e.style()).toLowerCase(Locale.ROOT)) {
            case "small", "s" -> 24;
            case "large", "l" -> 52;
            default -> 34;
        };
    }

    private void label(Graphics2D g, Element e, Rectangle2D r) {
        int sz = labelSize(e);
        Color col = accent(e.color(), TEXT);
        List<String> ls = wrap(nz(e.text()), sans, sz, (int) r.getWidth() + 2);
        float y = (float) r.getY() + sz;
        for (String l : ls) {
            text(g, l, (float) (r.getCenterX() - widthOf(l, sans, sz) / 2), y, sans, sz, col);
            y += sz * 1.25f;
        }
    }

    /** Mock chat-assistant window: app name, user bubble on the right, answer lines below. */
    private void chat(Graphics2D g, Element e, Rectangle2D r) {
        RoundRectangle2D win = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 14, 14);
        g.setColor(new Color(0x1E1B21));
        g.fill(win);
        g.setColor(new Color(0x34303A));
        g.setStroke(new BasicStroke(1.4f));
        g.draw(win);
        Shape clip = g.getClip();
        g.clip(win);
        float x = (float) r.getX() + 26;
        text(g, nz(e.title()).isEmpty() ? "Assistant" : e.title(), x, (float) r.getY() + 40, sans, 20, new Color(0xC9C4CF));
        String user = nz(e.text());
        float y = (float) r.getY() + 70;
        if (!user.isEmpty()) {
            List<String> ul = wrap(user, sans, 20, (int) (r.getWidth() * 0.55));
            float bw = 0;
            for (String l : ul) bw = Math.max(bw, widthOf(l, sans, 20));
            float bx = (float) (r.getMaxX() - 30 - bw - 36);
            g.setColor(new Color(0x34313A));
            g.fill(new RoundRectangle2D.Double(bx, y, bw + 36, ul.size() * 28 + 22, 24, 24));
            float ly = y + 32;
            for (String l : ul) { text(g, l, bx + 18, ly, sans, 20, TEXT); ly += 28; }
            y += ul.size() * 28 + 60;
        }
        if (e.lines() != null) {
            boolean first = true;
            for (String l : e.lines()) {
                if (y > r.getMaxY() - 20) break;
                boolean head = first || l.startsWith("#") || l.endsWith(":");
                String s = l.replaceFirst("^#+\\s*", "");
                for (String w : wrap(s, head ? sansBold : sans, 19, (int) r.getWidth() - 60)) {
                    if (y > r.getMaxY() - 20) break;
                    text(g, w, x + (l.startsWith("-") || l.startsWith("\u2022") ? 18 : 0), y, head ? sansBold : sans, 19, new Color(0xDCD8E0));
                    y += 27;
                }
                y += head ? 12 : 4;
                first = false;
            }
        }
        g.setClip(clip);
    }

    private void windowChrome(Graphics2D g, Rectangle2D r, Color body, String centerTitle, String rightTitle) {
        RoundRectangle2D win = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 14, 14);
        g.setColor(body);
        g.fill(win);
        g.setColor(new Color(0x3A3F47));
        g.setStroke(new BasicStroke(1.4f));
        g.draw(win);
        Color[] dots = {new Color(0xFF5F57), new Color(0xFEBC2E), new Color(0x28C840)};
        for (int i = 0; i < 3; i++) {
            g.setColor(dots[i]);
            g.fill(new Ellipse2D.Double(r.getX() + 18 + i * 22, r.getY() + 14, 13, 13));
        }
        if (centerTitle != null && !centerTitle.isBlank()) {
            String t = fit(centerTitle, sans, 19, (int) r.getWidth() - 200);
            text(g, t, (float) (r.getCenterX() - widthOf(t, sans, 19) / 2), (float) r.getY() + 27, sans, 19, MUTED);
        }
        if (rightTitle != null && !rightTitle.isBlank()) {
            text(g, rightTitle, (float) (r.getMaxX() - 20 - widthOf(rightTitle, sans, 18)), (float) r.getY() + 26, sans, 18, MUTED);
        }
    }

    private void terminal(Graphics2D g, Element e, Rectangle2D r) {
        windowChrome(g, r, new Color(0x0D0E11), null, nz(e.title()).isEmpty() ? "terminal" : e.title());
        Shape clip = g.getClip();
        g.clip(new Rectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight() - 6));
        float y = (float) r.getY() + 74, x = (float) r.getX() + 26;
        String cmd = nz(e.text());
        if (!cmd.isEmpty()) {
            text(g, "$ ", x, y, mono, 24, TEXT);
            float cx = x + widthOf("$ ", mono, 24);
            text(g, fit(cmd, mono, 24, (int) (r.getMaxX() - cx - 20)), cx, y, mono, 24, TEXT);
            y += 42;
        }
        for (String l : lines(e.extra())) {
            if (y > r.getMaxY() - 14) break;
            syntaxLine(g, l, x, y, 21, (int) (r.getMaxX() - x - 16));
            y += 31;
        }
        g.setClip(clip);
    }

    private void browser(Graphics2D g, Element e, Rectangle2D r) {
        windowChrome(g, r, new Color(0xEDEFF2), null, null);
        // tab + url bar
        g.setColor(new Color(0xD8DCE2));
        g.fill(new Rectangle2D.Double(r.getX(), r.getY() + 40, r.getWidth(), 48));
        g.setColor(Color.WHITE);
        RoundRectangle2D bar = new RoundRectangle2D.Double(r.getX() + 60, r.getY() + 48, r.getWidth() - 90, 32, 16, 16);
        g.fill(bar);
        String url = nz(e.title()).isEmpty() ? "www.example.com" : e.title();
        String host = url.contains("/") ? url.substring(0, url.indexOf('/')) : url, path = url.contains("/") ? url.substring(url.indexOf('/')) : "";
        text(g, host, (float) r.getX() + 82, (float) r.getY() + 71, sans, 20, new Color(0x1F2328));
        if (!path.isEmpty()) text(g, fit(path, sans, 20, (int) (r.getWidth() - 160 - widthOf(host, sans, 20))),
                (float) r.getX() + 82 + widthOf(host, sans, 20), (float) r.getY() + 71, sans, 20, new Color(0xB04BC9));
        Rectangle2D body = new Rectangle2D.Double(r.getX() + 1, r.getY() + 88, r.getWidth() - 2, r.getHeight() - 89);
        List<String> content = lines(e.extra());
        boolean data = "json".equalsIgnoreCase(e.style()) || "code".equalsIgnoreCase(e.style())
                || (!content.isEmpty() && (content.get(0).trim().startsWith("{") || content.get(0).trim().startsWith("[") || content.get(0).trim().startsWith("<")));
        Shape clip = g.getClip();
        g.clip(new RoundRectangle2D.Double(body.getX(), body.getY(), body.getWidth(), body.getHeight(), 12, 12));
        if (data) {
            g.setPaint(new GradientPaint((float) body.getX(), (float) body.getY(), new Color(0x1A0C26),
                    (float) body.getMaxX(), (float) body.getMaxY(), new Color(0x3A1150)));
            g.fill(body);
            float y = (float) body.getY() + 40;
            for (String l : content) {
                if (y > body.getMaxY() - 10) break;
                syntaxLine(g, l, (float) body.getX() + 24, y, 21, (int) body.getWidth() - 40);
                y += 30;
            }
        } else {
            // generic web page: hero band, headline, a few text lines, a call-to-action
            g.setColor(Color.WHITE);
            g.fill(body);
            g.setPaint(new GradientPaint((float) body.getX(), (float) body.getY(), new Color(0x8C6B54),
                    (float) body.getMaxX(), (float) body.getY() + 200, new Color(0x3E2C22)));
            g.fill(new Rectangle2D.Double(body.getX(), body.getY(), body.getWidth(), body.getHeight() * 0.55));
            String headline = content.isEmpty() ? nz(e.text()) : content.get(0);
            float y = (float) (body.getY() + body.getHeight() * 0.25);
            for (String l : wrap(headline, sansBold, 40, (int) body.getWidth() - 80)) {
                text(g, l, (float) body.getX() + 36, y, sansBold, 40, Color.WHITE);
                y += 46;
            }
            y = (float) (body.getY() + body.getHeight() * 0.55 + 46);
            for (int i = 1; i < content.size() && y < body.getMaxY() - 20; i++) {
                text(g, fit(content.get(i), sans, 20, (int) body.getWidth() - 80), (float) body.getX() + 36, y, sans, 20, new Color(0x333A44));
                y += 30;
            }
            g.setColor(new Color(0xC8102E));
            g.fill(new RoundRectangle2D.Double(body.getMaxX() - 180, body.getY() + body.getHeight() * 0.62, 140, 40, 6, 6));
            text(g, "Continue", (float) (body.getMaxX() - 152), (float) (body.getY() + body.getHeight() * 0.62 + 27), sansBold, 19, Color.WHITE);
        }
        g.setClip(clip);
    }

    private void code(Graphics2D g, Element e, Rectangle2D r) {
        windowChrome(g, r, new Color(0x111419), nz(e.title()).isEmpty() ? null : e.title(), null);
        List<String> ls = lines(e.extra());
        double avail = r.getHeight() - 70;
        int size = (int) Math.max(14, Math.min(30, avail / (Math.max(1, ls.size()) * 1.45)));
        int longest = 0;
        for (String l : ls) longest = Math.max(longest, l.replace("\t", "    ").length());
        while (size > 14 && longest > 0 && widthOf("M".repeat(longest), mono, size) > r.getWidth() - 100) size--;
        float lh = size * 1.45f;
        float x = (float) r.getX() + 66, y = (float) r.getY() + 56 + size;
        Shape clip = g.getClip();
        g.clip(new Rectangle2D.Double(r.getX(), r.getY() + 40, r.getWidth(), r.getHeight() - 46));
        for (int i = 0; i < ls.size(); i++) {
            String n = String.valueOf(i + 1);
            text(g, n, x - 22 - widthOf(n, mono, size - 2), y, mono, size - 2, new Color(0x5C6370));
            syntaxLine(g, ls.get(i), x, y, size, (int) (r.getMaxX() - x - 16));
            y += lh;
        }
        // dashed box around the lines the narration points at ("style": "3-6")
        int[] span = span(e.style());
        if (span != null && span[0] >= 1 && span[0] <= ls.size()) {
            int a = span[0], b = Math.min(ls.size(), Math.max(a, span[1]));
            float top = (float) r.getY() + 56 + (a - 1) * lh + 4, bottom = (float) r.getY() + 56 + b * lh + 10;
            g.setColor(new Color(0x8A8F98));
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{7, 6}, 0));
            g.draw(new RoundRectangle2D.Double(x - 12, top, r.getMaxX() - x - 30, bottom - top, 10, 10));
        }
        g.setClip(clip);
    }

    private void container(Graphics2D g, Element e, Rectangle2D r) {
        Color col = accent(e.color(), VIOLET);
        RoundRectangle2D rr = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 8, 8);
        g.setPaint(new GradientPaint((float) r.getX(), (float) r.getY(), new Color(col.getRed(), col.getGreen(), col.getBlue(), 70),
                (float) r.getX(), (float) r.getMaxY(), new Color(col.getRed(), col.getGreen(), col.getBlue(), 12)));
        g.fill(rr);
        g.setColor(col);
        g.setStroke(new BasicStroke(2.2f));
        g.draw(rr);
        String t = nz(e.text());
        if (!t.isEmpty()) {
            float y = (float) r.getY() + 48;
            for (String l : wrap(t, sans, 32, (int) r.getWidth() - 30)) {
                text(g, l, (float) (r.getCenterX() - widthOf(l, sans, 32) / 2), y, sans, 32, LAYER_TXT);
                y += 38;
            }
        }
        if (!"none".equalsIgnoreCase(e.icon())) icon(g, e.icon() == null ? "sparkle" : e.icon(), r.getX() + 4, r.getY() + 4, 42, LAYER_TXT);
    }

    /** A sentence travelling between two actors (agent-to-agent dialog): arrow + text above it. */
    private void message(Graphics2D g, Element e, Rectangle2D r) {
        boolean left = "left".equalsIgnoreCase(e.style());
        Color col = accent(e.color(), left ? GREEN : VIOLET);
        float y = (float) r.getMaxY() - 10;
        float x1 = (float) r.getX(), x2 = (float) r.getMaxX();
        g.setColor(col);
        g.setStroke(new BasicStroke(2.2f));
        g.draw(new Line2D.Float(x1, y, x2, y));
        arrowHead(g, left ? x2 : x1, y, left ? x1 : x2, y, col);
        String t = fit(nz(e.text()), sans, 28, (int) r.getWidth());
        text(g, t, (float) (r.getCenterX() - widthOf(t, sans, 28) / 2), y - 16, sans, 28, brighten(col));
    }


    // ------------------------------------------------------------------ short-video (reel) elements

    private static List<String> listOrEmpty(Element e) { return e.lines() == null ? List.of() : e.lines(); }

    /** Big number with a small caps label ("11.4 / LOSS"). */
    private void metric(Graphics2D g, Element e, Rectangle2D r) {
        Color col = accent(e.color(), PINK);
        String t = nz(e.text());
        int size = 150;
        while (size > 60 && widthOf(t, sansBold, size) > r.getWidth() - 20) size -= 6;
        text(g, t, (float) (r.getCenterX() - widthOf(t, sansBold, size) / 2), (float) (r.getY() + size * 0.95), sansBold, size, col);
        String l = nz(e.title()).toUpperCase(Locale.ROOT);
        if (!l.isEmpty()) text(g, l, (float) (r.getCenterX() - widthOf(l, sansBold, 28) / 2), (float) (r.getY() + size * 0.95 + 50), sansBold, 28, col);
    }

    /** Mono value in an outlined pill, first token in the accent colour ("w 0.2"), caption under it. */
    private void value(Graphics2D g, Element e, Rectangle2D r) {
        Color col = accent(e.color(), CYAN);
        RoundRectangle2D rr = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), 112, 24, 24);
        g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 22));
        g.fill(rr);
        g.setColor(col);
        g.setStroke(new BasicStroke(3f));
        g.draw(rr);
        String t = nz(e.text());
        int sp = t.indexOf(' ');
        String head = sp > 0 ? t.substring(0, sp) : "", tail = sp > 0 ? t.substring(sp) : t;
        float tw = widthOf(t, mono, 64), x = (float) (r.getCenterX() - tw / 2), y = (float) r.getY() + 80;
        if (!head.isEmpty()) { text(g, head, x, y, mono, 64, col); x += widthOf(head, mono, 64); }
        text(g, tail, x, y, mono, 64, TEXT);
        String cap = nz(e.title());
        if (!cap.isEmpty()) text(g, cap, (float) (r.getCenterX() - widthOf(cap, sans, 26) / 2), (float) r.getY() + 150, sans, 26, MUTED);
    }

    private double chipGroupWidth(String group) {
        String[] parts = group.split("\\|", 2);
        int n = Math.max(1, parts[0].trim().split("\\s+").length);
        return n * 64 + (n - 1) * 10;
    }

    /** Rows of small square chips in groups with a caption ("1 2 3|cpu"); icon names draw icons. */
    private void chips(Graphics2D g, Element e, Rectangle2D r) {
        boolean solid = "solid".equalsIgnoreCase(e.style());
        double total = 0;
        for (String grp : listOrEmpty(e)) total += chipGroupWidth(grp) + 40;
        double x = r.getCenterX() - Math.max(0, total - 40) / 2, y = r.getY() + 8;
        for (String grp : listOrEmpty(e)) {
            String[] parts = grp.split("\\|", 2);
            String[] chips = parts[0].trim().split("\\s+");
            double gx = x;
            for (String c : chips) {
                RoundRectangle2D rr = new RoundRectangle2D.Double(x, y, 64, 64, 12, 12);
                g.setColor(solid ? TEXT : TEAL_BTN);
                g.fill(rr);
                g.setColor(solid ? TEXT : TEAL_BTN_EDGE);
                g.setStroke(new BasicStroke(1.5f));
                g.draw(rr);
                Color ink = solid ? BG : TEXT;
                if (ICONS.contains(c.toLowerCase(Locale.ROOT))) icon(g, c, x + 32, y + 32, 34, ink);
                else text(g, fit(c, sansBold, 30, 54), (float) (x + 32 - widthOf(fit(c, sansBold, 30, 54), sansBold, 30) / 2), (float) y + 43, sansBold, 30, ink);
                x += 74;
            }
            x -= 10;
            if (parts.length > 1) {
                String l = parts[1].trim();
                text(g, l, (float) ((gx + x) / 2 - widthOf(l, mono, 22) / 2), (float) y + 100, mono, 22, MUTED);
            }
            x += 40;
        }
        if (!nz(e.title()).isEmpty()) {
            String cap = nz(e.title());
            text(g, cap, (float) (r.getCenterX() - widthOf(cap, mono, 24) / 2), (float) r.getMaxY() + 20, mono, 24, MUTED);
        }
    }

    private void card(Graphics2D g, Rectangle2D r) {
        RoundRectangle2D rr = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 22, 22);
        g.setColor(TEAL_BTN);
        g.fill(rr);
        g.setColor(TEAL_BTN_EDGE);
        g.setStroke(new BasicStroke(1.4f));
        g.draw(rr);
    }

    /** Info card: header + right tag, numbered "key|value" rows (the reels' explanation panel). */
    private void table(Graphics2D g, Element e, Rectangle2D r) {
        card(g, r);
        float x = (float) r.getX() + 30, y = (float) r.getY() + 52;
        text(g, fit(nz(e.title()), sansBold, 30, (int) r.getWidth() / 2), x, y, sansBold, 30, TEXT);
        String tag = nz(e.text());
        if (!tag.isEmpty()) text(g, tag, (float) (r.getMaxX() - 30 - widthOf(tag, mono, 22)), y, mono, 22, MUTED);
        y += 60;
        int i = 1;
        double keyW = r.getWidth() * 0.34;
        for (String row : listOrEmpty(e)) {
            if (y > r.getMaxY() - 10) break;
            String[] kv = row.split("\\|", 2);
            g.setColor(new Color(0x3F3F46));
            g.fill(new Ellipse2D.Double(x, y - 24, 30, 30));
            String n = String.valueOf(i++);
            text(g, n, x + 15 - widthOf(n, mono, 18) / 2, y - 3, mono, 18, TEXT);
            text(g, fit(kv[0].trim(), mono, 24, (int) keyW - 60), x + 46, y, mono, 24, MUTED);
            if (kv.length > 1) text(g, fit(kv[1].trim(), sans, 28, (int) (r.getWidth() - keyW - 70)), (float) (r.getX() + keyW + 40), y, sans, 28, TEXT);
            y += 58;
        }
    }

    /** Comparison bars: "label|number|unit" rows, bar length proportional to the number. */
    private void bars(Graphics2D g, Element e, Rectangle2D r) {
        card(g, r);
        float x = (float) r.getX() + 30, y = (float) r.getY() + 52;
        text(g, fit(nz(e.title()), sansBold, 30, (int) r.getWidth() - 60), x, y, sansBold, 30, TEXT);
        y += 64;
        double max = 0;
        List<String[]> rows = new ArrayList<>();
        for (String row : listOrEmpty(e)) {
            String[] p = row.split("\\|");
            rows.add(p);
            if (p.length > 1) max = Math.max(max, parseNum(p[1]));
        }
        Color[] cols = {TEXT, accent(e.color(), GREEN)};
        int i = 0;
        double labelW = r.getWidth() * 0.32, barX = r.getX() + 30 + labelW, barW = r.getWidth() - labelW - 210;
        for (String[] p : rows) {
            if (y > r.getMaxY() - 10) break;
            text(g, fit(p[0].trim(), sans, 26, (int) labelW - 10), x, y, sans, 26, TEXT);
            double v = p.length > 1 ? parseNum(p[1]) : 0, frac = max > 0 ? v / max : 0;
            g.setColor(new Color(0x27272A));
            g.fill(new RoundRectangle2D.Double(barX, y - 18, barW, 14, 14, 14));
            g.setColor(cols[i % 2]);
            g.fill(new RoundRectangle2D.Double(barX, y - 18, Math.max(8, barW * frac), 14, 14, 14));
            String num = (p.length > 1 ? p[1].trim() : "") + (p.length > 2 ? " " + p[2].trim() : "");
            text(g, num, (float) (r.getMaxX() - 30 - widthOf(num, mono, 26)), y, mono, 26, TEXT);
            y += 58;
            i++;
        }
    }

    private static double[] slopeIntercept(String s) {
        if (s == null) return null;
        var m = java.util.regex.Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*[,;\\s]\\s*(-?\\d+(?:\\.\\d+)?)").matcher(s);
        return m.find() ? new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))} : null;
    }

    private static double parseNum(String s) {
        try { return Double.parseDouble(s.replaceAll("[^0-9.]", "")); } catch (Exception e) { return 0; }
    }

    /** Pill tabs ("1 · speed", "2 · size"), the active one (style = its 1-based number) filled white. */
    private void tabs(Graphics2D g, Element e, Rectangle2D r) {
        int active = (int) parseNum(nz(e.style()));
        double x = r.getX();
        int i = 1;
        for (String l : listOrEmpty(e)) {
            double tw = widthOf(l, sans, 26) + 56;
            RoundRectangle2D rr = new RoundRectangle2D.Double(x, r.getY(), tw, r.getHeight(), r.getHeight(), r.getHeight());
            boolean on = i == active;
            g.setColor(on ? TEXT : TEAL_BTN);
            g.fill(rr);
            g.setColor(on ? TEXT : TEAL_BTN_EDGE);
            g.setStroke(new BasicStroke(1.4f));
            g.draw(rr);
            text(g, l, (float) (x + 28), (float) (r.getCenterY() + 9), sans, 26, on ? BG : MUTED);
            x += tw + 14;
            i++;
        }
    }

    /** Wireframe cube (or icon) with a name and a mono sub-label; "boxed" draws a dashed frame. */
    private void node(Graphics2D g, Element e, Rectangle2D r) {
        if ("boxed".equalsIgnoreCase(e.style())) {
            g.setColor(TEAL_BTN_EDGE);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{6, 6}, 0));
            g.draw(new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 22, 22));
        }
        icon(g, icon(e, "cube"), r.getCenterX(), r.getY() + 92, 120, accent(e.color(), MUTED));
        String t = nz(e.text());
        text(g, fit(t, sansBold, 32, (int) r.getWidth() - 20), (float) (r.getCenterX() - widthOf(fit(t, sansBold, 32, (int) r.getWidth() - 20), sansBold, 32) / 2),
                (float) r.getY() + 195, sansBold, 32, TEXT);
        String sub = nz(e.title());
        if (!sub.isEmpty()) {
            String f = fit(sub, mono, 20, (int) r.getWidth() - 20);
            text(g, f, (float) (r.getCenterX() - widthOf(f, mono, 20) / 2), (float) r.getY() + 228, mono, 20, MUTED);
        }
    }

    /** Small chart: points "x,y"; style may contain fit (least-squares line), residual (error lines), decay (loss curve). */
    private void plot(Graphics2D g, Element e, Rectangle2D r) {
        List<double[]> pts = new ArrayList<>();
        for (String l : listOrEmpty(e)) {
            String[] p = l.split("[,;\\s]+");
            if (p.length >= 2) try { pts.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])}); } catch (NumberFormatException ignored) { }
        }
        String st = nz(e.style()).toLowerCase(Locale.ROOT);
        double ax = r.getX() + 40, ay = r.getMaxY() - 50, aw = r.getWidth() - 70, ah = r.getHeight() - 110;
        g.setColor(MUTED);
        g.setStroke(new BasicStroke(2f));
        g.draw(new Line2D.Double(ax, ay, ax + aw, ay));
        g.draw(new Line2D.Double(ax, ay, ax, ay - ah));
        String yl = nz(e.title()), xl = nz(e.text());
        Color yc = st.contains("decay") ? accent("red", PINK) : TEXT;
        if (!yl.isEmpty()) text(g, "\u2191 " + yl, (float) ax + 6, (float) (ay - ah - 14), sansBold, 26, yc);
        if (!xl.isEmpty()) text(g, xl + " \u2192", (float) (ax + aw - widthOf(xl + " \u2192", sansBold, 26)), (float) ay + 40, sansBold, 26, MUTED);
        if (pts.isEmpty()) return;
        double minX = pts.stream().mapToDouble(p -> p[0]).min().orElse(0), maxX = pts.stream().mapToDouble(p -> p[0]).max().orElse(1);
        double minY = Math.min(0, pts.stream().mapToDouble(p -> p[1]).min().orElse(0)), maxY = pts.stream().mapToDouble(p -> p[1]).max().orElse(1);
        if (maxX == minX) maxX = minX + 1;
        if (maxY == minY) maxY = minY + 1;
        final double fx0 = minX, fx1 = maxX, fy0 = minY, fy1 = maxY;
        java.util.function.DoubleUnaryOperator sx = v -> ax + 24 + (v - fx0) / (fx1 - fx0) * (aw - 48);
        java.util.function.DoubleUnaryOperator sy = v -> ay - 16 - (v - fy0) / (fy1 - fy0) * (ah - 40);
        Color line = accent(e.color(), CYAN);
        double[] given = slopeIntercept(e.extra());   // content "0.2, 0.3" = draw THIS line (e.g. a bad first guess)
        if (st.contains("fit") || st.contains("residual") || given != null) {
            double n = pts.size(), mx = 0, my = 0, sxy = 0, sxx = 0;
            for (double[] p : pts) { mx += p[0] / n; my += p[1] / n; }
            for (double[] p : pts) { sxy += (p[0] - mx) * (p[1] - my); sxx += (p[0] - mx) * (p[0] - mx); }
            double k = given != null ? given[0] : sxx == 0 ? 0 : sxy / sxx, b0 = given != null ? given[1] : my - k * mx;
            if (st.contains("residual")) {
                g.setColor(accent("red", PINK));
                g.setStroke(new BasicStroke(2.5f));
                for (double[] p : pts) g.draw(new Line2D.Double(sx.applyAsDouble(p[0]), sy.applyAsDouble(p[1]), sx.applyAsDouble(p[0]), sy.applyAsDouble(k * p[0] + b0)));
            }
            g.setColor(line);
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(sx.applyAsDouble(fx0), sy.applyAsDouble(k * fx0 + b0), sx.applyAsDouble(fx1), sy.applyAsDouble(k * fx1 + b0)));
        }
        if (st.contains("decay") || st.contains("line")) {
            pts.sort((p, q) -> Double.compare(p[0], q[0]));
            Path2D path = new Path2D.Double();
            for (int i = 0; i < pts.size(); i++) {
                double px = sx.applyAsDouble(pts.get(i)[0]), py = sy.applyAsDouble(pts.get(i)[1]);
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            g.setColor(line);
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(path);
        }
        Color dot = st.contains("decay") || st.contains("line") ? line : TEXT;
        for (double[] p : pts) {
            g.setColor(dot);
            g.fill(new Ellipse2D.Double(sx.applyAsDouble(p[0]) - 9, sy.applyAsDouble(p[1]) - 9, 18, 18));
        }
    }

    /** Opening card: topic pill, big green title, grey subtitle, tagline at the bottom. */
    private void titlecard(Graphics2D g, Element e, Rectangle2D r) {
        float cx = (float) r.getCenterX();
        float y = (float) (r.getY() + r.getHeight() * 0.22);
        String tag = nz(e.title());
        if (!tag.isEmpty()) {
            float tw = widthOf(tag, sansBold, 28) + 44;
            RoundRectangle2D pill = new RoundRectangle2D.Double(cx - tw / 2, y - 40, tw, 54, 54, 54);
            g.setColor(TEAL_BTN);
            g.fill(pill);
            g.setColor(TEAL_BTN_EDGE);
            g.setStroke(new BasicStroke(1.6f));
            g.draw(pill);
            text(g, tag, cx - widthOf(tag, sansBold, 28) / 2, y - 3, sansBold, 28, MUTED);
            y += 120;
        }
        int size = portrait ? 96 : 110;
        List<String> ls = wrap(nz(e.text()), sansBold, size, (int) r.getWidth());
        while (ls.size() > 3 && size > 56) { size -= 6; ls = wrap(nz(e.text()), sansBold, size, (int) r.getWidth()); }
        Color green = accent(e.color(), GREEN);
        for (String l : ls) { text(g, l, cx - widthOf(l, sansBold, size) / 2, y, sansBold, size, green); y += size * 1.1f; }
        for (String l : wrap(nz(e.extra()), sansBold, 44, (int) r.getWidth())) {
            text(g, l, cx - widthOf(l, sansBold, 44) / 2, y + 10, sansBold, 44, MUTED);
            y += 54;
        }
        String foot = nz(e.style());
        if (!foot.isEmpty()) text(g, foot, cx - widthOf(foot, sansBold, 32) / 2, (float) r.getMaxY() - 20, sansBold, 32, green);
    }

    /** Closing card: one line, the word in "title" highlighted green ("Learn one concept a day."). */
    private void outro(Graphics2D g, Element e, Rectangle2D r) {
        String hlw = nz(e.title()).toLowerCase(Locale.ROOT);
        int size = portrait ? 76 : 90;
        List<String> ls = wrap(nz(e.text()), sansBold, size, (int) r.getWidth());
        float y = (float) (r.getCenterY() - ls.size() * size * 1.15f / 2 + size * 0.85f);
        for (String l : ls) {
            float x = (float) (r.getCenterX() - widthOf(l, sansBold, size) / 2);
            for (String w : l.split("(?<= )")) {
                boolean on = !hlw.isEmpty() && w.trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "").equals(hlw.replaceAll("[^\\p{L}\\p{N}]", ""));
                text(g, w, x, y, sansBold, size, on ? accent(e.color(), GREEN) : TEXT);
                x += widthOf(w, sansBold, size);
            }
            y += size * 1.15f;
        }
    }


    // ------------------------------------------------------------------ 3D blueprint (real-world breakdown)

    /** Deep navy room: vertical gradient, perspective floor grid to a horizon, soft bokeh specks. */
    private void blueprintBackground(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, new Color(0x123266), 0, H * 0.45f, new Color(0x0B1E40)));
        g.fillRect(0, 0, W, H);
        g.setPaint(new GradientPaint(0, H * 0.45f, new Color(0x0B1E40), 0, H, new Color(0x071530)));
        g.fillRect(0, (int) (H * 0.45f), W, H);
        float horizon = H * (portrait ? 0.30f : 0.36f), vx = W / 2f;
        g.setStroke(new BasicStroke(1.2f));
        for (int i = -14; i <= 14; i++) {                        // rays to the vanishing point
            float bx = vx + i * W * 0.16f;
            g.setPaint(new GradientPaint(vx, horizon, new Color(110, 150, 220, 0), bx, H, new Color(110, 150, 220, 70)));
            g.draw(new Line2D.Float(vx + (bx - vx) * 0.02f, horizon, bx, H));
        }
        for (int k = 1; k <= 18; k++) {                          // depth lines, closer together near the horizon
            double t = k / 18.0, y = horizon + (H - horizon) * t * t;
            g.setColor(new Color(110, 150, 220, (int) (10 + 60 * t)));
            g.draw(new Line2D.Double(0, y, W, y));
        }
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 10; i++) {                            // bokeh specks
            double x = rnd.nextDouble() * W, y = rnd.nextDouble() * H, r0 = 2 + rnd.nextDouble() * 3.5;
            Color c = rnd.nextInt(5) == 0 ? new Color(255, 214, 120) : new Color(220, 232, 255);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) (r0 * 4), new float[]{0f, 0.25f, 1f},
                    new Color[]{new Color(c.getRed(), c.getGreen(), c.getBlue(), 230), new Color(c.getRed(), c.getGreen(), c.getBlue(), 90), new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
            g.fill(new Ellipse2D.Double(x - r0 * 4, y - r0 * 4, r0 * 8, r0 * 8));
        }
        g.setPaint(new RadialGradientPaint(new Point2D.Float(W / 2f, H / 2f), Math.max(W, H) * 0.75f, new float[]{0.6f, 1f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 110)}));
        g.fillRect(0, 0, W, H);
    }

    /** "search -> book now -> payment": a line with dots, done = yellow, current = big glowing yellow dot. */
    private void journey(Graphics2D g, Element e, Rectangle2D r) {
        List<String> steps = listOrEmpty(e);
        if (steps.isEmpty()) return;
        int active = Math.max(1, Math.min(steps.size(), (int) parseNum(nz(e.style()))));
        double y = r.getY() + 22, x0 = r.getX() + 30, x1 = r.getMaxX() - 30;
        double step = steps.size() == 1 ? 0 : (x1 - x0) / (steps.size() - 1);
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(0x3A5486));
        g.draw(new Line2D.Double(x0, y, x1, y));
        g.setColor(HL);
        g.draw(new Line2D.Double(x0, y, x0 + step * (active - 1), y));
        for (int i = 0; i < steps.size(); i++) {
            double cx = x0 + step * i;
            boolean done = i + 1 <= active, cur = i + 1 == active;
            if (cur) {
                g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, y), 30, new float[]{0f, 1f}, new Color[]{new Color(255, 200, 61, 160), new Color(255, 200, 61, 0)}));
                g.fill(new Ellipse2D.Double(cx - 30, y - 30, 60, 60));
            }
            double rr = cur ? 12 : 8;
            g.setColor(done ? HL : new Color(0x0B1C3A));
            g.fill(new Ellipse2D.Double(cx - rr, y - rr, rr * 2, rr * 2));
            g.setColor(done ? HL : new Color(0x6D86B5));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Ellipse2D.Double(cx - rr, y - rr, rr * 2, rr * 2));
            String l = steps.get(i).toUpperCase(Locale.ROOT);
            float lw = widthOf(l, mono, 22);
            float lx = (float) (i == 0 ? cx - 10 : i == steps.size() - 1 ? cx - lw + 10 : cx - lw / 2);
            text(g, l, lx, (float) y + 52, mono, 22, cur ? HL : MUTED);
        }
    }

    // ---- rich text: *highlight* and ~~strike~~ inside one wrapped paragraph

    private record Word(String text, boolean hl, boolean strike) { }

    private List<Word> richWords(String s) {
        List<Word> out = new ArrayList<>();
        boolean hl = false, st = false;
        StringBuilder cur = new StringBuilder();
        String t = nz(s);
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '*') { flush(out, cur, hl, st); hl = !hl; continue; }
            if (c == '~' && i + 1 < t.length() && t.charAt(i + 1) == '~') { flush(out, cur, hl, st); st = !st; i++; continue; }
            if (Character.isWhitespace(c)) { flush(out, cur, hl, st); continue; }
            cur.append(c);
        }
        flush(out, cur, hl, st);
        return out;
    }

    private static void flush(List<Word> out, StringBuilder cur, boolean hl, boolean st) {
        if (cur.length() > 0) { out.add(new Word(cur.toString(), hl, st)); cur.setLength(0); }
    }

    private List<List<Word>> richLines(String s, Font f, int size, int maxW) {
        List<List<Word>> lines = new ArrayList<>();
        List<Word> cur = new ArrayList<>();
        float w = 0, space = widthOf(" ", f, size);
        for (Word wd : richWords(s)) {
            float ww = widthOf(wd.text(), f, size);
            if (!cur.isEmpty() && w + space + ww > maxW) { lines.add(cur); cur = new ArrayList<>(); w = 0; }
            w += (cur.isEmpty() ? 0 : space) + ww;
            cur.add(wd);
        }
        if (!cur.isEmpty()) lines.add(cur);
        return lines;
    }

    /** Draws wrapped rich text; returns the y after the last line. */
    private float drawRich(Graphics2D g, String s, float x, float y, Font f, int size, int maxW, Color base, Color hl, boolean center, float cx) {
        float space = widthOf(" ", f, size);
        for (List<Word> line : richLines(s, f, size, maxW)) {
            float lw = 0;
            for (int i = 0; i < line.size(); i++) lw += widthOf(line.get(i).text(), f, size) + (i > 0 ? space : 0);
            float px = center ? cx - lw / 2 : x;
            for (int i = 0; i < line.size(); i++) {
                Word wd = line.get(i);
                float ww = widthOf(wd.text(), f, size);
                Color c = wd.strike() ? new Color(base.getRed(), base.getGreen(), base.getBlue(), 150) : wd.hl() ? hl : base;
                text(g, wd.text(), px, y, f, size, c);
                if (wd.strike()) {
                    g.setColor(PINK);
                    g.setStroke(new BasicStroke(Math.max(3f, size / 14f)));
                    g.draw(new Line2D.Float(px - 2, y - size * 0.32f, px + ww + 2, y - size * 0.32f));
                }
                px += ww + space;
                caretX = px - space; caretY = y;
            }
            y += size * 1.18f;
        }
        return y;
    }

    /**
     * Glass card with a small mono kicker and bold text (*word* = highlight, ~~words~~ = struck out).
     * style: default (pale edge) | trap (red edge + red highlight) | named ("THIS IS CALLED" + big yellow term) | stat (big numbers);
     * lines = recap rows "Search -> *read replicas*".
     */
    private void callout(Graphics2D g, Element e, Rectangle2D r) {
        Integer limit = typing.get(e.id());
        String fullText = e.text();
        if (limit != null) e = withText(e, truncateMarkup(e.text(), limit));
        caretX = -1;
        String st = nz(e.style()).toLowerCase(Locale.ROOT);
        boolean trap = st.contains("trap"), named = st.contains("named"), stat = st.contains("stat");
        Color edge = trap ? PINK : named ? HL : new Color(0x9FB7E0);
        Color hl = trap ? PINK : stat && st.contains("red") ? PINK : HL;
        RoundRectangle2D rr = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 14, 14);
        g.setColor(new Color(9, 24, 52, 225));
        g.fill(rr);
        if (named || trap) {
            g.setColor(new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 60));
            g.setStroke(new BasicStroke(8f));
            g.draw(rr);
        }
        g.setColor(edge);
        g.setStroke(new BasicStroke(2.2f));
        g.draw(rr);
        float x = (float) r.getX() + 30, y = (float) r.getY() + 44;
        String kicker = nz(e.title()).toUpperCase(Locale.ROOT);
        if (!kicker.isEmpty()) {
            text(g, kicker, x, y, mono, 22, trap ? PINK : HL);
            y += 50;
        } else y += 14;
        int size = named ? 64 : stat ? 60 : 50;
        if (named) {
            if (limit != null) {   // type from a fixed left edge (the finished title's), so the word does not slide while typing
                float fullW = widthOf(nz(fullText).replace("*", ""), sansBold, size);
                float left = (float) Math.max(x, r.getCenterX() - fullW / 2);
                y = drawRich(g, "*" + nz(e.text()).replace("*", "") + "*", left, y + 6, sansBold, size, (int) r.getWidth() - 60, TEXT, HL, false, 0);
            } else
            y = drawRich(g, "*" + nz(e.text()).replace("*", "") + "*", x, y + 6, sansBold, size, (int) r.getWidth() - 60, TEXT, HL, true, (float) r.getCenterX());
        } else if (stat) {
            // numbers twice as big as the words around them
            float px = x;
            for (Word wd : richWords(e.text())) {
                boolean num = wd.text().matches("[\\d.,%×x]+");
                int sz = num ? (int) (size * 2.0) : size;
                Color c = wd.hl() ? hl : TEXT;
                float ww = widthOf(wd.text(), sansBold, sz);
                if (px + ww > r.getMaxX() - 30) { px = x; y += size * 2.0f; }
                text(g, wd.text(), px, y + (num ? size * 0.9f : 0), sansBold, sz, c);
                caretX = px + ww; caretY = y + (num ? size * 0.9f : 0);
                px += ww + widthOf(" ", sansBold, size);
            }
            y += size * 2.1f;
        } else {
            y = drawRich(g, e.text(), x, y + 6, sansBold, size, (int) r.getWidth() - 60, TEXT, hl, false, 0);
        }
        if (limit != null && caretX >= 0) {            // typing caret after the last visible character
            g.setColor(HL);
            g.fill(new Rectangle2D.Double(caretX + 4, caretY - size * 0.78, Math.max(3, size / 14.0), size * 0.92));
        }
        if (limit != null && limit < visibleLength(e.text()) + 1) return;   // recap rows appear after the headline is typed
        for (String l : listOrEmpty(e)) {
            y += 4;
            y = drawRich(g, l.replace("->", "\u2192"), x, y + 34, sansBold, 40, (int) r.getWidth() - 60, TEXT, HL, false, 0) - 34;
            y += 18;
        }
    }

    private static Element withText(Element e, String t) {
        return new Element(e.id(), e.type(), t, e.title(), e.icon(), e.color(), e.lines(), e.extra(), e.from(), e.to(), e.style(),
                e.x(), e.y(), e.w(), e.h(), e.at(), e.dimAt(), e.highlight());
    }

    /** Characters a viewer sees (markup * and ~~ do not count). */
    static int visibleLength(String s) {
        return nz(s).replace("~~", "").replace("*", "").length();
    }

    /** First n visible characters, markup kept balanced. */
    static String truncateMarkup(String s, int n) {
        String t = nz(s);
        StringBuilder out = new StringBuilder();
        int seen = 0;
        boolean hl = false, st = false;
        for (int i = 0; i < t.length() && seen < n; i++) {
            char c = t.charAt(i);
            if (c == '*') { hl = !hl; out.append(c); continue; }
            if (c == '~' && i + 1 < t.length() && t.charAt(i + 1) == '~') { st = !st; out.append("~~"); i++; continue; }
            out.append(c);
            seen++;
        }
        if (hl) out.append('*');
        if (st) out.append("~~");
        return out.toString();
    }

    /** Seconds needed to type the callouts that appear at sentence k (0 = none). */
    public double typingSeconds(Scene scene, int k) {
        int chars = 0;
        for (Element e : scene.elements()) if (e.at() == k && e.type().equals("callout")) chars = Math.max(chars, visibleLength(e.text()));
        return chars == 0 ? 0 : Math.max(0.35, Math.min(1.8, chars / TYPE_CPS));
    }

    /**
     * Frames for the reveal of sentence k (k >= 1) when it brings a callout: the callout types itself in with a caret while
     * the other new elements fade in. Played over the clip from the moment step k starts; empty list = nothing to type.
     */
    public synchronized List<BufferedImage> renderTyping(Scene scene, int k, String styleId, boolean vertical, int fps) {
        setCanvas(vertical);
        applyTheme(styleId);
        double secs = typingSeconds(scene, k);
        if (!blueprint || k <= 0 || secs <= 0) return List.of();
        Map<String, Rectangle2D> b = layout(scene.elements());
        double[] cam = camera(scene, b);
        int frames = (int) Math.ceil(secs * fps) + 3;
        List<BufferedImage> out = new ArrayList<>();
        for (int f = 1; f <= frames; f++) {
            double sec = f / (double) fps;
            Map<String, Integer> lim = new HashMap<>();
            for (Element e : scene.elements())
                if (e.at() == k && e.type().equals("callout")) lim.put(e.id(), (int) Math.min(visibleLength(e.text()), Math.round(sec * TYPE_CPS)));
            if (f == frames) lim.clear();                       // last frame = the finished step (seamless hand-over)
            final float fade = (float) Math.min(1, sec / 0.35);
            typing = lim;
            try {
                BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                try {
                    hints(g);
                    background(g);
                    headingText(g, scene.heading(), scene.label(), 1f);
                    Graphics2D cg = (Graphics2D) g.create();
                    try {
                        applyCamera(cg, cam);
                        drawLayers(cg, scene.elements(), b, e -> e.at() < k ? 1f : e.at() == k ? (e.type().equals("callout") ? 1f : fade) : 0f, k);
                    } finally {
                        cg.dispose();
                    }
                } finally {
                    g.dispose();
                }
                out.add(glow ? bloom(img) : img);
            } finally {
                typing = Map.of();
            }
        }
        return out;
    }

    /** Glowing 3D database: three stacked discs with LEDs, a lit ring on top, floor ring and a mono label plate. */
    private void database3d(Graphics2D g, Element e, Rectangle2D r) {
        String st = nz(e.style()).toLowerCase(Locale.ROOT);
        Color ring = st.contains("slow") || st.contains("alert") || st.contains("red") ? PINK : st.contains("active") || st.contains("glow") || st.contains("main") ? HL : new Color(0x9CC2FF);
        double d = r.getWidth(), cx = r.getCenterX(), top = r.getY() + d * 0.12;
        double eh = d * 0.32, discH = d * 0.30, gap = d * 0.04;
        // floor ring
        double floorY = top + 3 * (discH + gap) + eh * 0.5;
        g.setColor(new Color(150, 190, 255, 60));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{4, 6}, 0));
        g.draw(new Ellipse2D.Double(cx - d * 0.66, floorY - eh * 0.45, d * 1.32, eh * 1.1));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, floorY), (float) (d * 0.7), new float[]{0f, 1f},
                new Color[]{new Color(ring.getRed(), ring.getGreen(), ring.getBlue(), 70), new Color(0, 0, 0, 0)}));
        g.fill(new Ellipse2D.Double(cx - d * 0.7, floorY - eh * 0.6, d * 1.4, eh * 1.2));
        for (int i = 2; i >= 0; i--) {
            double y = top + i * (discH + gap);
            Path2D body = new Path2D.Double();
            body.moveTo(cx - d / 2, y + eh / 2);
            body.lineTo(cx - d / 2, y + eh / 2 + discH);
            body.append(new Arc2D.Double(cx - d / 2, y + discH, d, eh, 180, 180, Arc2D.OPEN), true);
            body.lineTo(cx + d / 2, y + eh / 2);
            body.closePath();
            g.setPaint(new GradientPaint((float) (cx - d / 2), 0, new Color(0x173B78), (float) (cx + d / 2), 0, new Color(0x0E2550)));
            g.fill(body);
            g.setPaint(new GradientPaint((float) (cx - d * 0.15), 0, new Color(120, 170, 255, 70), (float) (cx + d * 0.1), 0, new Color(120, 170, 255, 0)));
            g.fill(body);
            g.setColor(new Color(0x7FA8EE));
            g.setStroke(new BasicStroke(1.6f));
            g.draw(new Arc2D.Double(cx - d / 2, y + discH, d, eh, 180, 180, Arc2D.OPEN));
            g.setColor(new Color(0x4E7FD8));
            g.fill(new Ellipse2D.Double(cx - d / 2, y, d, eh));                // top face
            g.setColor(new Color(0x9CC2FF));
            g.draw(new Ellipse2D.Double(cx - d / 2, y, d, eh));
            for (int k = 0; k < 3; k++) {                                     // LEDs
                double lx = cx - d * 0.08 + k * d * 0.09, ly = y + eh / 2 + discH * 0.55;
                Color led = k == 0 && i == 1 ? HL : Color.WHITE;
                g.setPaint(new RadialGradientPaint(new Point2D.Double(lx, ly), (float) (d * 0.05), new float[]{0f, 1f},
                        new Color[]{led, new Color(led.getRed(), led.getGreen(), led.getBlue(), 0)}));
                g.fill(new Ellipse2D.Double(lx - d * 0.05, ly - d * 0.05, d * 0.1, d * 0.1));
            }
            g.setColor(new Color(0x9CC2FF));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Line2D.Double(cx + d * 0.22, y + eh / 2 + discH * 0.62, cx + d * 0.36, y + eh / 2 + discH * 0.5));
        }
        // lit ring on top
        g.setColor(new Color(ring.getRed(), ring.getGreen(), ring.getBlue(), 90));
        g.setStroke(new BasicStroke((float) (d * 0.06)));
        g.draw(new Ellipse2D.Double(cx - d * 0.3, top + eh * 0.2, d * 0.6, eh * 0.6));
        g.setColor(ring);
        g.setStroke(new BasicStroke((float) (d * 0.025)));
        g.draw(new Ellipse2D.Double(cx - d * 0.3, top + eh * 0.2, d * 0.6, eh * 0.6));
        label3d(g, nz(e.text()), cx, r.getMaxY() - 8, ring == HL);
    }

    /** Mono label plate under a 3D object ("MAIN DB · WRITES"). */
    private void label3d(Graphics2D g, String t, double cx, double baseline, boolean lit) {
        if (t.isEmpty()) return;
        String l = t.toUpperCase(Locale.ROOT);
        float lw = widthOf(l, mono, 24);
        RoundRectangle2D plate = new RoundRectangle2D.Double(cx - lw / 2 - 14, baseline - 34, lw + 28, 44, 6, 6);
        g.setColor(new Color(8, 22, 48, 230));
        g.fill(plate);
        g.setColor(lit ? HL : new Color(0xC9D8F5));
        g.setStroke(new BasicStroke(1.8f));
        g.draw(plate);
        text(g, l, (float) (cx - lw / 2), (float) baseline - 4, mono, 24, lit ? HL : TEXT);
    }

    /** 3D server rack: front, side and top faces, slots with LEDs, label above. */
    private void server3d(Graphics2D g, Element e, Rectangle2D r) {
        double w = r.getWidth() * 0.78, dep = r.getWidth() * 0.22, x = r.getX(), y = r.getY() + 50 + dep * 0.5, h = r.getHeight() - 60 - dep * 0.5;
        Path2D top = new Path2D.Double();
        top.moveTo(x, y); top.lineTo(x + dep, y - dep * 0.5); top.lineTo(x + dep + w, y - dep * 0.5); top.lineTo(x + w, y); top.closePath();
        Path2D side = new Path2D.Double();
        side.moveTo(x + w, y); side.lineTo(x + w + dep, y - dep * 0.5); side.lineTo(x + w + dep, y + h - dep * 0.5); side.lineTo(x + w, y + h); side.closePath();
        g.setColor(new Color(0x2C5296)); g.fill(top);
        g.setColor(new Color(0x10285A)); g.fill(side);
        g.setPaint(new GradientPaint((float) x, (float) y, new Color(0x183A75), (float) x, (float) (y + h), new Color(0x0D2148)));
        g.fill(new Rectangle2D.Double(x, y, w, h));
        g.setColor(new Color(0x9CC2FF)); g.setStroke(new BasicStroke(1.6f));
        g.draw(top); g.draw(side); g.draw(new Rectangle2D.Double(x, y, w, h));
        int slots = 7;
        for (int i = 0; i < slots; i++) {
            double sy = y + 16 + i * (h - 24) / slots, sh = (h - 24) / slots - 8;
            g.setColor(new Color(0x0A1A3A)); g.fill(new RoundRectangle2D.Double(x + 12, sy, w - 24, sh, 4, 4));
            g.setColor(new Color(0x4C74C2)); g.draw(new RoundRectangle2D.Double(x + 12, sy, w - 24, sh, 4, 4));
            g.setColor(i % 3 == 0 ? HL : Color.WHITE);
            g.fill(new Ellipse2D.Double(x + 22, sy + sh / 2 - 3, 6, 6));
            g.setColor(new Color(0x9CC2FF));
            g.draw(new Ellipse2D.Double(x + w - 40, sy + sh / 2 - 7, 14, 14));
        }
        String l = nz(e.text()).toUpperCase(Locale.ROOT);
        if (!l.isEmpty()) text(g, l, (float) (x + (w + dep) / 2 - widthOf(l, mono, 24) / 2), (float) r.getY() + 26, mono, 24, TEXT);
    }

    /** Phone with a real-looking app screen: coloured header (text / title), list cards (lines "Name|STATUS"), CTA (style). */
    private void phone(Graphics2D g, Element e, Rectangle2D r) {
        double x = r.getX(), y = r.getY(), w = r.getWidth(), h = r.getHeight(), rad = w * 0.13;
        g.setPaint(new RadialGradientPaint(new Point2D.Double(r.getCenterX(), r.getMaxY()), (float) (w * 0.8), new float[]{0f, 1f},
                new Color[]{new Color(255, 200, 61, 70), new Color(0, 0, 0, 0)}));
        g.fill(new Ellipse2D.Double(r.getCenterX() - w * 0.8, r.getMaxY() - w * 0.25, w * 1.6, w * 0.5));
        g.setColor(new Color(0x0B0F18)); g.fill(new RoundRectangle2D.Double(x, y, w, h, rad * 2, rad * 2));
        g.setColor(new Color(0x7F9FD6)); g.setStroke(new BasicStroke(2f)); g.draw(new RoundRectangle2D.Double(x, y, w, h, rad * 2, rad * 2));
        double ins = w * 0.035;
        RoundRectangle2D screen = new RoundRectangle2D.Double(x + ins, y + ins, w - ins * 2, h - ins * 2, rad * 1.7, rad * 1.7);
        Shape clip = g.getClip();
        g.clip(screen);
        g.setColor(new Color(0xF4F5F8)); g.fill(screen);
        double sx = x + ins, sw = w - ins * 2, k = sw / 400.0;
        Color brand = accent(e.color(), new Color(0xF07D2B));
        if (e.color() == null) brand = new Color(0xF07D2B);
        g.setColor(brand); g.fill(new Rectangle2D.Double(sx, y + ins, sw, 150 * k));
        text(g, "9:41", (float) (sx + 24 * k), (float) (y + ins + 30 * k), sansBold, (int) (16 * k), Color.WHITE);
        g.setColor(Color.WHITE); g.fill(new Ellipse2D.Double(sx + sw / 2 - 40 * k, y + ins + 12 * k, 80 * k, 22 * k));
        text(g, fit(nz(e.text()).toUpperCase(Locale.ROOT), sansBold, (int) (24 * k), (int) (sw - 40 * k)), (float) (sx + 22 * k), (float) (y + ins + 92 * k), sansBold, (int) (24 * k), Color.WHITE);
        if (!nz(e.title()).isEmpty()) text(g, fit(nz(e.title()), sans, (int) (16 * k), (int) (sw - 40 * k)), (float) (sx + 22 * k), (float) (y + ins + 122 * k), sans, (int) (16 * k), new Color(255, 255, 255, 220));
        double cy = y + ins + 170 * k;
        for (String row : listOrEmpty(e)) {
            if (cy > y + h - 160 * k) break;
            String[] p = row.split("\\|");
            RoundRectangle2D card = new RoundRectangle2D.Double(sx + 16 * k, cy, sw - 32 * k, 92 * k, 14 * k, 14 * k);
            g.setColor(Color.WHITE); g.fill(card);
            g.setColor(new Color(0xD9DEE8)); g.setStroke(new BasicStroke(1.2f)); g.draw(card);
            text(g, fit(p[0].trim(), sansBold, (int) (20 * k), (int) (sw * 0.55)), (float) (sx + 32 * k), (float) (cy + 36 * k), sansBold, (int) (20 * k), new Color(0x1F2937));
            for (int c = 0; c < 3; c++) {
                g.setColor(new Color(0xEEF1F6)); g.fill(new RoundRectangle2D.Double(sx + 32 * k + c * 74 * k, cy + 52 * k, 62 * k, 26 * k, 6 * k, 6 * k));
            }
            if (p.length > 1) {
                String status = p[1].trim().toUpperCase(Locale.ROOT);
                boolean ok = status.contains("AVAIL") || status.contains("OK") || status.contains("CONFIRM");
                Color sc = ok ? new Color(0x16A34A) : status.contains("WAIT") || status.contains("FULL") ? new Color(0xDC2626) : new Color(0x64748B);
                float tw = widthOf(status, sansBold, (int) (14 * k));
                g.setColor(new Color(sc.getRed(), sc.getGreen(), sc.getBlue(), 30));
                g.fill(new RoundRectangle2D.Double(sx + sw - 32 * k - tw - 20 * k, cy + 16 * k, tw + 20 * k, 28 * k, 8 * k, 8 * k));
                text(g, status, (float) (sx + sw - 32 * k - tw - 10 * k), (float) (cy + 36 * k), sansBold, (int) (14 * k), sc);
            }
            cy += 108 * k;
        }
        String cta = nz(e.style());
        if (!cta.isEmpty()) {
            RoundRectangle2D b = new RoundRectangle2D.Double(sx + 16 * k, y + h - ins - 96 * k, sw - 32 * k, 64 * k, 14 * k, 14 * k);
            g.setColor(brand); g.fill(b);
            String c = cta.toUpperCase(Locale.ROOT);
            text(g, c, (float) (sx + sw / 2 - widthOf(c, sansBold, (int) (22 * k)) / 2), (float) (y + h - ins - 56 * k), sansBold, (int) (22 * k), Color.WHITE);
        }
        g.setClip(clip);
    }

    /**
     * Stack of 3D table slabs ("23|B4|LOCKED · YOU|v7"). Status sets the look: BOOKED = dim, FREE = bright,
     * LOCKED = yellow + padlock, HELD = yellow + timer ring, "... YOU" with BOOKED/CONFIRMED = green.
     */
    private void rows3d(Graphics2D g, Element e, Rectangle2D r) {
        double y = r.getY(), w = r.getWidth(), x = r.getX(), rh = 62, depth = 9;
        for (String row : listOrEmpty(e)) {
            String[] p = row.split("\\|");
            String status = p.length > 2 ? p[2].trim().toUpperCase(Locale.ROOT) : p.length > 1 ? p[1].trim().toUpperCase(Locale.ROOT) : "";
            boolean you = status.contains("YOU"), locked = status.contains("LOCK"), held = status.contains("HELD") || status.contains("HOLD");
            boolean booked = status.contains("BOOKED") || status.contains("TAKEN") || status.contains("SOLD");
            boolean ok = you && (booked || status.contains("CONFIRM") || status.contains("PAID"));
            Color edge = ok ? GREEN : (locked || held) ? HL : booked ? new Color(0x5D7AB0) : new Color(0xB8CCF0);
            Color face = ok ? new Color(0x123D35) : (locked || held) ? new Color(0x3A3218) : new Color(0x112B5A);
            g.setColor(new Color(0x07142E));
            g.fill(new RoundRectangle2D.Double(x + 4, y + depth, w, rh, 6, 6));        // extruded underside
            g.setPaint(new GradientPaint((float) x, (float) y, face.brighter(), (float) x, (float) (y + rh), face));
            g.fill(new RoundRectangle2D.Double(x, y, w, rh, 6, 6));
            if (ok || locked || held) {
                g.setColor(new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 70));
                g.setStroke(new BasicStroke(8f));
                g.draw(new RoundRectangle2D.Double(x, y, w, rh, 6, 6));
            }
            g.setColor(edge); g.setStroke(new BasicStroke(2f));
            g.draw(new RoundRectangle2D.Double(x, y, w, rh, 6, 6));
            Color txt = booked && !you ? new Color(0x9DB2D8) : (ok || locked || held) ? edge.brighter() : TEXT;
            float base = (float) (y + rh / 2 + 10);
            text(g, p[0].trim(), (float) x + 22, base, mono, 30, txt);
            g.setColor(new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 120));
            g.draw(new Line2D.Double(x + 86, y + 12, x + 86, y + rh - 12));
            if (p.length > 2) {
                text(g, p[1].trim(), (float) x + 104, base, mono, 24, txt);
                g.draw(new Line2D.Double(x + 170, y + 12, x + 170, y + rh - 12));
            }
            float sx = (float) (x + (p.length > 2 ? 170 : 86) + (w - (p.length > 2 ? 170 : 86) - 90) / 2 - widthOf(status, mono, 24) / 2);
            text(g, status, sx, base, mono, 24, txt);
            if (p.length > 3) {
                String tag = p[3].trim();
                text(g, tag, (float) (x + w - 24 - widthOf(tag, mono, 28)), base, mono, 28, HL);
            }
            if (locked) icon(g, "padlock", x - 52, y + rh / 2, 56, HL);
            if (held) {
                g.setColor(HL); g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Arc2D.Double(x + w - 62, y + 14, 34, 34, 90, -260, Arc2D.OPEN));
            }
            y += rh + 22;
        }
    }

    /** Swarm of glowing requests (style "bots" = two in three red). Deterministic so frames match. */
    private void particles(Graphics2D g, Element e, Rectangle2D r) {
        int n = (int) Math.max(20, Math.min(400, parseNum(nz(e.text())) > 0 ? parseNum(nz(e.text())) : 140));
        boolean bots = nz(e.style()).toLowerCase(Locale.ROOT).contains("bot");
        java.util.Random rnd = new java.util.Random(n * 31L + e.id().hashCode());
        for (int i = 0; i < n; i++) {
            double px = r.getX() + rnd.nextDouble() * r.getWidth(), py = r.getY() + Math.pow(rnd.nextDouble(), 0.8) * r.getHeight();
            double rad = 2 + rnd.nextDouble() * 5;
            Color c = bots && rnd.nextInt(3) < 2 ? PINK : rnd.nextInt(6) == 0 ? HL : new Color(0xDCE8FF);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(px, py), (float) (rad * 3), new float[]{0f, 0.3f, 1f},
                    new Color[]{c, new Color(c.getRed(), c.getGreen(), c.getBlue(), 120), new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
            g.fill(new Ellipse2D.Double(px - rad * 3, py - rad * 3, rad * 6, rad * 6));
        }
    }

    /** A bundle of glowing cables between two elements (connections). text = how many. */
    private void cables(Graphics2D g, Element e, Map<String, Rectangle2D> b) {
        Rectangle2D a = b.get(nz(e.from())), c = b.get(nz(e.to()));
        if (a == null || c == null) return;
        int n = (int) Math.max(1, Math.min(40, parseNum(nz(e.text())) > 0 ? parseNum(nz(e.text())) : 10));
        double x1 = a.getMaxX() + 4, y1 = a.getCenterY(), x2 = c.getX() - 4, y2 = c.getCenterY();
        Color col = accent(e.color(), HL);
        for (int i = 0; i < n; i++) {
            double off = (i - (n - 1) / 2.0) * Math.min(7, 120.0 / n);
            // wires leave together, fan out in the middle (sagging by different amounts) and meet again
            double sag = 30 + Math.abs(off) * 4.5 + (off > 0 ? 18 : 0);
            CubicCurve2D cv = new CubicCurve2D.Double(x1, y1 + off * 0.5, x1 + (x2 - x1) * 0.3, y1 + sag * (off >= 0 ? 1 : -0.6),
                    x1 + (x2 - x1) * 0.7, y2 + sag * (off >= 0 ? 1 : -0.6), x2, y2 + off * 0.5);
            g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 60));
            g.setStroke(new BasicStroke(6f));
            g.draw(cv);
            g.setColor(col);
            g.setStroke(new BasicStroke(2f));
            g.draw(cv);
        }
    }

    /** Code card with a kicker ("2 WAYS · WAY 1"); SQL keywords yellow. */
    private void sql(Graphics2D g, Element e, Rectangle2D r) {
        RoundRectangle2D rr = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 12, 12);
        g.setColor(new Color(7, 18, 40, 235)); g.fill(rr);
        g.setColor(new Color(0x7F9FD6)); g.setStroke(new BasicStroke(1.8f)); g.draw(rr);
        float x = (float) r.getX() + 26, y = (float) r.getY() + 42;
        String k = nz(e.title()).toUpperCase(Locale.ROOT);
        if (!k.isEmpty()) { text(g, k, x, y, mono, 20, HL); y += 40; }
        java.util.Set<String> kw = java.util.Set.of("SELECT", "FROM", "WHERE", "UPDATE", "SET", "AND", "OR", "FOR", "INSERT", "INTO", "VALUES",
                "DELETE", "JOIN", "ON", "BEGIN", "COMMIT", "ROLLBACK", "LOCK", "RETURNING", "LIMIT", "ORDER", "BY", "GROUP", "EXPIRE", "GET", "INCR", "DECR");
        for (String line : lines(e.extra())) {
            float px = x;
            for (String tok : line.split("(?<=\\s)|(?=\\s)")) {
                String clean = tok.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
                Color c = kw.contains(clean) && tok.trim().equals(tok.trim().toUpperCase(Locale.ROOT)) ? HL : TEXT;
                text(g, tok, px, y, mono, 28, c);
                px += widthOf(tok, mono, 28);
            }
            y += 42;
        }
    }

    /** Full-bleed (or framed) generated picture of the real-world example; content = image path. */
    private void photo(Graphics2D g, Element e, Rectangle2D r) {
        BufferedImage img = null;
        try {
            if (e.extra() != null && Files.isRegularFile(Path.of(e.extra().trim()))) img = javax.imageio.ImageIO.read(Path.of(e.extra().trim()).toFile());
        } catch (Exception ignored) { }
        if (img == null) return;
        double s = Math.max(r.getWidth() / img.getWidth(), r.getHeight() / img.getHeight());
        double dw = img.getWidth() * s, dh = img.getHeight() * s;
        Shape clip = g.getClip();
        g.clip(r);
        g.drawImage(img, (int) (r.getCenterX() - dw / 2), (int) (r.getCenterY() - dh / 2), (int) dw, (int) dh, null);
        g.setPaint(new GradientPaint(0, (float) r.getY(), new Color(8, 20, 45, 200), 0, (float) (r.getY() + r.getHeight() * 0.3), new Color(8, 20, 45, 0)));
        g.fill(r);
        g.setClip(clip);
    }

    // ------------------------------------------------------------------ connectors

    private void connector(Graphics2D g, Element e, Map<String, Rectangle2D> b) {
        Rectangle2D a = b.get(nz(e.from())), c = b.get(nz(e.to()));
        if (a == null || c == null) return;
        double dx = c.getCenterX() - a.getCenterX(), dy = c.getCenterY() - a.getCenterY();
        Point2D p1, p2;
        boolean horizontal = Math.abs(dx) >= Math.abs(dy) * 0.6;
        if (horizontal) {
            p1 = new Point2D.Double(dx >= 0 ? a.getMaxX() + 6 : a.getX() - 6, a.getCenterY());
            p2 = new Point2D.Double(dx >= 0 ? c.getX() - 8 : c.getMaxX() + 8, c.getCenterY());
        } else {
            p1 = new Point2D.Double(a.getCenterX(), dy >= 0 ? a.getMaxY() + 6 : a.getY() - 6);
            p2 = new Point2D.Double(c.getCenterX(), dy >= 0 ? c.getY() - 8 : c.getMaxY() + 8);
        }
        double k = 0.5;
        Point2D c1 = horizontal ? new Point2D.Double(p1.getX() + (p2.getX() - p1.getX()) * k, p1.getY())
                : new Point2D.Double(p1.getX(), p1.getY() + (p2.getY() - p1.getY()) * k);
        Point2D c2 = horizontal ? new Point2D.Double(p2.getX() - (p2.getX() - p1.getX()) * k, p2.getY())
                : new Point2D.Double(p2.getX(), p2.getY() - (p2.getY() - p1.getY()) * k);
        Color col = accent(e.color(), ARROW);
        boolean solid = "solid".equalsIgnoreCase(e.style());
        g.setColor(col);
        g.setStroke(solid ? new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                : new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{9, 8}, 0));
        g.draw(new CubicCurve2D.Double(p1.getX(), p1.getY(), c1.getX(), c1.getY(), c2.getX(), c2.getY(), p2.getX(), p2.getY()));
        arrowHead(g, c2.getX(), c2.getY(), p2.getX(), p2.getY(), col);
        if ("both".equalsIgnoreCase(e.style())) arrowHead(g, c1.getX(), c1.getY(), p1.getX(), p1.getY(), col);
        String t = nz(e.text());
        if (!t.isEmpty()) {
            double mx = (p1.getX() + 3 * c1.getX() + 3 * c2.getX() + p2.getX()) / 8, my = (p1.getY() + 3 * c1.getY() + 3 * c2.getY() + p2.getY()) / 8;
            float tw = widthOf(t, sans, 22);
            RoundRectangle2D tag = new RoundRectangle2D.Double(mx - tw / 2 - 16, my - 20, tw + 32, 40, 10, 10);
            g.setColor(TAG_BG);
            g.fill(tag);
            g.setColor(themeId.equals("reference") ? new Color(0x2FB3A6) : ARROW);
            g.setStroke(new BasicStroke(1.6f));
            g.draw(tag);
            text(g, t, (float) (mx - tw / 2), (float) (my + 8), sans, 22, TEXT);
        }
    }

    private static void arrowHead(Graphics2D g, double fx, double fy, double tx, double ty, Color col) {
        double ang = Math.atan2(ty - fy, tx - fx), len = 14, spread = 0.45;
        Path2D p = new Path2D.Double();
        p.moveTo(tx, ty);
        p.lineTo(tx - len * Math.cos(ang - spread), ty - len * Math.sin(ang - spread));
        p.lineTo(tx - len * Math.cos(ang + spread), ty - len * Math.sin(ang + spread));
        p.closePath();
        g.setColor(col);
        g.fill(p);
    }

    // ------------------------------------------------------------------ figures + icons

    /** The reference's "agent": a bust with slick hair, dark glasses, suit and tie, in one accent colour. */
    private static void agentFigure(Graphics2D g, double cx, double cy, double s, Color col) {
        double hr = s * 0.21, hx = cx, hy = cy - s * 0.17;
        // shoulders / suit
        Path2D suit = new Path2D.Double();
        suit.moveTo(cx - s * 0.42, cy + s * 0.48);
        suit.curveTo(cx - s * 0.42, cy + s * 0.12, cx - s * 0.30, cy + s * 0.06, cx - s * 0.12, cy + s * 0.05);
        suit.lineTo(cx + s * 0.12, cy + s * 0.05);
        suit.curveTo(cx + s * 0.30, cy + s * 0.06, cx + s * 0.42, cy + s * 0.12, cx + s * 0.42, cy + s * 0.48);
        suit.closePath();
        g.setColor(col);
        g.fill(suit);
        // shirt V + tie
        Path2D v = new Path2D.Double();
        v.moveTo(cx - s * 0.10, cy + s * 0.05);
        v.lineTo(cx, cy + s * 0.30);
        v.lineTo(cx + s * 0.10, cy + s * 0.05);
        v.closePath();
        g.setColor(new Color(0xF4F0F6));
        g.fill(v);
        Path2D tie = new Path2D.Double();
        tie.moveTo(cx - s * 0.025, cy + s * 0.06);
        tie.lineTo(cx + s * 0.025, cy + s * 0.06);
        tie.lineTo(cx + s * 0.04, cy + s * 0.24);
        tie.lineTo(cx, cy + s * 0.30);
        tie.lineTo(cx - s * 0.04, cy + s * 0.24);
        tie.closePath();
        g.setColor(new Color(0x1C1C24));
        g.fill(tie);
        // neck + head
        g.setColor(col);
        g.fill(new Rectangle2D.Double(cx - s * 0.06, hy + hr * 0.6, s * 0.12, s * 0.16));
        g.fill(new Ellipse2D.Double(hx - hr, hy - hr * 1.05, hr * 2, hr * 2.25));
        // hair
        Path2D hair = new Path2D.Double();
        hair.moveTo(hx - hr * 1.02, hy - hr * 0.1);
        hair.curveTo(hx - hr * 1.15, hy - hr * 1.5, hx + hr * 1.2, hy - hr * 1.6, hx + hr * 1.03, hy - hr * 0.15);
        hair.curveTo(hx + hr * 0.6, hy - hr * 0.75, hx - hr * 0.4, hy - hr * 0.7, hx - hr * 1.02, hy - hr * 0.1);
        hair.closePath();
        g.setColor(col.darker());
        g.fill(hair);
        // sunglasses
        g.setColor(new Color(0x111116));
        g.fill(new RoundRectangle2D.Double(hx - hr * 0.92, hy - hr * 0.12, hr * 0.82, hr * 0.42, 8, 8));
        g.fill(new RoundRectangle2D.Double(hx + hr * 0.10, hy - hr * 0.12, hr * 0.82, hr * 0.42, 8, 8));
        g.fill(new Rectangle2D.Double(hx - hr * 0.12, hy - hr * 0.05, hr * 0.24, hr * 0.08));
    }

    private static void userFigure(Graphics2D g, double cx, double cy, double s, Color col) {
        g.setColor(col);
        g.fill(new Ellipse2D.Double(cx - s * 0.20, cy - s * 0.42, s * 0.40, s * 0.40));
        Path2D body = new Path2D.Double();
        body.moveTo(cx - s * 0.38, cy + s * 0.42);
        body.curveTo(cx - s * 0.38, cy + s * 0.05, cx - s * 0.2, cy + s * 0.02, cx, cy + s * 0.02);
        body.curveTo(cx + s * 0.2, cy + s * 0.02, cx + s * 0.38, cy + s * 0.05, cx + s * 0.38, cy + s * 0.42);
        body.closePath();
        g.fill(body);
    }

    /** Simple line/solid glyphs for common tech ideas. Unknown names draw a rounded box with an initial. */
    void icon(Graphics2D g, String name, double cx, double cy, double s, Color col) {
        String n = name == null ? "box" : name.toLowerCase(Locale.ROOT).trim();
        double h = s / 2;
        g.setColor(col);
        BasicStroke line = new BasicStroke((float) Math.max(2, s * 0.06), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        g.setStroke(line);
        switch (n) {
            case "user" -> userFigure(g, cx, cy, s, col);
            case "agent" -> agentFigure(g, cx, cy, s, col);
            case "robot", "bot" -> {
                g.draw(new RoundRectangle2D.Double(cx - h * 0.8, cy - h * 0.45, h * 1.6, h * 1.2, s * 0.2, s * 0.2));
                g.fill(new Ellipse2D.Double(cx - h * 0.45, cy - h * 0.05, h * 0.25, h * 0.25));
                g.fill(new Ellipse2D.Double(cx + h * 0.2, cy - h * 0.05, h * 0.25, h * 0.25));
                g.draw(new Line2D.Double(cx, cy - h * 0.45, cx, cy - h * 0.8));
                g.fill(new Ellipse2D.Double(cx - h * 0.1, cy - h * 0.95, h * 0.2, h * 0.2));
            }
            case "plane" -> {
                Path2D p = new Path2D.Double();
                // fuselage along the diagonal, wings and tail
                double a = Math.toRadians(-35), c = Math.cos(a), sn = Math.sin(a);
                double[][] pts = {{-0.95, -0.08}, {0.75, -0.08}, {0.95, 0}, {0.75, 0.08}, {-0.95, 0.08}};
                p.moveTo(cx + h * (pts[0][0] * c - pts[0][1] * sn), cy + h * (pts[0][0] * sn + pts[0][1] * c));
                for (double[] q : pts) p.lineTo(cx + h * (q[0] * c - q[1] * sn), cy + h * (q[0] * sn + q[1] * c));
                p.closePath();
                double[][] wing = {{0.15, 0}, {-0.25, -0.75}, {-0.42, -0.75}, {-0.2, 0}, {-0.42, 0.75}, {-0.25, 0.75}};
                Path2D w = new Path2D.Double();
                w.moveTo(cx + h * (wing[0][0] * c - wing[0][1] * sn), cy + h * (wing[0][0] * sn + wing[0][1] * c));
                for (double[] q : wing) w.lineTo(cx + h * (q[0] * c - q[1] * sn), cy + h * (q[0] * sn + q[1] * c));
                w.closePath();
                double[][] tail = {{-0.7, 0}, {-0.88, -0.35}, {-0.98, -0.35}, {-0.9, 0}, {-0.98, 0.35}, {-0.88, 0.35}};
                Path2D t = new Path2D.Double();
                t.moveTo(cx + h * (tail[0][0] * c - tail[0][1] * sn), cy + h * (tail[0][0] * sn + tail[0][1] * c));
                for (double[] q : tail) t.lineTo(cx + h * (q[0] * c - q[1] * sn), cy + h * (q[0] * sn + q[1] * c));
                t.closePath();
                g.fill(p); g.fill(w); g.fill(t);
            }
            case "hotel" -> {
                g.draw(new Rectangle2D.Double(cx - h * 0.7, cy - h * 0.9, h * 1.4, h * 1.8));
                for (int r = 0; r < 3; r++) for (int c = 0; c < 2; c++)
                    g.fill(new Rectangle2D.Double(cx - h * 0.45 + c * h * 0.6, cy - h * 0.65 + r * h * 0.45, h * 0.3, h * 0.25));
                g.fill(new Rectangle2D.Double(cx - h * 0.15, cy + h * 0.55, h * 0.3, h * 0.35));
            }
            case "server" -> {
                for (int i = 0; i < 3; i++) {
                    double y = cy - h * 0.85 + i * h * 0.6;
                    g.draw(new RoundRectangle2D.Double(cx - h * 0.85, y, h * 1.7, h * 0.48, 8, 8));
                    g.fill(new Ellipse2D.Double(cx + h * 0.5, y + h * 0.16, h * 0.16, h * 0.16));
                }
            }
            case "database" -> {
                double w = h * 1.4, eh = h * 0.45;
                g.draw(new Ellipse2D.Double(cx - w / 2, cy - h * 0.85, w, eh));
                g.draw(new Arc2D.Double(cx - w / 2, cy - h * 0.25, w, eh, 180, 180, Arc2D.OPEN));
                g.draw(new Arc2D.Double(cx - w / 2, cy + h * 0.35, w, eh, 180, 180, Arc2D.OPEN));
                g.draw(new Line2D.Double(cx - w / 2, cy - h * 0.62, cx - w / 2, cy + h * 0.58));
                g.draw(new Line2D.Double(cx + w / 2, cy - h * 0.62, cx + w / 2, cy + h * 0.58));
            }
            case "cloud" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(cx - h * 0.7, cy + h * 0.4);
                p.curveTo(cx - h * 1.1, cy + h * 0.4, cx - h * 1.0, cy - h * 0.2, cx - h * 0.6, cy - h * 0.15);
                p.curveTo(cx - h * 0.55, cy - h * 0.7, cx + h * 0.2, cy - h * 0.8, cx + h * 0.3, cy - h * 0.3);
                p.curveTo(cx + h * 0.9, cy - h * 0.45, cx + h * 1.1, cy + h * 0.4, cx + h * 0.65, cy + h * 0.4);
                p.closePath();
                g.draw(p);
            }
            case "brain" -> {
                g.draw(new Ellipse2D.Double(cx - h * 0.85, cy - h * 0.7, h * 0.95, h * 1.4));
                g.draw(new Ellipse2D.Double(cx - h * 0.1, cy - h * 0.7, h * 0.95, h * 1.4));
                g.draw(new Arc2D.Double(cx - h * 0.6, cy - h * 0.35, h * 0.5, h * 0.4, 0, 200, Arc2D.OPEN));
                g.draw(new Arc2D.Double(cx + h * 0.1, cy, h * 0.5, h * 0.4, 180, 200, Arc2D.OPEN));
            }
            case "gear" -> {
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    g.draw(new Line2D.Double(cx + Math.cos(a) * h * 0.55, cy + Math.sin(a) * h * 0.55, cx + Math.cos(a) * h * 0.9, cy + Math.sin(a) * h * 0.9));
                }
                g.draw(new Ellipse2D.Double(cx - h * 0.6, cy - h * 0.6, h * 1.2, h * 1.2));
                g.draw(new Ellipse2D.Double(cx - h * 0.25, cy - h * 0.25, h * 0.5, h * 0.5));
            }
            case "terminal" -> {
                g.draw(new RoundRectangle2D.Double(cx - h * 0.9, cy - h * 0.7, h * 1.8, h * 1.4, 10, 10));
                Path2D p = new Path2D.Double();
                p.moveTo(cx - h * 0.55, cy - h * 0.3); p.lineTo(cx - h * 0.2, cy); p.lineTo(cx - h * 0.55, cy + h * 0.3);
                g.draw(p);
                g.draw(new Line2D.Double(cx, cy + h * 0.32, cx + h * 0.5, cy + h * 0.32));
            }
            case "browser", "globe" -> {
                if (n.equals("globe")) {
                    g.draw(new Ellipse2D.Double(cx - h * 0.85, cy - h * 0.85, h * 1.7, h * 1.7));
                    g.draw(new Ellipse2D.Double(cx - h * 0.4, cy - h * 0.85, h * 0.8, h * 1.7));
                    g.draw(new Line2D.Double(cx - h * 0.85, cy, cx + h * 0.85, cy));
                } else {
                    g.draw(new RoundRectangle2D.Double(cx - h * 0.9, cy - h * 0.7, h * 1.8, h * 1.4, 10, 10));
                    g.draw(new Line2D.Double(cx - h * 0.9, cy - h * 0.35, cx + h * 0.9, cy - h * 0.35));
                    for (int i = 0; i < 3; i++) g.fill(new Ellipse2D.Double(cx - h * 0.75 + i * h * 0.2, cy - h * 0.58, h * 0.11, h * 0.11));
                }
            }
            case "doc", "list", "book" -> {
                g.draw(new RoundRectangle2D.Double(cx - h * 0.6, cy - h * 0.85, h * 1.2, h * 1.7, 8, 8));
                for (int i = 0; i < 4; i++) g.draw(new Line2D.Double(cx - h * 0.35, cy - h * 0.45 + i * h * 0.3, cx + h * 0.35, cy - h * 0.45 + i * h * 0.3));
            }
            case "lock", "key", "shield" -> {
                if (n.equals("shield")) {
                    Path2D p = new Path2D.Double();
                    p.moveTo(cx, cy - h * 0.9); p.lineTo(cx + h * 0.75, cy - h * 0.6); p.curveTo(cx + h * 0.75, cy + h * 0.3, cx + h * 0.3, cy + h * 0.7, cx, cy + h * 0.9);
                    p.curveTo(cx - h * 0.3, cy + h * 0.7, cx - h * 0.75, cy + h * 0.3, cx - h * 0.75, cy - h * 0.6); p.closePath();
                    g.draw(p);
                } else {
                    g.draw(new Arc2D.Double(cx - h * 0.45, cy - h * 0.95, h * 0.9, h * 0.9, 0, 180, Arc2D.OPEN));
                    g.draw(new Line2D.Double(cx - h * 0.45, cy - h * 0.5, cx - h * 0.45, cy - h * 0.15));
                    g.draw(new Line2D.Double(cx + h * 0.45, cy - h * 0.5, cx + h * 0.45, cy - h * 0.15));
                    g.fill(new RoundRectangle2D.Double(cx - h * 0.7, cy - h * 0.15, h * 1.4, h * 1.0, 8, 8));
                }
            }
            case "chart" -> {
                g.draw(new Line2D.Double(cx - h * 0.85, cy + h * 0.8, cx + h * 0.85, cy + h * 0.8));
                double[] hs = {0.5, 0.9, 0.7, 1.3};
                for (int i = 0; i < 4; i++) g.fill(new Rectangle2D.Double(cx - h * 0.75 + i * h * 0.42, cy + h * 0.8 - h * hs[i], h * 0.28, h * hs[i]));
            }
            case "mail", "chat" -> {
                if (n.equals("mail")) {
                    g.draw(new RoundRectangle2D.Double(cx - h * 0.9, cy - h * 0.6, h * 1.8, h * 1.2, 8, 8));
                    Path2D p = new Path2D.Double();
                    p.moveTo(cx - h * 0.9, cy - h * 0.55); p.lineTo(cx, cy + h * 0.1); p.lineTo(cx + h * 0.9, cy - h * 0.55);
                    g.draw(p);
                } else {
                    g.draw(new RoundRectangle2D.Double(cx - h * 0.9, cy - h * 0.7, h * 1.8, h * 1.1, 16, 16));
                    Path2D p = new Path2D.Double();
                    p.moveTo(cx - h * 0.4, cy + h * 0.4); p.lineTo(cx - h * 0.6, cy + h * 0.8); p.lineTo(cx, cy + h * 0.4);
                    g.draw(p);
                }
            }
            case "git", "network" -> {
                double[][] nodes = n.equals("git") ? new double[][]{{-0.4, -0.7}, {-0.4, 0.7}, {0.45, -0.2}} : new double[][]{{0, -0.7}, {-0.7, 0.6}, {0.7, 0.6}};
                g.draw(new Line2D.Double(cx + nodes[0][0] * h, cy + nodes[0][1] * h, cx + nodes[1][0] * h, cy + nodes[1][1] * h));
                g.draw(new CubicCurve2D.Double(cx + nodes[2][0] * h, cy + nodes[2][1] * h, cx + nodes[2][0] * h, cy + h * 0.3,
                        cx + nodes[1][0] * h, cy, cx + nodes[1][0] * h, cy + nodes[1][1] * h * 0.6));
                if (n.equals("network")) g.draw(new Line2D.Double(cx + nodes[1][0] * h, cy + nodes[1][1] * h, cx + nodes[2][0] * h, cy + nodes[2][1] * h));
                for (double[] q : nodes) g.fill(new Ellipse2D.Double(cx + q[0] * h - h * 0.2, cy + q[1] * h - h * 0.2, h * 0.4, h * 0.4));
            }
            case "code", "api" -> {
                Path2D l = new Path2D.Double(), r = new Path2D.Double();
                l.moveTo(cx - h * 0.35, cy - h * 0.55); l.lineTo(cx - h * 0.85, cy); l.lineTo(cx - h * 0.35, cy + h * 0.55);
                r.moveTo(cx + h * 0.35, cy - h * 0.55); r.lineTo(cx + h * 0.85, cy); r.lineTo(cx + h * 0.35, cy + h * 0.55);
                g.draw(l); g.draw(r);
                g.draw(new Line2D.Double(cx + h * 0.15, cy - h * 0.7, cx - h * 0.15, cy + h * 0.7));
            }
            case "search" -> {
                g.draw(new Ellipse2D.Double(cx - h * 0.75, cy - h * 0.75, h * 1.1, h * 1.1));
                g.setStroke(new BasicStroke((float) Math.max(3, s * 0.1), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(cx + h * 0.2, cy + h * 0.2, cx + h * 0.8, cy + h * 0.8));
            }
            case "bolt" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(cx + h * 0.2, cy - h * 0.95); p.lineTo(cx - h * 0.55, cy + h * 0.1); p.lineTo(cx - h * 0.05, cy + h * 0.1);
                p.lineTo(cx - h * 0.25, cy + h * 0.95); p.lineTo(cx + h * 0.55, cy - h * 0.15); p.lineTo(cx + h * 0.05, cy - h * 0.15); p.closePath();
                g.fill(p);
            }
            case "cart" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(cx - h * 0.95, cy - h * 0.7); p.lineTo(cx - h * 0.65, cy - h * 0.7); p.lineTo(cx - h * 0.4, cy + h * 0.35); p.lineTo(cx + h * 0.7, cy + h * 0.35);
                p.lineTo(cx + h * 0.85, cy - h * 0.4); p.lineTo(cx - h * 0.55, cy - h * 0.4);
                g.draw(p);
                g.fill(new Ellipse2D.Double(cx - h * 0.4, cy + h * 0.55, h * 0.3, h * 0.3));
                g.fill(new Ellipse2D.Double(cx + h * 0.4, cy + h * 0.55, h * 0.3, h * 0.3));
            }
            case "folder" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(cx - h * 0.9, cy - h * 0.6); p.lineTo(cx - h * 0.3, cy - h * 0.6); p.lineTo(cx - h * 0.15, cy - h * 0.4);
                p.lineTo(cx + h * 0.9, cy - h * 0.4); p.lineTo(cx + h * 0.9, cy + h * 0.65); p.lineTo(cx - h * 0.9, cy + h * 0.65); p.closePath();
                g.draw(p);
            }
            case "phone" -> {
                g.draw(new RoundRectangle2D.Double(cx - h * 0.5, cy - h * 0.9, h, h * 1.8, 14, 14));
                g.fill(new Ellipse2D.Double(cx - h * 0.08, cy + h * 0.62, h * 0.16, h * 0.16));
            }
            case "check" -> {
                g.draw(new Ellipse2D.Double(cx - h * 0.85, cy - h * 0.85, h * 1.7, h * 1.7));
                Path2D p = new Path2D.Double();
                p.moveTo(cx - h * 0.45, cy); p.lineTo(cx - h * 0.1, cy + h * 0.35); p.lineTo(cx + h * 0.5, cy - h * 0.35);
                g.draw(p);
            }
            case "warning" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(cx, cy - h * 0.55); p.lineTo(cx + h * 0.9, cy + h * 0.75); p.lineTo(cx - h * 0.9, cy + h * 0.75); p.closePath();
                g.draw(p);
                g.draw(new Line2D.Double(cx, cy - h * 0.25, cx, cy + h * 0.25));
                g.fill(new Ellipse2D.Double(cx - h * 0.07, cy + h * 0.42, h * 0.14, h * 0.14));
            }
            case "sparkle" -> {
                for (double[] q : new double[][]{{0, 0, 1}, {0.55, -0.55, 0.45}, {-0.55, 0.5, 0.35}}) {
                    double x = cx + q[0] * h, y = cy + q[1] * h, r = h * 0.55 * q[2];
                    Path2D p = new Path2D.Double();
                    p.moveTo(x, y - r); p.quadTo(x, y, x + r, y); p.quadTo(x, y, x, y + r); p.quadTo(x, y, x - r, y); p.quadTo(x, y, x, y - r);
                    g.fill(p);
                }
            }
            case "money" -> {
                g.draw(new Ellipse2D.Double(cx - h * 0.85, cy - h * 0.85, h * 1.7, h * 1.7));
                text(g, "$", (float) (cx - widthOf("$", sansBold, (int) (h * 1.1)) / 2), (float) (cy + h * 0.38), sansBold, (int) (h * 1.1), col);
            }
            case "clock" -> {
                g.draw(new Ellipse2D.Double(cx - h * 0.85, cy - h * 0.85, h * 1.7, h * 1.7));
                g.draw(new Line2D.Double(cx, cy, cx, cy - h * 0.5));
                g.draw(new Line2D.Double(cx, cy, cx + h * 0.35, cy + h * 0.2));
            }
            case "padlock" -> {   // glossy 3D padlock
                double bw = s * 0.62, bh = s * 0.5;
                g.setStroke(new BasicStroke((float) (s * 0.1), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(col.darker());
                g.draw(new Arc2D.Double(cx - bw * 0.32, cy - s * 0.5, bw * 0.64, s * 0.6, 0, 180, Arc2D.OPEN));
                g.draw(new Line2D.Double(cx - bw * 0.32, cy - s * 0.2, cx - bw * 0.32, cy - s * 0.05));
                g.draw(new Line2D.Double(cx + bw * 0.32, cy - s * 0.2, cx + bw * 0.32, cy - s * 0.05));
                g.setPaint(new GradientPaint((float) (cx - bw / 2), 0, col.brighter(), (float) (cx + bw / 2), 0, col.darker()));
                g.fill(new RoundRectangle2D.Double(cx - bw / 2, cy - s * 0.08, bw, bh, s * 0.1, s * 0.1));
                g.setColor(new Color(60, 40, 0, 160));
                g.fill(new Ellipse2D.Double(cx - s * 0.05, cy + s * 0.06, s * 0.1, s * 0.1));
            }
            case "cube" -> {   // wireframe isometric box (the reels' VM / container glyph)
                double a = h * 0.75, dy = h * 0.42;
                Path2D p = new Path2D.Double();
                double[][] top = {{0, -h * 0.85}, {a, -h * 0.85 + dy}, {0, -h * 0.85 + 2 * dy}, {-a, -h * 0.85 + dy}};
                p.moveTo(cx + top[0][0], cy + top[0][1]);
                for (double[] q : top) p.lineTo(cx + q[0], cy + q[1]);
                p.closePath();
                g.setStroke(new BasicStroke((float) Math.max(1.4, s * 0.018), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{3, 4}, 0));
                g.draw(p);
                double depth = h * 0.95;
                g.draw(new Line2D.Double(cx - a, cy - h * 0.85 + dy, cx - a, cy - h * 0.85 + dy + depth));
                g.draw(new Line2D.Double(cx + a, cy - h * 0.85 + dy, cx + a, cy - h * 0.85 + dy + depth));
                g.draw(new Line2D.Double(cx, cy - h * 0.85 + 2 * dy, cx, cy - h * 0.85 + 2 * dy + depth));
                g.draw(new Line2D.Double(cx - a, cy - h * 0.85 + dy + depth, cx, cy - h * 0.85 + 2 * dy + depth));
                g.draw(new Line2D.Double(cx + a, cy - h * 0.85 + dy + depth, cx, cy - h * 0.85 + 2 * dy + depth));
            }
            case "cpu" -> {
                g.draw(new RoundRectangle2D.Double(cx - h * 0.55, cy - h * 0.55, h * 1.1, h * 1.1, 8, 8));
                for (int i = -1; i <= 1; i++) {
                    double o = i * h * 0.3;
                    g.draw(new Line2D.Double(cx + o, cy - h * 0.55, cx + o, cy - h * 0.55));
                    g.draw(new Line2D.Double(cx + o, cy + h * 0.55, cx + o, cy + h * 0.55));
                    g.draw(new Line2D.Double(cx - h * 0.55, cy + o, cx - h * 0.85, cy + o));
                    g.draw(new Line2D.Double(cx + h * 0.55, cy + o, cx + h * 0.85, cy + o));
                }
            }
            default -> {
                g.draw(new RoundRectangle2D.Double(cx - h * 0.8, cy - h * 0.8, h * 1.6, h * 1.6, s * 0.2, s * 0.2));
                String letter = n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase(Locale.ROOT);
                int sz = (int) (h * 0.9);
                text(g, letter, (float) (cx - widthOf(letter, sansBold, sz) / 2), (float) (cy + sz * 0.36), sansBold, sz, col);
            }
        }
    }

    // ------------------------------------------------------------------ text helpers

    /** Light syntax colouring for JSON / config / shell / code lines (keys violet, strings, numbers, comments). */
    private void syntaxLine(Graphics2D g, String line, float x, float y, int size, int maxW) {
        String l = line.replace("\t", "    ");
        if (widthOf(l, mono, size) > maxW) l = fit(l, mono, size, maxW);
        Color base = new Color(0xD4D7DD), key = new Color(0xC79BF2), str = new Color(0xE9C46A), num = new Color(0x7FD1C7),
                comment = new Color(0x6B7480), kw = new Color(0x6CB6FF);
        String trimmed = l.trim();
        if (trimmed.startsWith("//") || trimmed.startsWith("#")) { text(g, l, x, y, mono, size, comment); return; }
        float cx = x;
        int i = 0;
        while (i < l.length()) {
            char c = l.charAt(i);
            int j;
            Color col;
            if (c == '"' || c == '\'') {
                j = l.indexOf(c, i + 1);
                j = j < 0 ? l.length() : j + 1;
                int k = j;
                while (k < l.length() && l.charAt(k) == ' ') k++;
                col = k < l.length() && l.charAt(k) == ':' ? key : str;
            } else if (Character.isDigit(c)) {
                j = i;
                while (j < l.length() && (Character.isDigit(l.charAt(j)) || l.charAt(j) == '.')) j++;
                col = num;
            } else if (Character.isLetter(c) || c == '_') {
                j = i;
                while (j < l.length() && (Character.isLetterOrDigit(l.charAt(j)) || l.charAt(j) == '_')) j++;
                String w = l.substring(i, j);
                col = KEYWORDS.contains(w) ? kw : base;
            } else {
                j = i + 1;
                col = base;
            }
            String part = l.substring(i, j);
            text(g, part, cx, y, mono, size, col);
            cx += widthOf(part, mono, size);
            i = j;
        }
    }

    private static final java.util.Set<String> KEYWORDS = java.util.Set.of("def", "return", "if", "else", "for", "while", "import", "from",
            "class", "public", "private", "static", "void", "new", "const", "let", "var", "function", "async", "await", "true", "false",
            "null", "None", "True", "False", "curl", "npx", "pip", "docker", "kubectl", "git", "SELECT", "FROM", "WHERE", "int", "String");

    private void centeredLines(Graphics2D g, String t, Rectangle2D r, Font f, int size, Color col) {
        int s = size;
        List<String> ls = wrap(t, f, s, (int) r.getWidth() - 20);
        while (ls.size() * s * 1.2 > r.getHeight() - 6 && s > 16) { s -= 2; ls = wrap(t, f, s, (int) r.getWidth() - 20); }
        float lh = s * 1.2f, y = (float) (r.getCenterY() - ls.size() * lh / 2 + s * 0.55);
        for (String l : ls) {
            text(g, l, (float) (r.getCenterX() - widthOf(l, f, s) / 2), y, f, s, col);
            y += lh;
        }
    }

    private void text(Graphics2D g, String t, float x, float y, Font f, int size, Color col) {
        if (t == null || t.isEmpty()) return;
        g.setColor(col);
        drawText(g, t, x, y, f, size, col);
    }

    /** col == null keeps the current paint (used for gradient headings). */
    private void drawText(Graphics2D g, String t, float x, float y, Font f, int size, Color col) {
        if (t == null || t.isEmpty()) return;
        if (col != null) g.setColor(col);
        new TextLayout(attributed(t, f, size).getIterator(), g.getFontRenderContext()).draw(g, x, y);
    }

    float widthOf(String t, Font f, int size) {
        if (t == null || t.isEmpty()) return 0;
        return new TextLayout(attributed(t, f, size).getIterator(), FRC).getAdvance();
    }

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
                if (alt == null) for (Font f : scriptFonts.values()) if (f.canDisplay(cp)) { alt = f; break; }
                if (alt == null && SYMBOLS.canDisplay(cp)) alt = SYMBOLS;
                if (alt != null) {
                    int j = i + len;
                    while (j < text.length() && !b.canDisplay(text.codePointAt(j))) j += Character.charCount(text.codePointAt(j));
                    as.addAttribute(TextAttribute.FONT, alt.deriveFont((float) size), i, j);
                    i = j;
                    continue;
                }
            }
            i += len;
        }
        return as;
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
                }
                if (cur.isEmpty()) cur.append(w); else { cur.setLength(0); cur.append(cand); }
            }
            if (!cur.isEmpty()) out.add(cur.toString());
        }
        return out;
    }

    private String fit(String t, Font f, int size, int maxW) {
        if (t == null) return "";
        if (widthOf(t, f, size) <= maxW) return t;
        String s = t;
        while (s.length() > 2 && widthOf(s + "\u2026", f, size) > maxW) s = s.substring(0, s.length() - 1);
        return s.trim() + "\u2026";
    }

    private static List<String> lines(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>(List.of(s.replace("\r", "").split("\n", -1)));
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) out.remove(out.size() - 1);
        return out;
    }

    private static int[] span(String s) {
        if (s == null) return null;
        var m = java.util.regex.Pattern.compile("(\\d+)\\s*-\\s*(\\d+)").matcher(s);
        if (m.find()) return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        m = java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*$").matcher(s);
        if (m.find()) { int a = Integer.parseInt(m.group(1)); return new int[]{a, a}; }
        return null;
    }

    private static String icon(Element e, String fallback) {
        return e.icon() == null || e.icon().isBlank() ? fallback : e.icon().toLowerCase(Locale.ROOT).trim();
    }

    private Color brighten(Color c) {
        if (light) return c.darker();
        return new Color(Math.min(255, c.getRed() + 50), Math.min(255, c.getGreen() + 50), Math.min(255, c.getBlue() + 50));
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(Math.max(lo, hi), v)); }
}
