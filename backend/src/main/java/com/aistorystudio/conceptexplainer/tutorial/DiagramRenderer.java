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
            "journey", "callout", "db", "server", "phone", "rows", "particles", "cables", "sql", "photo",
            // teaching devices (any topic, any look) - so lessons do not all look the same
            "timeline", "steps", "compare", "cycle", "layers", "venn", "meter", "checklist", "analogy", "matrix",
            "formula", "pie", "tree",
            // subject devices: physics, chemistry, biology, maths, exams / GK, and the kids section
            "forces", "wave", "circuit", "atom", "molecule", "reaction", "element", "pins", "numberline", "triangle",
            "fraction", "quiz", "factcard", "mascot", "speech", "badge",
            // age-specific: 2-5 (one big thing at a time, a friend who talks to the child) and 10-15 (game-style, respectful)
            "pip", "bigpic", "count", "choose", "sizes", "level", "challenge",
            // overlays for the illustrated kids scenes (cloud title banner, row of round icon badges)
            "cloudtitle", "iconrow",
            // diagrams for technology, science and business explainers
            "flowchart", "sequence", "datastruct", "periodic", "energy", "lens", "funnel");

    public static final List<String> ICONS = List.of("user", "agent", "robot", "plane", "hotel", "server", "database", "cloud",
            "brain", "gear", "terminal", "browser", "doc", "lock", "chart", "mail", "git", "code", "search", "bolt", "cart",
            "folder", "key", "globe", "phone", "check", "warning", "api", "sparkle", "money", "clock", "book", "cpu", "network",
            "chat", "shield", "list", "cube", "padlock",
            // health, body and everyday life
            "ear", "eye", "heart", "lungs", "tooth", "bone", "pill", "syringe", "stethoscope", "virus", "cell", "dna", "drop",
            "thermometer", "hospital", "stomach", "kidney", "muscle", "apple", "leaf", "sun", "water", "wind", "gauge", "plus",
            "baby", "home", "car", "food", "person", "family", "bed", "mask", "microscope", "bacteria", "moon");

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
    public static final List<String> THEMES = List.of("reference", "neon", "sketchnote", "storyboard", "chalkboard", "blueprint", "anime", "reel", "blueprint3d", "clinical", "kids", "toddler", "teen");

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
            case "toddler" -> {   // ages 2-5: calm cream canvas, a few saturated primary colours, nothing in the background
                set(0xFFF6E5, 0xFFFDF7, 0x2A2238, 0x6B6480, 0xF0436C, 0x7A4DFF, 0x1E90FF, 0xFFFFFF, 0x2A2238, 0x2A2238,
                        0x22B455, 0x15873C, 0x1E90FF, 0xFFB000, 0xFFFFFF, 0x7A4DFF, 0xF0436C, 0xFFB000);
                light = true;
                if (kidBold != null) { sansBold = kidBold; headFont = kidBold; sans = kidBold; sansItalic = kidBold; }
            }
            case "teen" -> {   // ages 10-15: dark game-HUD look, neon accents - grown-up, not babyish
                set(0x0D0B1E, 0x1A1440, 0xF2F0FF, 0x9C96C8, 0xFF4FD8, 0x9D7BFF, 0x3BE8FF, 0x16123A, 0x5B4FC0, 0xF2F0FF,
                        0x3DFFA2, 0x9CFFD0, 0x3BE8FF, 0x3DFFA2, 0x16123A, 0xFF4FD8, 0x3BE8FF, 0xFF4FD8);
                glow = true; bloomAmount = 0.28;
                if (grotesk != null) { sans = groteskMedium != null ? groteskMedium : grotesk; sansBold = grotesk; headFont = grotesk; sansItalic = sans; }
                if (jbMono != null) mono = jbMono;
            }
            case "kids" -> {   // bright, warm, rounded: big friendly letters, one colour per idea, soft confetti dots
                set(0xFFF8EC, 0xFFFFFF, 0x2B2340, 0x6B6480, 0xFF5D8F, 0x8A5CFF, 0x1EA7FD, 0xFFFFFF, 0x2B2340, 0x2B2340,
                        0x2DBE6C, 0x1E8F50, 0x1EA7FD, 0xFF8A00, 0xFFFFFF, 0x8A5CFF, 0xFF5D8F, 0x8A5CFF);
                light = true;
                if (kidBold != null) { sansBold = kidBold; headFont = kidBold; sans = kidRegular != null ? kidRegular : kidBold; sansItalic = sans; }
            }
            case "clinical" -> {   // health & biology: calm white-mint, teal structure, coral for what hurts or warns
                set(0xF3FAF8, 0xFFFFFF, 0x173B45, 0x5E7A82, 0xE4572E, 0x7B6CD9, 0x1F9AA8, 0xE3F4F2, 0x1F9AA8, 0x173B45,
                        0x2FA36B, 0x237F53, 0x1F9AA8, 0x1F9AA8, 0xFFFFFF, 0x1F6F7A, 0x1F9AA8, 0x2FA36B);
                light = true;
            }
            case "blueprint3d" -> {   // real-world breakdown: deep navy 3D world, glowing parts, yellow highlights
                set(0x0B1C3A, 0x14366C, 0xEAF1FF, 0x8DA4C8, 0xFF5A6A, 0x9DB7FF, 0x7FB8FF, 0x0D2246, 0x7F9FD6, 0xEAF1FF,
                        0x5BE38C, 0x8EF0B0, 0x7FB8FF, 0xFFC83D, 0x0C1F40, 0xFFC83D, 0xFFC83D, 0xFFE08A);
                blueprint = true; glow = true; bloomAmount = 0.32;
                if (grotesk != null) { sans = groteskMedium != null ? groteskMedium : grotesk; sansBold = grotesk; headFont = grotesk; sansItalic = sans; }
                if (jbMono != null) mono = jbMono;
            }
            case "reel" -> {   // both reference reels: near-black, monochrome UI cards, green / cyan / red accents
                set(0x0B0B0C, 0x20242C, 0xF4F4F5, 0x8B8B92, 0xF87171, 0xA78BFA, 0x38BDF8, 0x18181B, 0x3F3F46, 0xF4F4F5,
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
        if (light) HL = new Color(0xC98200);   // the bright yellow vanishes on white
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
    private final Font baseSans, baseBold, baseItalic, baseMono, hand, handBold, pop, grotesk, groteskMedium, jbMono, kidBold, kidRegular;
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
        kidBold = load("Sniglet-ExtraBold.ttf", null);
        kidRegular = load("Sniglet-Regular.ttf", null);
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
        overlayOnly = false;
        setCanvas(vertical);
        applyTheme(styleId);
        int n = Math.max(1, scene.sentences());
        Map<String, Rectangle2D> bounds = layoutFor(scene);
        List<BufferedImage> out = new ArrayList<>();
        for (int k = 0; k < n; k++) out.add(frame(scene, bounds, k));
        return out;
    }

    private boolean overlayOnly;

    /**
     * Illustrated scenes: only the overlays (titles, cards, quizzes, speech bubbles) on a TRANSPARENT canvas, one frame per
     * sentence. The illustration itself is animated separately underneath (slow pan / zoom), so the camera can move across
     * the picture while the overlays stay put.
     */
    public synchronized List<BufferedImage> renderOverlays(Scene scene, String styleId, boolean vertical) {
        setCanvas(vertical);
        applyTheme(styleId);
        overlayOnly = true;
        try {
            int n = Math.max(1, scene.sentences());
            Map<String, Rectangle2D> bounds = layoutFor(scene);
            List<BufferedImage> out = new ArrayList<>();
            for (int k = 0; k < n; k++) out.add(frame(scene, bounds, k));
            return out;
        } finally {
            overlayOnly = false;
        }
    }

    /** A copy of the scene with a full-screen picture (e.g. the still of a Manim animation) behind everything else. */
    public static Scene withBackdrop(Scene scene, String path) {
        List<Element> els = new ArrayList<>();
        els.add(new Element("manim-backdrop", "photo", "", null, null, null, List.of(), path, null, null, "full", 50, 50, 0, 0, 0, -1, List.of()));
        els.addAll(scene.elements());
        return new Scene(scene.heading(), els, scene.sentences(), scene.label());
    }

    /** The scene's full-screen picture file, if it has one (illustrated kids scenes). */
    public static String backdropPath(Scene scene) {
        for (Element e : scene.elements())
            if (underlay(e) && e.extra() != null && !e.extra().isBlank() && Files.isRegularFile(Path.of(e.extra().trim()))) return e.extra().trim();
        return null;
    }

    // ------------------------------------------------------------------ layout

    /** Pixel bounds of every non-arrow element (needed before drawing so arrows can attach). */
    /** True while a scene WITH a heading is laid out: its content must stay below the heading / label (the camera frames the same zone). */
    private boolean reserveHeader;

    private double headerBottom() { return portrait ? 330 : 130; }

    /** Layout for a whole scene: content stays clear of the heading, except in illustrated scenes where overlays are placed on purpose. */
    Map<String, Rectangle2D> layoutFor(Scene sc) {
        boolean head = !nz(sc.heading()).isEmpty() || !nz(sc.label()).isEmpty();
        boolean illustrated = false;
        for (Element e : sc.elements()) if (underlay(e)) { illustrated = true; break; }
        reserveHeader = head && !illustrated;
        try {
            return layout(sc.elements());
        } finally {
            reserveHeader = false;
        }
    }

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
            double minY = reserveHeader && !full(e) && !backdrop(e) ? headerBottom() : 30;
            double maxY = (full(e) ? H - 30 : contentBottom()) - sz[1];
            double y = e.y() > 100 ? cy - sz[1] / 2 : clamp(cy - sz[1] / 2, minY, Math.max(minY, maxY));
            if (e.type().equals("photo") && !nz(e.style()).toLowerCase(Locale.ROOT).contains("framed")) { x = 0; y = 0; sz = new double[]{W, H}; }
            else if (backdrop(e) && e.w() <= 0) { x = 0; y = 0; }     // full-bleed picture
            if (e.type().equals("journey")) { x = 60; y = portrait ? 175 : 110; sz = new double[]{W - 120, 80}; }   // HUD: always at the top
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
    private static boolean backdrop(Element e) { return e.type().equals("photo") || e.type().equals("particles") || e.type().equals("pins") || hud(e); }

    /** Screen-space UI (the step tracker): never moved by the camera. */
    private static boolean hud(Element e) { return e.type().equals("journey"); }

    /** Full-screen pictures: drawn first, in screen space, never moved by the camera. */
    private static boolean underlay(Element e) {
        return e.type().equals("photo") && (e.style() == null || !e.style().toLowerCase(Locale.ROOT).contains("framed"));
    }

    private void drawUnderlay(Graphics2D g, List<Element> els, java.util.function.ToDoubleFunction<Element> alpha) {
        for (Element e : els) {
            if (!underlay(e)) continue;
            float a = (float) alpha.applyAsDouble(e);
            if (a <= 0.004f) continue;
            var old = g.getComposite();
            if (a < 0.999f) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
            photo(g, e, new Rectangle2D.Double(0, 0, W, H));
            g.setComposite(old);
        }
    }

    private static boolean full(Element e) { return e.type().equals("titlecard") || e.type().equals("outro"); }

    private void resolveOverlaps(List<Element> els, Map<String, Rectangle2D> b) {
        List<Rectangle2D> placed = new ArrayList<>();
        for (Element e : els) {
            Rectangle2D r = b.get(e.id());
            if (r == null || e.type().equals("container") || backdrop(e)) continue;
            // First preserve the author/LLM placement where possible, nudging down and then right.
            for (int guard = 0; guard < 80 && overlapsAny(r, placed, 5); guard++) {
                Rectangle2D hit = firstOverlap(r, placed, 5);
                if (hit == null) break;
                double ny = hit.getMaxY() + 22;
                double bottom = Math.max(H - 30, Math.min(1.8 * H, contentBottom()));
                if (ny + r.getHeight() <= bottom) {
                    r = new Rectangle2D.Double(r.getX(), ny, r.getWidth(), r.getHeight());
                } else {
                    double nx = hit.getMaxX() + 24;
                    if (nx + r.getWidth() <= 1.8 * W) r = new Rectangle2D.Double(nx, r.getY(), r.getWidth(), r.getHeight());
                    else break;
                }
            }
            // A finite nudge loop can still leave a collision (e.g. several oversized cards).
            // Deterministically search a roomy grid before accepting an overlap. The camera frames
            // world-space layouts, so elements may sit beyond the initial viewport but not overlap.
            if (overlapsAny(r, placed, 5)) {
                Rectangle2D candidate = findFreePlacement(r, placed);
                if (candidate != null) r = candidate;
            }
            b.put(e.id(), r);
            placed.add(r);
        }
        // Keep content wholly inside or outside a container instead of straddling its border.
        for (Element c : els) {
            if (!c.type().equals("container")) continue;
            Rectangle2D cr = b.get(c.id());
            if (cr == null) continue;
            Rectangle2D inner = new Rectangle2D.Double(cr.getX() + 16, cr.getY() + 64, Math.max(1, cr.getWidth() - 32), Math.max(1, cr.getHeight() - 80));
            for (Element e : els) {
                if (e == c || e.type().equals("container") || e.type().equals("arrow") || backdrop(e)) continue;
                Rectangle2D r = b.get(e.id());
                if (r == null || !r.intersects(cr) || inner.contains(r)) continue;
                Rectangle2D ix = r.createIntersection(cr);
                boolean inside = area(ix) > area(r) * 0.5 && r.getWidth() <= inner.getWidth() && r.getHeight() <= inner.getHeight();
                double x = r.getX(), y = r.getY();
                if (inside) {
                    x = Math.max(inner.getX(), Math.min(inner.getMaxX() - r.getWidth(), x));
                    y = Math.max(inner.getY(), Math.min(inner.getMaxY() - r.getHeight(), y));
                } else {
                    double left = r.getMaxX() - cr.getX() + 20, right = cr.getMaxX() - r.getX() + 20,
                           up = r.getMaxY() - cr.getY() + 20, down = cr.getMaxY() - r.getY() + 20;
                    double m = Math.min(Math.min(left, right), Math.min(up, down));
                    if (m == left) x -= left; else if (m == right) x += right; else if (m == up) y -= up; else y += down;
                }
                b.put(e.id(), new Rectangle2D.Double(x, y, r.getWidth(), r.getHeight()));
            }
        }
    }

    private Rectangle2D firstOverlap(Rectangle2D r, List<Rectangle2D> placed, double inset) {
        for (Rectangle2D p : placed) {
            double rw = Math.max(1, r.getWidth() - inset * 2), rh = Math.max(1, r.getHeight() - inset * 2);
            if (p.intersects(r.getX() + inset, r.getY() + inset, rw, rh)) return p;
        }
        return null;
    }

    private boolean overlapsAny(Rectangle2D r, List<Rectangle2D> placed, double inset) {
        return firstOverlap(r, placed, inset) != null;
    }

    private Rectangle2D findFreePlacement(Rectangle2D original, List<Rectangle2D> placed) {
        double minY = reserveHeader ? headerBottom() : 30;
        double maxX = Math.max(W * 1.65, original.getWidth() + 60);
        double maxY = Math.max(H * 1.65, original.getHeight() + minY + 30);
        double stepX = Math.max(36, Math.min(72, original.getWidth() / 5));
        double stepY = Math.max(30, Math.min(64, original.getHeight() / 4));
        // Search row-major positions close to the existing layout first, then expand to the right/down.
        for (double y = minY; y + original.getHeight() <= maxY; y += stepY) {
            for (double x = 30; x + original.getWidth() <= maxX; x += stepX) {
                Rectangle2D candidate = new Rectangle2D.Double(x, y, original.getWidth(), original.getHeight());
                if (!overlapsAny(candidate, placed, 5)) return candidate;
            }
        }
        return null; // Preserve the source geometry rather than silently dropping an element.
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
                double body = st.contains("stat") ? statHeight(t, size, (int) cw - 60) : richLines(t, sansBold, size, (int) cw - 60).size() * size * 1.18;
                double extra = listOrEmpty(e).size() * 58 + (nz(e.title()).isEmpty() ? 0 : 44);
                return new double[]{cw, h > 0 ? h : 56 + body + extra};
            }
            case "db" -> { double d = w > 0 ? w : 260; return new double[]{d, h > 0 ? h : d * 1.25 + 70}; }
            case "server" -> { double d = w > 0 ? w : 200; return new double[]{d, h > 0 ? h : d * 1.7 + 50}; }
            case "phone" -> { double d = w > 0 ? w : (portrait ? 420 : 360); return new double[]{d, h > 0 ? h : d * 2.05}; }
            case "rows" -> { int n = Math.max(1, listOrEmpty(e).size()); return new double[]{w > 0 ? w : 640, h > 0 ? h : n * 84 + 10}; }
            case "particles" -> { return new double[]{w > 0 ? w : W - 80, h > 0 ? h : 520}; }
            case "sql" -> { int n = Math.max(1, lines(e.extra()).size()); return new double[]{w > 0 ? w : W - 160, h > 0 ? h : 70 + n * 42 + (nz(e.title()).isEmpty() ? 0 : 36)}; }
            case "photo" -> { return new double[]{w > 0 ? w : W, h > 0 ? h : H}; }
            case "timeline" -> { int n = Math.max(2, listOrEmpty(e).size());
                if (portrait) return new double[]{w > 0 ? w : Math.min(W - 100, 900), h > 0 ? h : Math.min(1000, 90 + n * 170.0)};   // vertical: a tall frame
                return new double[]{w > 0 ? w : W * 0.86, h > 0 ? h : 230}; }
            case "steps" -> {
                int n = Math.max(1, listOrEmpty(e).size());
                boolean vert = nz(e.style()).toLowerCase(Locale.ROOT).contains("vert") || portrait;
                double cw = 0;   // vertical: as wide as the longest title / detail, so the list sits centred
                for (String l : listOrEmpty(e)) cw = Math.max(cw, Math.max(widthOf(part(l, 0), sansBold, 32), widthOf(part(l, 1), sans, 24)));
                return vert ? new double[]{w > 0 ? w : Math.min(W - 120, cw + 130), h > 0 ? h : n * 104 + 10}
                            : new double[]{w > 0 ? w : Math.min(W - 120, n * 300.0), h > 0 ? h : 250};
            }
            case "compare" -> { int n = Math.max(1, listOrEmpty(e).size()); return new double[]{w > 0 ? w : Math.min(W - 100, portrait ? 980 : 1300), h > 0 ? h : 96 + n * 62}; }
            case "cycle" -> { double d = w > 0 ? w : (portrait ? 760 : 560); return new double[]{d, h > 0 ? h : d}; }
            case "layers" -> { int n = Math.max(1, listOrEmpty(e).size()); return new double[]{w > 0 ? w : Math.min(W - 120, 820), h > 0 ? h : n * 92 + 10}; }
            case "venn" -> { double d = w > 0 ? w : (portrait ? 860 : 720); return new double[]{d, h > 0 ? h : d * 0.72}; }
            case "meter" -> { double d = w > 0 ? w : 420; return new double[]{d, h > 0 ? h : d * 0.72}; }
            case "checklist" -> { int n = Math.max(1, listOrEmpty(e).size()); return new double[]{w > 0 ? w : Math.min(W - 120, 760), h > 0 ? h : 30 + n * 66 + (nz(e.title()).isEmpty() ? 0 : 56)}; }
            case "analogy" -> { return new double[]{w > 0 ? w : Math.min(W - 80, portrait ? 920 : 1100), h > 0 ? h : portrait ? 760 : 330}; }
            case "matrix" -> { double d = w > 0 ? w : (portrait ? 880 : 700); return new double[]{d, h > 0 ? h : d * 0.8}; }
            case "formula" -> { int n = listOrEmpty(e).size(); return new double[]{w > 0 ? w : Math.min(W - 100, 1000), h > 0 ? h : 170 + n * 50}; }
            case "pie" -> { double d = w > 0 ? w : (portrait ? 820 : 760); return new double[]{d, h > 0 ? h : Math.max(d * 0.55, 60 + listOrEmpty(e).size() * 56)}; }
            case "tree" -> { return new double[]{w > 0 ? w : Math.min(W - 100, 1100), h > 0 ? h : 420}; }
            case "forces" -> { double d = w > 0 ? w : 620; return new double[]{d, h > 0 ? h : d * 0.8}; }
            case "wave" -> { return new double[]{w > 0 ? w : Math.min(W - 120, 900), h > 0 ? h : 320}; }
            case "circuit" -> { return new double[]{w > 0 ? w : 720, h > 0 ? h : 440}; }
            case "atom" -> { double d = w > 0 ? w : 520; return new double[]{d, h > 0 ? h : d}; }
            case "molecule" -> { return new double[]{w > 0 ? w : 640, h > 0 ? h : 420}; }
            case "reaction" -> { return new double[]{w > 0 ? w : Math.min(W - 100, 1100), h > 0 ? h : 200}; }
            case "element" -> { double d = w > 0 ? w : 260; return new double[]{d, h > 0 ? h : d * 1.15}; }
            case "pins" -> { return new double[]{w > 0 ? w : 10, h > 0 ? h : 10}; }
            case "numberline" -> { return new double[]{w > 0 ? w : Math.min(W - 120, 1000), h > 0 ? h : 200}; }
            case "triangle" -> { double d = w > 0 ? w : 560; return new double[]{d, h > 0 ? h : d * 0.8}; }
            case "fraction" -> { int n = Math.max(1, listOrEmpty(e).size()); return new double[]{w > 0 ? w : Math.min(W - 120, 900), h > 0 ? h : 30 + n * 110}; }
            case "quiz" -> { return new double[]{w > 0 ? w : Math.min(W - 80, portrait ? 960 : 1100), h > 0 ? h : 180 + Math.max(2, listOrEmpty(e).size()) * 86}; }
            case "factcard" -> { return new double[]{w > 0 ? w : Math.min(W - 100, 900), h > 0 ? h : 300}; }
            case "mascot" -> { double d = w > 0 ? w : 280; return new double[]{d, h > 0 ? h : d * 1.15}; }
            case "speech" -> {
                double sw = w > 0 ? w : Math.min(W * 0.6, Math.max(320, widthOf(t, sansBold, 36) * 0.62 + 90));
                return new double[]{sw, h > 0 ? h : 70 + wrap(t, sansBold, 36, (int) sw - 60).size() * 46};
            }
            case "badge" -> { double d = w > 0 ? w : 300; return new double[]{d, h > 0 ? h : d}; }
            case "pip" -> { double d = w > 0 ? w : 300; return new double[]{d, h > 0 ? h : d * 1.05}; }
            case "bigpic" -> { double d = w > 0 ? w : Math.min(W, H) * (portrait ? 0.62 : 0.5); return new double[]{d, h > 0 ? h : d + 120}; }
            case "count" -> { int n = (int) Math.max(1, Math.min(10, parseNum(nz(e.text())))); double c = Math.min(280, (W - 160.0) / n);
                return new double[]{w > 0 ? w : c * n, h > 0 ? h : c + 120}; }
            case "choose" -> { int n = Math.max(2, listOrEmpty(e).size()); return new double[]{w > 0 ? w : Math.min(W - 100, n * 360.0), h > 0 ? h : 420}; }
            case "sizes" -> { return new double[]{w > 0 ? w : Math.min(W - 100, 900), h > 0 ? h : 460}; }
            case "level" -> { return new double[]{w > 0 ? w : Math.min(W - 100, 900), h > 0 ? h : 150}; }
            case "flowchart" -> { int[] fl = flowShape(e); return new double[]{w > 0 ? w : Math.min(W - 100, Math.max(600, fl[1] * 340.0)), h > 0 ? h : Math.max(240, fl[0] * 156.0 + 40)}; }
            case "sequence" -> { int n = Math.max(2, listOrEmpty(e).size()); int m = Math.max(1, nz(e.text()).isEmpty() ? 1 : nz(e.text()).split(",").length);
                return new double[]{w > 0 ? w : Math.min(W - 100, n * 320.0 + 60), h > 0 ? h : 170 + m * 92.0}; }
            case "datastruct" -> { String st = nz(e.style()).toLowerCase(Locale.ROOT); int n = Math.max(1, listOrEmpty(e).size());
                if (st.startsWith("stack")) return new double[]{w > 0 ? w : 460, h > 0 ? h : n * 88.0 + 110};
                if (st.startsWith("hash")) return new double[]{w > 0 ? w : Math.min(W - 100, 1000), h > 0 ? h : n * 76.0 + 50};
                return new double[]{w > 0 ? w : Math.min(W - 100, Math.max(560, n * (st.startsWith("linked") ? 240.0 : 160.0))), h > 0 ? h : st.startsWith("linked") ? 240 : 260}; }
            case "periodic" -> { double d = w > 0 ? w : Math.min(W - 60, 1260); return new double[]{d, h > 0 ? h : d * 0.56}; }
            case "energy" -> { return new double[]{w > 0 ? w : Math.min(W - 100, 860), h > 0 ? h : 420}; }
            case "lens" -> { return new double[]{w > 0 ? w : Math.min(W - 100, 980), h > 0 ? h : 430}; }
            case "funnel" -> { int n = Math.max(2, listOrEmpty(e).size()); return new double[]{w > 0 ? w : Math.min(W - 100, 960), h > 0 ? h : n * 86.0 + 20}; }
            case "cloudtitle" -> {
                double cw = w > 0 ? w : Math.min(W * 0.86, Math.max(widthOf(t, kidOr(sansBold), 84), widthOf(nz(e.title()), kidOr(sansBold), 44)) + 200);
                return new double[]{cw, h > 0 ? h : (nz(e.title()).isEmpty() ? 190 : 250)};
            }
            case "iconrow" -> { int n = Math.max(1, listOrEmpty(e).size()); return new double[]{w > 0 ? w : Math.min(W - 60, n * 210.0), h > 0 ? h : 230}; }
            case "challenge" -> {
                double cw = w > 0 ? w : Math.min(W - 80, 1000);
                int q = wrap(nz(e.title()), sansBold, 40, (int) cw - 68).size();
                return new double[]{cw, h > 0 ? h : 140 + q * 48 + listOrEmpty(e).size() * 70};
            }
            case "outro" -> { return new double[]{W * 0.86, h > 0 ? h : 300}; }
            default -> { return new double[]{w > 0 ? w : 300, h > 0 ? h : 80}; }
        }
    }

    // ------------------------------------------------------------------ frame

    private BufferedImage frame(Scene scene, Map<String, Rectangle2D> bounds, int k) {
        double[] cam = camera(scene, bounds);
        BufferedImage img = new BufferedImage(W, H, overlayOnly ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            hints(g);
            if (!overlayOnly) {
                background(g);
                drawUnderlay(g, scene.elements(), e -> e.at() <= k ? 1f : 0f);
            }
            Graphics2D cg = (Graphics2D) g.create();
            try {
                applyCamera(cg, cam);
                drawLayers(cg, scene.elements(), bounds, e -> e.at() <= k ? 1f : 0f, k);
            } finally {
                cg.dispose();
            }
            headingText(g, scene.heading(), scene.label(), 1f);   // on top of everything (full-screen photos included)
            drawHud(g, scene.elements(), bounds, e -> e.at() <= k ? 1f : 0f, k);
        } finally {
            g.dispose();
        }
        return glow && !overlayOnly ? bloom(img) : img;
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
        for (Element e : scene.elements()) if (underlay(e)) return identity();   // illustrated scenes: overlays stay exactly where placed
        boolean fits = box.getX() >= left - 1 && box.getMaxX() <= right + 1 && box.getY() >= top - 40 && box.getMaxY() <= bottom + 1;
        if (fits && !blueprint) {
            // reels: a small diagram floating in a big empty frame looks unfinished - frame it closer
            boolean onlyCards = true;
            for (Element e : scene.elements()) if (!full(e) && !backdrop(e)) { onlyCards = false; break; }
            double zin = Math.min(portrait ? 1.6 : 1.3, Math.min((right - left - 40) / box.getWidth(), (bottom - top - 40) / box.getHeight()));
            if (!onlyCards && zin > 1.08) return new double[]{zin, box.getCenterX(), box.getCenterY(), W / 2.0, (top + bottom) / 2.0};
            return identity();
        }
        if (fits) return identity();
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
        Map<String, Rectangle2D> b1 = layoutFor(scene);
        double[] cam1 = camera(scene, b1);
        Map<String, Rectangle2D> b0 = prev == null ? Map.of() : layoutFor(prev);
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
                {
                    final double lf = lin;
                    if (prev != null) drawUnderlay(g, prev.elements(), e -> now.contains(e.id()) ? 0f : (float) Math.max(0, 1 - lf / 0.6));
                    drawUnderlay(g, scene.elements(), e -> e.at() > 0 ? 0f : before.contains(e.id()) ? 1f : (float) Math.min(1, lf / 0.6));
                }
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
                {
                    final float inH = (float) Math.max(0, Math.min(1, (lin - 0.3) / 0.5));
                    if (prev != null) drawHud(g, prev.elements(), b0, e -> now.contains(e.id()) ? 0f : (float) Math.max(0, 1 - lin / 0.45), Math.max(0, prev.sentences() - 1));
                    drawHud(g, scene.elements(), bi, e -> e.at() > 0 ? 0f : before.contains(e.id()) ? 1f : inH, -1);
                }
                if (h0.equals(h1) && l0.equals(l1)) headingText(g, h1, l1, 1f);
                else {
                    headingText(g, h0, l0, (float) Math.max(0, 1 - lin / 0.45));
                    headingText(g, h1, l1, (float) Math.max(0, Math.min(1, (lin - 0.45) / 0.45)));
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
    private boolean hudPass;

    /** The step tracker etc., drawn without the camera transform so they stay pinned to the top. */
    private void drawHud(Graphics2D g, List<Element> els, Map<String, Rectangle2D> bounds, java.util.function.ToDoubleFunction<Element> alpha, int k) {
        List<Element> hudEls = new ArrayList<>();
        for (Element e : els) if (hud(e)) hudEls.add(e);
        if (hudEls.isEmpty()) return;
        hudPass = true;
        try { drawLayers(g, hudEls, bounds, alpha, k); } finally { hudPass = false; }
    }

    private final List<Object[]> pendingPins = new ArrayList<>();

    private void drawLayers(Graphics2D g, List<Element> els, Map<String, Rectangle2D> bounds,
                            java.util.function.ToDoubleFunction<Element> alpha, int k) {
        pendingLabels.clear();
        pendingPins.clear();
        try {
            drawPasses(g, els, bounds, alpha, k);
        } finally {
            for (Object[] pp : pendingPins) {
                var old = g.getComposite();
                if ((float) pp[1] < 0.999f) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) pp[1]));
                pinsOverlay(g, (Element) pp[0], bounds);
                g.setComposite(old);
            }
            pendingPins.clear();
            flushLabels(g, bounds);
        }
    }

    private int curStep = Integer.MAX_VALUE;

    private void drawPasses(Graphics2D g, List<Element> els, Map<String, Rectangle2D> bounds,
                            java.util.function.ToDoubleFunction<Element> alpha, int k) {
        curStep = k < 0 ? 0 : k;
        for (Element e : els) {   // labelled pins sit on top of the picture / shape they point into
            if (!e.type().equals("pins")) continue;
            float a = (float) alpha.applyAsDouble(e);
            if (a <= 0.004f) continue;
            pendingPins.add(new Object[]{e, a});
        }
        for (int pass = 0; pass < 3; pass++) {
            for (Element e : els) {
                float a = (float) alpha.applyAsDouble(e);
                if (a <= 0.004f || (hud(e) && !hudPass) || underlay(e)) continue;
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
            // screen blend: glow lifts dark areas but never flattens already-bright detail (phone screens, cards) to white
            int r = screen((a[i] >> 16) & 255, (int) (((b[i] >> 16) & 255) * k));
            int gg = screen((a[i] >> 8) & 255, (int) (((b[i] >> 8) & 255) * k));
            int bb = screen(a[i] & 255, (int) ((b[i] & 255) * k));
            a[i] = (r << 16) | (gg << 8) | bb;
        }
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, W, H, a, 0, W);
        return out;
    }

    private static int screen(int a, int b) { return 255 - (255 - a) * (255 - Math.min(255, b)) / 255; }

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
        if (themeId.equals("teen")) {
            g.drawImage(gradientBackground("teen"), 0, 0, null);        // diagonal gradient + pink glow, dithered
            g.setStroke(new BasicStroke(1f));
            for (int x = 0; x < W; x += 60) { g.setColor(new Color(157, 123, 255, x % 300 == 0 ? 26 : 10)); g.drawLine(x, 0, x, H); }
            for (int y = 0; y < H; y += 60) { g.setColor(new Color(157, 123, 255, y % 300 == 0 ? 26 : 10)); g.drawLine(0, y, W, y); }
            return;
        }
        if (light) {   // light themes: a gentle top-to-bottom wash (a radial lift showed as rings after compression)
            g.drawImage(gradientBackground("wash"), 0, 0, null);        // the wash is dithered so it does not show as stripes
            if (themeId.equals("kids")) {   // soft confetti around the edges, never behind the middle
                java.util.Random rnd = new java.util.Random(11);
                Color[] cs = {PINK, VIOLET, CYAN, GREEN, ARROW};
                for (int i = 0; i < 26; i++) {
                    double x = rnd.nextDouble() * W, y = rnd.nextDouble() * H;
                    if (Math.abs(x - W / 2.0) < W * 0.3 && Math.abs(y - H / 2.0) < H * 0.3) continue;
                    Color c = cs[i % cs.length];
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 55));
                    double d = 10 + rnd.nextDouble() * 22;
                    if (i % 3 == 0) g.fill(new RoundRectangle2D.Double(x, y, d, d * 0.45, 6, 6)); else g.fill(new Ellipse2D.Double(x, y, d, d));
                }
            }
            return;
        }
        // very soft lift in the middle, like the reference's slight vignette (dithered: an 8-bit radial gradient shows as rings)
        g.drawImage(gradientBackground("lift"), 0, 0, null);
        if (grid) { // blueprint grid paper
            g.setStroke(new BasicStroke(1f));
            for (int x = 0; x < W; x += 40) { g.setColor(new Color(255, 255, 255, x % 200 == 0 ? 34 : 14)); g.drawLine(x, 0, x, H); }
            for (int y = 0; y < H; y += 40) { g.setColor(new Color(255, 255, 255, y % 200 == 0 ? 34 : 14)); g.drawLine(0, y, W, y); }
        }
    }

    /**
     * Strength of the dither in grey levels (1 = +-1 level). 2 is what survives the two x264 encodes of a lesson: measured on every look, 1
     * left about 10 flat bands per row after encoding, 2 with -aq-mode 3 (see ConceptExplainerService) left none. Tunable with -Dstudio.bg.noise.
     */
    private static final double BG_NOISE = Double.parseDouble(System.getProperty("studio.bg.noise", "2.0"));
    private BufferedImage bgCache;
    private String bgKey = "";

    /**
     * Smooth backgrounds computed in floating point and dithered with +-1 level of static noise before rounding. Painting these with
     * Java2D gradients gives 8-bit steps that read as rings or stripes (worse after video compression); the noise breaks the steps up.
     * The noise is the same on every frame (a fixed seed), so it never flickers, and the image is built once per size and look.
     * kinds: lift = a soft glow in the middle of the look's background colour, wash = top-to-bottom, teen = diagonal plus a pink corner glow.
     */
    private BufferedImage gradientBackground(String kind) {
        String key = kind + "|" + W + "x" + H + "|" + BG.getRGB() + "|" + BG_GLOW.getRGB();
        if (bgCache != null && key.equals(bgKey)) return bgCache;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(42);
        double[] bg = {BG.getRed(), BG.getGreen(), BG.getBlue()}, gl = {BG_GLOW.getRed(), BG_GLOW.getGreen(), BG_GLOW.getBlue()};
        double[] t0 = {0x1A, 0x14, 0x40}, t1 = {0x08, 0x06, 0x14}, pink = {255, 79, 216};
        // a background that is genuinely one flat colour gets no grain at all
        boolean flat = !kind.equals("teen") && Math.abs(bg[0] - gl[0]) < 0.5 && Math.abs(bg[1] - gl[1]) < 0.5 && Math.abs(bg[2] - gl[2]) < 0.5;
        int[] row = new int[W];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double[] c = new double[3];
                switch (kind) {
                    case "wash" -> { double t = y / (double) H; for (int k = 0; k < 3; k++) c[k] = gl[k] + (bg[k] - gl[k]) * t; }
                    case "teen" -> {
                        double t = (x * (double) W + y * (double) H) / ((double) W * W + (double) H * H);
                        double d = Math.hypot(x - W * 0.85, y - H * 0.1) / (W * 0.6), a = d >= 1 ? 0 : (50 / 255.0) * (1 - d);
                        for (int k = 0; k < 3; k++) { double base = t0[k] + (t1[k] - t0[k]) * t; c[k] = base * (1 - a) + pink[k] * a; }
                    }
                    default -> {      // lift: alpha 150 at the centre fading to 0 at 0.7 W
                        double d = Math.hypot(x - W * 0.5, y - H * 0.45) / (W * 0.7), a = d >= 1 ? 0 : (150 / 255.0) * (1 - d);
                        for (int k = 0; k < 3; k++) c[k] = bg[k] * (1 - a) + gl[k] * a;
                    }
                }
                // triangular noise in (-1, 1) levels shared by the three channels, at full strength on every look (measured: weakening it on
                // bright looks brought their bands back after encoding)
                double n = flat ? 0 : (rnd.nextDouble() + rnd.nextDouble() - 1.0) * BG_NOISE;
                int r = (int) Math.max(0, Math.min(255, Math.round(c[0] + n))), gg = (int) Math.max(0, Math.min(255, Math.round(c[1] + n))),
                        b = (int) Math.max(0, Math.min(255, Math.round(c[2] + n)));
                row[x] = (r << 16) | (gg << 8) | b;
            }
            img.setRGB(0, y, W, 1, row, 0, W);
        }
        bgCache = img;
        bgKey = key;
        return img;
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
            case "timeline" -> timeline(g, e, r);
            case "steps" -> steps(g, e, r);
            case "compare" -> compare(g, e, r);
            case "cycle" -> cycle(g, e, r);
            case "layers" -> layers(g, e, r);
            case "venn" -> venn(g, e, r);
            case "meter" -> meter(g, e, r);
            case "checklist" -> checklist(g, e, r);
            case "analogy" -> analogy(g, e, r);
            case "matrix" -> matrix(g, e, r);
            case "formula" -> formula(g, e, r);
            case "pie" -> pie(g, e, r);
            case "tree" -> tree(g, e, r);
            case "forces" -> forces(g, e, r);
            case "wave" -> wave(g, e, r);
            case "circuit" -> circuit(g, e, r);
            case "atom" -> atom(g, e, r);
            case "molecule" -> molecule(g, e, r);
            case "reaction" -> reaction(g, e, r);
            case "element" -> element(g, e, r);
            case "pins" -> { }   // drawn by pinsOverlay on top of their target
            case "numberline" -> numberline(g, e, r);
            case "triangle" -> triangle(g, e, r);
            case "fraction" -> fraction(g, e, r);
            case "quiz" -> quiz(g, e, r);
            case "factcard" -> factcard(g, e, r);
            case "mascot" -> mascot(g, e, r);
            case "speech" -> speech(g, e, r);
            case "badge" -> badge(g, e, r);
            case "pip" -> pip(g, e, r);
            case "bigpic" -> bigpic(g, e, r);
            case "count" -> count(g, e, r);
            case "choose" -> choose(g, e, r);
            case "sizes" -> sizes(g, e, r);
            case "level" -> level(g, e, r);
            case "challenge" -> challenge(g, e, r);
            case "cloudtitle" -> cloudtitle(g, e, r);
            case "flowchart" -> flowchart(g, e, r);
            case "sequence" -> sequence(g, e, r);
            case "datastruct" -> datastruct(g, e, r);
            case "periodic" -> periodic(g, e, r);
            case "energy" -> energy(g, e, r);
            case "lens" -> lens(g, e, r);
            case "funnel" -> funnel(g, e, r);
            case "iconrow" -> iconrow(g, e, r);
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
        boolean twoLines = !t.isEmpty() && widthOf(t, sansBold, (int) Math.max(18, Math.min(34, s * 0.19))) > s - 16;
        icon(g, icon(e, "box"), r.getCenterX(), r.getY() + r.getHeight() * (t.isEmpty() ? 0.5 : twoLines ? 0.34 : 0.40), s * (twoLines ? 0.38 : 0.46), p[1]);
        if (!t.isEmpty()) {
            int size = (int) Math.max(18, Math.min(34, s * 0.19));
            if (widthOf(t, sansBold, size) <= s - 16) {
                text(g, t, (float) (r.getCenterX() - widthOf(t, sansBold, size) / 2), (float) (r.getY() + r.getHeight() * 0.82), sansBold, size, p[2]);
            } else {   // long names ("Eustachian tube"): two smaller lines instead of an ellipsis
                int sz = (int) (size * 0.8);
                List<String> ls = wrap(t, sansBold, sz, (int) (s - 14));
                while (ls.size() > 2 && sz > 14) { sz -= 2; ls = wrap(t, sansBold, sz, (int) (s - 14)); }
                float y = (float) (r.getY() + r.getHeight() * (ls.size() > 1 ? 0.74 : 0.82));
                for (int i = 0; i < Math.min(2, ls.size()); i++) {
                    String l = fit(ls.get(i), sansBold, sz, (int) (s - 14));
                    text(g, l, (float) (r.getCenterX() - widthOf(l, sansBold, sz) / 2), y, sansBold, sz, p[2]);
                    y += sz * 1.1f;
                }
            }
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
        List<String> ls = wrap("\u201C" + unquote(e.text()) + "\u201D", sansItalic, 30, (int) r.getWidth());
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
                if (hasIcon(c)) icon(g, c, x + 32, y + 32, 34, ink);
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
            // numbers twice as big as the words around them, every word of a line on ONE baseline
            float top = y - size * 0.8f;
            for (List<Word> line : statLines(e.text(), size, (int) r.getWidth() - 60)) {
                int lineMax = size;
                for (Word wd : line) lineMax = Math.max(lineMax, statSize(wd, size));
                float base = top + lineMax * 0.86f, px = x;
                for (Word wd : line) {
                    int sz = statSize(wd, size);
                    float ww = widthOf(wd.text(), sansBold, sz);
                    text(g, wd.text(), px, base, sansBold, sz, wd.hl() ? hl : TEXT);
                    caretX = px + ww; caretY = base;
                    px += ww + widthOf(" ", sansBold, size) * 0.9f;
                }
                top += lineMax * 1.02f + 6;
            }
            y = top + size * 0.8f;
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

    private static int statSize(Word w, int size) { return w.text().matches(".*\\d.*") ? (int) (size * 1.9) : size; }

    /** Wraps a stat sentence where number tokens are drawn larger. */
    private List<List<Word>> statLines(String text, int size, int maxW) {
        List<List<Word>> lines = new ArrayList<>();
        List<Word> cur = new ArrayList<>();
        float w = 0, space = widthOf(" ", sansBold, size) * 0.9f;
        for (Word wd : richWords(text)) {
            float ww = widthOf(wd.text(), sansBold, statSize(wd, size));
            if (!cur.isEmpty() && w + space + ww > maxW) { lines.add(cur); cur = new ArrayList<>(); w = 0; }
            w += (cur.isEmpty() ? 0 : space) + ww;
            cur.add(wd);
        }
        if (!cur.isEmpty()) lines.add(cur);
        return lines;
    }

    private double statHeight(String text, int size, int maxW) {
        double h = 0;
        for (List<Word> line : statLines(text, size, maxW)) {
            int m = size;
            for (Word wd : line) m = Math.max(m, statSize(wd, size));
            h += m * 1.02 + 6;
        }
        return h;
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
        Map<String, Rectangle2D> b = layoutFor(scene);
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
                    drawUnderlay(g, scene.elements(), e -> e.at() <= k ? 1f : 0f);
                    Graphics2D cg = (Graphics2D) g.create();
                    try {
                        applyCamera(cg, cam);
                        drawLayers(cg, scene.elements(), b, e -> e.at() < k ? 1f : e.at() == k ? (e.type().equals("callout") ? 1f : fade) : 0f, k);
                    } finally {
                        cg.dispose();
                    }
                    drawHud(g, scene.elements(), b, e -> e.at() < k ? 1f : e.at() == k ? fade : 0f, k);
                    headingText(g, scene.heading(), scene.label(), 1f);
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
        g.setColor(new Color(0xEDF0F5)); g.fill(screen);
        double sx = x + ins, sw = w - ins * 2, k = sw / 400.0;
        Color brand = accent(e.color(), new Color(0xF07D2B));
        if (e.color() == null) brand = new Color(0xF07D2B);
        g.setColor(brand); g.fill(new Rectangle2D.Double(sx, y + ins, sw, 150 * k));
        text(g, "9:41", (float) (sx + 24 * k), (float) (y + ins + 30 * k), sansBold, (int) (16 * k), Color.WHITE);
        g.setColor(Color.WHITE); g.fill(new Ellipse2D.Double(sx + sw / 2 - 40 * k, y + ins + 12 * k, 80 * k, 22 * k));
        text(g, fit(nz(e.text()).toUpperCase(Locale.ROOT), sansBold, (int) (24 * k), (int) (sw - 40 * k)), (float) (sx + 22 * k), (float) (y + ins + 92 * k), sansBold, (int) (24 * k), Color.WHITE);
        if (!nz(e.title()).isEmpty()) text(g, fit(nz(e.title()), sans, (int) (16 * k), (int) (sw - 40 * k)), (float) (sx + 22 * k), (float) (y + ins + 122 * k), sans, (int) (16 * k), new Color(255, 255, 255, 220));
        double cy = y + ins + 170 * k;
        List<String> rowsList = listOrEmpty(e);
        if (rowsList.isEmpty()) rowsList = List.of("·|", "·|", "·|");     // skeleton rows, never an empty white screen
        for (String row : rowsList) {
            if (cy > y + h - 160 * k) break;
            String[] p = row.split("\\|");
            RoundRectangle2D card = new RoundRectangle2D.Double(sx + 16 * k, cy, sw - 32 * k, 92 * k, 14 * k, 14 * k);
            g.setColor(Color.WHITE); g.fill(card);
            g.setColor(new Color(0xD2D9E4)); g.setStroke(new BasicStroke(1.4f)); g.draw(card);
            if (p[0].trim().equals("·")) {
                g.setColor(new Color(0xC7D0DD));
                g.fill(new RoundRectangle2D.Double(sx + 32 * k, cy + 20 * k, sw * 0.45, 18 * k, 9 * k, 9 * k));
            } else
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
        boolean framed = nz(e.style()).toLowerCase(Locale.ROOT).contains("framed");
        Shape clip = g.getClip();
        RoundRectangle2D frame = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), framed ? 22 : 0, framed ? 22 : 0);
        g.clip(frame);
        g.drawImage(img, (int) (r.getCenterX() - dw / 2), (int) (r.getCenterY() - dh / 2), (int) dw, (int) dh, null);
        if (!framed && !List.of("toddler", "kids", "teen").contains(themeId)) {   // full-screen backdrop: darken the top so the header stays readable
            g.setPaint(new GradientPaint(0, (float) r.getY(), new Color(8, 20, 45, 200), 0, (float) (r.getY() + r.getHeight() * 0.3), new Color(8, 20, 45, 0)));
            g.fill(r);
        }
        g.setClip(clip);
        if (framed) {   // a picture card next to the diagram
            g.setColor(TEAL_BTN_EDGE);
            g.setStroke(new BasicStroke(2f));
            g.draw(frame);
        }
    }


    // ------------------------------------------------------------------ teaching devices (all themes)

    private Color[] series() { return new Color[]{CYAN, GREEN, PINK, VIOLET, HL, new Color(0xF0A060)}; }

    private static String part(String row, int i) {
        String[] p = row.split("\\|");
        return i < p.length ? p[i].trim() : "";
    }

    private void centered(Graphics2D g, String t, double cx, double baseline, Font f, int size, Color c, double maxW) {
        String x = fit(t, f, size, (int) maxW);
        text(g, x, (float) (cx - widthOf(x, f, size) / 2), (float) baseline, f, size, c);
    }

    /** Events down a vertical line (portrait frames): the dot on the left, the title and its detail beside it. */
    private void timelineVertical(Graphics2D g, List<String> ev, int hl, Rectangle2D r) {
        double x0 = r.getX() + 46, y0 = r.getY() + 30, y1 = r.getMaxY() - 30;
        g.setColor(MUTED); g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(x0, y0 - 14, x0, y1 + 8));
        arrowHead(g, x0, y1 - 24, x0, y1 + 14, MUTED);
        double step = ev.size() == 1 ? 0 : (y1 - y0 - 50) / (ev.size() - 1);
        double tw = r.getWidth() - 130;
        for (int i = 0; i < ev.size(); i++) {
            double cy = y0 + i * step;
            boolean on = hl == i + 1;
            Color c = on ? HL : series()[i % 3];
            g.setColor(c);
            g.fill(new Ellipse2D.Double(x0 - (on ? 17 : 12), cy - (on ? 17 : 12), on ? 34 : 24, on ? 34 : 24));
            text(g, fit(part(ev.get(i), 0), sansBold, 38, (int) tw), (float) (x0 + 48), (float) (cy + 6), sansBold, 38, c);
            List<String> det = wrap(part(ev.get(i), 1), sans, 28, (int) tw);
            for (int k = 0; k < Math.min(2, det.size()); k++) text(g, det.get(k), (float) (x0 + 48), (float) (cy + 46 + k * 34), sans, 28, TEXT);
        }
    }

    /** Events on a line: "1990|Web begins". style = number of the highlighted event. */
    private void timeline(Graphics2D g, Element e, Rectangle2D r) {
        List<String> ev = listOrEmpty(e);
        if (ev.isEmpty()) return;
        int hl = (int) parseNum(nz(e.style()));
        if (portrait) { timelineVertical(g, ev, hl, r); return; }
        double y = r.getCenterY(), x0 = r.getX() + 40, x1 = r.getMaxX() - 40;
        g.setColor(MUTED); g.setStroke(new BasicStroke(3f));
        g.draw(new Line2D.Double(x0, y, x1, y));
        arrowHead(g, x1 - 20, y, x1 + 8, y, MUTED);
        double step = ev.size() == 1 ? 0 : (x1 - x0 - 30) / (ev.size() - 1);
        for (int i = 0; i < ev.size(); i++) {
            double cx = x0 + i * step;
            boolean on = hl == i + 1;
            Color c = on ? HL : series()[i % 3];
            g.setColor(c);
            g.fill(new Ellipse2D.Double(cx - (on ? 13 : 9), y - (on ? 13 : 9), on ? 26 : 18, on ? 26 : 18));
            boolean up = i % 2 == 0;                                 // alternate above / below so neighbours never collide
            double cw = Math.max(160, Math.min(320, step * 1.6));
            List<String> det = wrap(part(ev.get(i), 1), sans, 22, (int) cw);
            if (up) {
                double base = y - 30;
                for (int k = Math.min(2, det.size()) - 1; k >= 0; k--) { centered(g, det.get(k), cx, base, sans, 22, TEXT, cw); base -= 28; }
                centered(g, part(ev.get(i), 0), cx, base - 6, sansBold, 30, c, cw);
            } else {
                centered(g, part(ev.get(i), 0), cx, y + 52, sansBold, 30, c, cw);
                for (int k = 0; k < Math.min(2, det.size()); k++) centered(g, det.get(k), cx, y + 84 + k * 28, sans, 22, TEXT, cw);
            }
        }
    }

    /** Numbered steps "Title|detail", horizontal (default) or vertical; style "2" highlights step 2. */
    private void steps(Graphics2D g, Element e, Rectangle2D r) {
        List<String> st = listOrEmpty(e);
        if (st.isEmpty()) return;
        String style = nz(e.style()).toLowerCase(Locale.ROOT);
        boolean vert = style.contains("vert") || portrait;
        int hl = (int) parseNum(style);
        for (int i = 0; i < st.size(); i++) {
            Color c = hl == i + 1 ? HL : series()[i % series().length];
            double cx, cy;
            if (vert) { cx = r.getX() + 44; cy = r.getY() + 50 + i * 104; }
            else { double w = r.getWidth() / st.size(); cx = r.getX() + w * i + w / 2; cy = r.getY() + 48; }
            g.setColor(c);
            g.fill(new Ellipse2D.Double(cx - 34, cy - 34, 68, 68));
            String n = String.valueOf(i + 1);
            text(g, n, (float) (cx - widthOf(n, sansBold, 34) / 2), (float) cy + 12, sansBold, 34, light ? Color.WHITE : BG);
            if (i < st.size() - 1) {
                g.setColor(MUTED); g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{8, 8}, 0));
                if (vert) g.draw(new Line2D.Double(cx, cy + 40, cx, cy + 64));
                else { double w = r.getWidth() / st.size(); g.draw(new Line2D.Double(cx + 42, cy, cx + w - 42, cy)); arrowHead(g, cx + w - 60, cy, cx + w - 42, cy, MUTED); }
            }
            String title = part(st.get(i), 0), detail = part(st.get(i), 1);
            if (vert) {
                text(g, fit(title, sansBold, 32, (int) r.getWidth() - 110), (float) cx + 58, (float) cy - 2, sansBold, 32, TEXT);
                if (!detail.isEmpty()) text(g, fit(detail, sans, 24, (int) r.getWidth() - 110), (float) cx + 58, (float) cy + 30, sans, 24, MUTED);
            } else {
                double w = r.getWidth() / st.size() - 20;
                centered(g, title, cx, cy + 82, sansBold, 30, TEXT, w);
                List<String> d = wrap(detail, sans, 22, (int) w);
                for (int k = 0; k < Math.min(3, d.size()); k++) centered(g, d.get(k), cx, cy + 118 + k * 28, sans, 22, MUTED, w);
            }
        }
    }

    /** Side-by-side comparison. title = "Left|Right"; lines = "Aspect|left value|right value" (a ✓ / ✗ prefix colours it). */
    private void compare(Graphics2D g, Element e, Rectangle2D r) {
        card(g, r);
        double x = r.getX(), w = r.getWidth(), c0 = x + 30, c1 = x + w * 0.38, c2 = x + w * 0.69;
        float y = (float) r.getY() + 56;
        String[] heads = nz(e.title()).split("\\|");
        text(g, heads.length > 0 ? fit(heads[0].trim(), sansBold, 32, (int) (w * 0.3)) : "", (float) c1, y, sansBold, 32, CYAN);
        text(g, heads.length > 1 ? fit(heads[1].trim(), sansBold, 32, (int) (w * 0.3)) : "", (float) c2, y, sansBold, 32, GREEN);
        g.setColor(TEAL_BTN_EDGE); g.setStroke(new BasicStroke(1.4f));
        g.draw(new Line2D.Double(x + 20, y + 18, x + w - 20, y + 18));
        y += 62;
        for (String row : listOrEmpty(e)) {
            text(g, fit(part(row, 0), sans, 26, (int) (w * 0.33)), (float) c0, y, sans, 26, MUTED);
            for (int k = 1; k <= 2; k++) {
                String v = part(row, k);
                Color c = v.startsWith("✓") || v.startsWith("✔") ? GREEN : v.startsWith("✗") || v.startsWith("✘") || v.startsWith("×") ? PINK : TEXT;
                text(g, fit(v, sansBold, 26, (int) (w * 0.29)), (float) (k == 1 ? c1 : c2), y, sansBold, 26, c);
            }
            y += 62;
        }
    }

    /** A loop of stages with arrows around a centre label (text). */
    private void cycle(Graphics2D g, Element e, Rectangle2D r) {
        List<String> st = listOrEmpty(e);
        int n = Math.max(2, st.size());
        double cx = r.getCenterX(), cy = r.getCenterY(), rad = Math.min(r.getWidth(), r.getHeight()) * 0.34;
        g.setColor(MUTED); g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{10, 9}, 0));
        g.draw(new Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2));
        for (int i = 0; i < st.size(); i++) {
            double a = -Math.PI / 2 + i * 2 * Math.PI / n, a2 = a + Math.PI / n;
            arrowHead(g, cx + Math.cos(a2 - 0.08) * rad, cy + Math.sin(a2 - 0.08) * rad, cx + Math.cos(a2) * rad, cy + Math.sin(a2) * rad, MUTED);
            double px = cx + Math.cos(a) * rad, py = cy + Math.sin(a) * rad;
            Color c = series()[i % series().length];
            double bw = Math.min(250, rad * 1.05);
            RoundRectangle2D box = new RoundRectangle2D.Double(px - bw / 2, py - 34, bw, 68, 34, 34);
            g.setColor(light ? Color.WHITE : TEAL_BTN); g.fill(box);
            g.setColor(c); g.setStroke(new BasicStroke(2.6f)); g.draw(box);
            centered(g, part(st.get(i), 0), px, py + 10, sansBold, 26, c, bw - 20);
        }
        String centre = nz(e.text());
        if (!centre.isEmpty()) {
            double inner = rad * 2 - Math.min(250, rad * 1.05) - 30;   // space between the left and right stage boxes
            int sz = 32;
            List<String> ls = wrap(centre, sansBold, sz, (int) inner);
            while (ls.size() > 2 && sz > 20) { sz -= 2; ls = wrap(centre, sansBold, sz, (int) inner); }
            float y = (float) (cy - (ls.size() - 1) * sz * 0.6 + sz * 0.35);
            for (String l : ls) { centered(g, l, cx, y, sansBold, sz, TEXT, inner); y += sz * 1.2f; }
        }
    }

    /** Stacked layers, top to bottom ("Name|what it does"); style "pyramid" narrows the top. */
    private void layers(Graphics2D g, Element e, Rectangle2D r) {
        List<String> ls = listOrEmpty(e);
        boolean pyr = nz(e.style()).toLowerCase(Locale.ROOT).contains("pyramid");
        for (int i = 0; i < ls.size(); i++) {
            double frac = pyr ? 0.35 + 0.65 * (i + 1.0) / ls.size() : 1.0;
            double w = r.getWidth() * frac, x = r.getCenterX() - w / 2, y = r.getY() + i * 92;
            Color c = series()[i % series().length];
            RoundRectangle2D box = new RoundRectangle2D.Double(x, y, w, 80, 14, 14);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), light ? 50 : 70)); g.fill(box);
            g.setColor(c); g.setStroke(new BasicStroke(2.2f)); g.draw(box);
            String name = part(ls.get(i), 0), what = part(ls.get(i), 1);
            if (what.isEmpty()) centered(g, name, r.getCenterX(), y + 50, sansBold, 30, TEXT, w - 30);
            else {
                centered(g, name, r.getCenterX(), y + 36, sansBold, 28, TEXT, w - 30);
                centered(g, what, r.getCenterX(), y + 66, sans, 21, MUTED, w - 30);
            }
        }
    }

    /** Two or three overlapping sets; text = what sits in the overlap. */
    private void venn(Graphics2D g, Element e, Rectangle2D r) {
        List<String> sets = listOrEmpty(e);
        int n = Math.max(2, Math.min(3, sets.size()));
        double d = r.getHeight() * (n == 3 ? 0.62 : 0.8), cx = r.getCenterX(), cy = r.getCenterY();
        double[][] pos = n == 3 ? new double[][]{{-0.32, -0.18}, {0.32, -0.18}, {0, 0.3}} : new double[][]{{-0.3, 0}, {0.3, 0}};
        for (int i = 0; i < n; i++) {
            Color c = series()[i];
            double x = cx + pos[i][0] * d * 1.15 - d / 2, y = cy + pos[i][1] * d - d / 2;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), light ? 55 : 65)); g.fill(new Ellipse2D.Double(x, y, d, d));
            g.setColor(c); g.setStroke(new BasicStroke(2.5f)); g.draw(new Ellipse2D.Double(x, y, d, d));
            if (i < sets.size()) {
                double lx = cx + pos[i][0] * d * 1.8, ly = cy + pos[i][1] * d * 1.5 + (n == 3 && i == 2 ? d * 0.25 : 0);
                centered(g, part(sets.get(i), 0), lx, ly, sansBold, 30, c, d * 0.7);
            }
        }
        String mid = nz(e.text());
        if (!mid.isEmpty()) {
            double room = d * (n == 3 ? 0.42 : 0.38);
            int sz = n == 3 ? 24 : 28;
            List<String> ls = wrap(mid, sansBold, sz, (int) room);
            float y = (float) (cy + (n == 3 ? 4 : 10) - (Math.min(2, ls.size()) - 1) * sz * 0.6);
            for (int k = 0; k < Math.min(2, ls.size()); k++) { centered(g, ls.get(k), cx, y, sansBold, sz, TEXT, room); y += sz * 1.2f; }
        }
    }

    /** A dial: text = value ("72%" or "72"), title = label, lines = ["low label","high label"]. */
    private void meter(Graphics2D g, Element e, Rectangle2D r) {
        double v = Math.max(0, Math.min(100, parseNum(nz(e.text()))));
        double cx = r.getCenterX(), cy = r.getY() + r.getWidth() * 0.5, rad = r.getWidth() * 0.42;
        Color[] zones = {GREEN, HL, PINK};
        for (int z = 0; z < 3; z++) {
            g.setColor(zones[z]); g.setStroke(new BasicStroke((float) (rad * 0.16), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Double(cx - rad, cy - rad, rad * 2, rad * 2, 180 - z * 60, -60, Arc2D.OPEN));
        }
        double a = Math.PI - v / 100 * Math.PI;
        g.setColor(TEXT); g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(cx, cy, cx + Math.cos(a) * rad * 0.8, cy - Math.sin(a) * rad * 0.8));
        g.fill(new Ellipse2D.Double(cx - 12, cy - 12, 24, 24));
        centered(g, nz(e.text()), cx, cy + 60, sansBold, 48, TEXT, r.getWidth());
        if (!nz(e.title()).isEmpty()) centered(g, nz(e.title()).toUpperCase(Locale.ROOT), cx, cy + 96, sansBold, 22, MUTED, r.getWidth());
        List<String> ends = listOrEmpty(e);
        if (ends.size() > 0) text(g, ends.get(0), (float) (cx - rad - 10), (float) cy + 34, sans, 20, MUTED);
        if (ends.size() > 1) text(g, ends.get(1), (float) (cx + rad + 10 - widthOf(ends.get(1), sans, 20)), (float) cy + 34, sans, 20, MUTED);
    }

    /** Do / don't list: "✓ Drink water", "✗ Skip sleep" (or "item|yes" / "item|no"). */
    private void checklist(Graphics2D g, Element e, Rectangle2D r) {
        card(g, r);
        float y = (float) r.getY() + 50, x = (float) r.getX() + 30;
        if (!nz(e.title()).isEmpty()) { text(g, fit(nz(e.title()), sansBold, 32, (int) r.getWidth() - 60), x, y, sansBold, 32, TEXT); y += 58; }
        for (String row : listOrEmpty(e)) {
            String t = row.trim();
            boolean no = t.startsWith("✗") || t.startsWith("✘") || t.startsWith("×") || t.toLowerCase(Locale.ROOT).endsWith("|no");
            boolean yes = !no;
            t = t.replaceFirst("^[✓✔✗✘×]\\s*", "").replaceFirst("\\|(yes|no)$", "");
            Color c = yes ? GREEN : PINK;
            g.setColor(c);
            g.fill(new Ellipse2D.Double(x, y - 30, 40, 40));
            g.setColor(light ? Color.WHITE : BG); g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (yes) { Path2D p = new Path2D.Double(); p.moveTo(x + 10, y - 10); p.lineTo(x + 18, y - 2); p.lineTo(x + 31, y - 19); g.draw(p); }
            else { g.draw(new Line2D.Double(x + 12, y - 22, x + 28, y - 6)); g.draw(new Line2D.Double(x + 28, y - 22, x + 12, y - 6)); }
            text(g, fit(t, sans, 28, (int) r.getWidth() - 110), x + 58, y, sans, 28, TEXT);
            y += 66;
        }
    }

    /** "The concept is like an everyday thing": two cards and an ≈ between them. title = concept, text = everyday thing,
     *  icon = concept icon, content = everyday icon, lines = optional shared traits. */
    private void analogy(Graphics2D g, Element e, Rectangle2D r) {
        double cw = r.getWidth() * 0.38, ch = r.getHeight() - (listOrEmpty(e).isEmpty() ? 0 : 70);
        Rectangle2D a = new Rectangle2D.Double(r.getX(), r.getY(), cw, ch), b = new Rectangle2D.Double(r.getMaxX() - cw, r.getY(), cw, ch);
        if (portrait) {          // a tall frame: the two sides are stacked with the "is like" sign between them
            double gap = 120, bh = (ch - gap) / 2;
            a = new Rectangle2D.Double(r.getX(), r.getY(), r.getWidth(), bh);
            b = new Rectangle2D.Double(r.getX(), r.getY() + bh + gap, r.getWidth(), bh);
        }
        String[][] side = {{nz(e.title()), nz(e.icon()), "THE IDEA"}, {nz(e.text()), nz(e.extra()), "IS LIKE"}};
        Rectangle2D[] boxes = {a, b};
        for (int i = 0; i < 2; i++) {
            Rectangle2D bx = boxes[i];
            Color c = i == 0 ? CYAN : GREEN;
            RoundRectangle2D rr = new RoundRectangle2D.Double(bx.getX(), bx.getY(), bx.getWidth(), bx.getHeight(), 24, 24);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), light ? 35 : 45)); g.fill(rr);
            g.setColor(c); g.setStroke(new BasicStroke(2.4f)); g.draw(rr);
            centered(g, side[i][2], bx.getCenterX(), bx.getY() + 40, sansBold, 20, MUTED, bx.getWidth());
            icon(g, side[i][1].isEmpty() ? (i == 0 ? "bolt" : "home") : side[i][1], bx.getCenterX(), bx.getY() + bx.getHeight() * 0.48, Math.min(120, bx.getHeight() * 0.38), c);
            List<String> ls = wrap(side[i][0], sansBold, 30, (int) bx.getWidth() - 30);
            float y = (float) (bx.getMaxY() - 30 - (Math.min(2, ls.size()) - 1) * 36);
            for (int k = 0; k < Math.min(2, ls.size()); k++) { centered(g, ls.get(k), bx.getCenterX(), y, sansBold, 30, TEXT, bx.getWidth() - 20); y += 36; }
        }
        centered(g, "\u2248", r.getCenterX(), r.getY() + ch / 2 + 30, sansBold, 90, HL, 140);
        float y = (float) (r.getY() + ch + 48);
        StringBuilder sb = new StringBuilder();
        for (String t : listOrEmpty(e)) { if (sb.length() > 0) sb.append("   ·   "); sb.append(t); }
        if (sb.length() > 0) centered(g, sb.toString(), r.getCenterX(), y, sans, 26, MUTED, r.getWidth());
    }

    /** 2x2 matrix. title = "x axis|y axis", lines = four quadrant labels (top-left, top-right, bottom-left, bottom-right);
     *  style = number of the highlighted quadrant. */
    private void matrix(Graphics2D g, Element e, Rectangle2D r) {
        List<String> q = listOrEmpty(e);
        int hl = (int) parseNum(nz(e.style()));
        double x = r.getX() + 50, y = r.getY() + 10, w = r.getWidth() - 60, h = r.getHeight() - 60;
        for (int i = 0; i < 4; i++) {
            double qx = x + (i % 2) * w / 2, qy = y + (i / 2) * h / 2;
            Color c = series()[i];
            RoundRectangle2D box = new RoundRectangle2D.Double(qx + 6, qy + 6, w / 2 - 12, h / 2 - 12, 16, 16);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), hl == i + 1 ? 110 : light ? 35 : 45)); g.fill(box);
            g.setColor(hl == i + 1 ? HL : c); g.setStroke(new BasicStroke(hl == i + 1 ? 4f : 2f)); g.draw(box);
            if (i < q.size()) {
                List<String> ls = wrap(part(q.get(i), 0), sansBold, 28, (int) (w / 2 - 40));
                float ty = (float) (qy + h / 4 - (ls.size() - 1) * 17 + 10);
                for (String l : ls) { centered(g, l, qx + w / 4, ty, sansBold, 28, TEXT, w / 2 - 30); ty += 34; }
                String sub = part(q.get(i), 1);
                if (!sub.isEmpty()) centered(g, sub, qx + w / 4, ty + 6, sans, 21, MUTED, w / 2 - 30);
            }
        }
        String[] ax = nz(e.title()).split("\\|");
        if (ax.length > 0 && !ax[0].isBlank()) centered(g, ax[0].trim() + " \u2192", x + w / 2, y + h + 44, sans, 24, MUTED, w);
        if (ax.length > 1) {
            var at = g.getTransform();
            g.rotate(-Math.PI / 2, x - 18, y + h / 2);
            centered(g, ax[1].trim() + " \u2192", x - 18, y + h / 2, sans, 24, MUTED, h);
            g.setTransform(at);
        }
    }

    /** A big formula (text) with its parts explained (lines "symbol|meaning"). */
    private void formula(Graphics2D g, Element e, Rectangle2D r) {
        card(g, r);
        int size = 76;
        String f = nz(e.text());
        while (size > 36 && widthOf(f, mono, size) > r.getWidth() - 60) size -= 4;
        centered(g, f, r.getCenterX(), r.getY() + 40 + size, mono, size, HL, r.getWidth() - 40);
        float y = (float) (r.getY() + 80 + size);
        int i = 0;
        for (String row : listOrEmpty(e)) {
            Color c = series()[i++ % 3];
            String sym = part(row, 0), meaning = part(row, 1);
            float sw = widthOf(sym, mono, 30);
            float x0 = (float) (r.getCenterX() - (sw + 30 + widthOf(meaning, sans, 28)) / 2);
            text(g, sym, x0, y, mono, 30, c);
            text(g, "= " + meaning, x0 + sw + 14, y, sans, 28, TEXT);
            y += 50;
        }
    }

    /** Donut chart from "Label|percent" rows with a legend; text = centre caption. */
    private void pie(Graphics2D g, Element e, Rectangle2D r) {
        List<String> rows = listOrEmpty(e);
        double total = 0;
        for (String row : rows) total += Math.max(0, parseNum(part(row, 1)));
        if (total <= 0) return;
        double d = Math.min(r.getHeight() - 20, r.getWidth() * 0.48), cx = r.getX() + d / 2 + 10, cy = r.getCenterY();
        double start = 90;
        for (int i = 0; i < rows.size(); i++) {
            double ext = -360 * Math.max(0, parseNum(part(rows.get(i), 1))) / total;
            g.setColor(series()[i % series().length]);
            g.fill(new Arc2D.Double(cx - d / 2, cy - d / 2, d, d, start, ext, Arc2D.PIE));
            start += ext;
        }
        g.setColor(light ? BG : BG.darker());
        g.fill(new Ellipse2D.Double(cx - d * 0.3, cy - d * 0.3, d * 0.6, d * 0.6));
        if (!nz(e.text()).isEmpty()) centered(g, nz(e.text()), cx, cy + 12, sansBold, 30, TEXT, d * 0.55);
        float ly = (float) (cy - rows.size() * 28 + 20), lx = (float) (cx + d / 2 + 50);
        for (int i = 0; i < rows.size(); i++) {
            g.setColor(series()[i % series().length]);
            g.fill(new RoundRectangle2D.Double(lx, ly - 22, 26, 26, 6, 6));
            String l = part(rows.get(i), 0) + "  " + part(rows.get(i), 1) + (part(rows.get(i), 1).endsWith("%") ? "" : "%");
            text(g, fit(l, sans, 28, (int) (r.getMaxX() - lx - 40)), lx + 40, ly, sans, 28, TEXT);
            ly += 56;
        }
    }

    /** Hierarchy: text = root, lines = "Branch: leaf, leaf" (2 levels). */
    private void tree(Graphics2D g, Element e, Rectangle2D r) {
        List<String> br = listOrEmpty(e);
        double rootY = r.getY() + 40, cx = r.getCenterX();
        double bw = r.getWidth() / Math.max(1, br.size());
        g.setStroke(new BasicStroke(2.2f));
        for (int i = 0; i < br.size(); i++) {
            double bx = r.getX() + bw * i + bw / 2, by = r.getY() + 190;
            g.setColor(MUTED);
            g.draw(new CubicCurve2D.Double(cx, rootY + 34, cx, rootY + 100, bx, by - 100, bx, by - 34));
            String[] p = br.get(i).split(":", 2);
            Color c = series()[i % series().length];
            RoundRectangle2D box = new RoundRectangle2D.Double(bx - bw / 2 + 12, by - 34, bw - 24, 68, 20, 20);
            g.setColor(light ? Color.WHITE : TEAL_BTN); g.fill(box);
            g.setColor(c); g.draw(box);
            centered(g, p[0].trim(), bx, by + 10, sansBold, 28, c, bw - 40);
            if (p.length > 1) {
                float ly = (float) by + 80;
                for (String leaf : p[1].split(",")) {
                    if (leaf.isBlank()) continue;
                    g.setColor(MUTED); g.draw(new Line2D.Double(bx, ly - 50, bx, ly - 26));
                    centered(g, leaf.trim(), bx, ly, sans, 24, TEXT, bw - 30);
                    ly += 44;
                    if (ly > r.getMaxY()) break;
                }
            }
        }
        String root = nz(e.text());
        float rw = Math.min((float) r.getWidth() * 0.6f, widthOf(root, sansBold, 34) + 60);
        RoundRectangle2D rb = new RoundRectangle2D.Double(cx - rw / 2, rootY - 34, rw, 68, 34, 34);
        g.setColor(HL); g.fill(rb);
        centered(g, root, cx, rootY + 12, sansBold, 34, light ? Color.WHITE : BG, rw - 20);
    }


    // ------------------------------------------------------------------ subject devices (physics / chemistry / biology / maths / GK / kids)

    private Font kidOr(Font f) { return themeId.equals("kids") && kidBold != null ? kidBold : f; }

    /** Free-body diagram: text = object, lines = "up|N|Normal force" (direction|symbol|name[|relative size]). */
    private void forces(Graphics2D g, Element e, Rectangle2D r) {
        double cx = r.getCenterX(), cy = r.getCenterY(), bw = r.getWidth() * 0.28, bh = r.getHeight() * 0.26;
        RoundRectangle2D box = new RoundRectangle2D.Double(cx - bw / 2, cy - bh / 2, bw, bh, 12, 12);
        g.setColor(light ? new Color(0xE9EEF5) : TEAL_BTN); g.fill(box);
        g.setColor(TEXT); g.setStroke(new BasicStroke(2.5f)); g.draw(box);
        centered(g, nz(e.text()), cx, cy + 12, sansBold, 30, TEXT, bw - 10);
        int i = 0;
        for (String row : listOrEmpty(e)) {
            String dir = part(row, 0).toLowerCase(Locale.ROOT), sym = part(row, 1), name = part(row, 2);
            double mag = parseNum(part(row, 3)) > 0 ? Math.min(2, parseNum(part(row, 3))) : 1;
            double dx = dir.startsWith("r") ? 1 : dir.startsWith("l") ? -1 : 0, dy = dir.startsWith("u") ? -1 : dir.startsWith("d") ? 1 : 0;
            if (dx == 0 && dy == 0) continue;
            double sx = cx + dx * bw / 2, sy = cy + dy * bh / 2;
            double len = (dx != 0 ? r.getWidth() * 0.22 : r.getHeight() * 0.26) * mag;
            double ex = sx + dx * len, ey = sy + dy * len;
            Color c = series()[i++ % series().length];
            g.setColor(c); g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(sx, sy, ex - dx * 16, ey - dy * 16));
            Path2D head = new Path2D.Double();
            head.moveTo(ex, ey); head.lineTo(ex - dx * 26 - dy * 14, ey - dy * 26 + dx * 14); head.lineTo(ex - dx * 26 + dy * 14, ey - dy * 26 - dx * 14); head.closePath();
            g.fill(head);
            String lab = sym + (name.isEmpty() ? "" : "  " + name);
            float lw = widthOf(lab, sansBold, 26);
            float lx = (float) (dx > 0 ? ex + 12 : dx < 0 ? ex - lw - 12 : ex + 16), ly = (float) (dy > 0 ? ey + 34 : dy < 0 ? ey - 12 : ey + 9);
            text(g, sym, lx, ly, mono, 28, c);
            if (!name.isEmpty()) text(g, name, lx + widthOf(sym + "  ", mono, 28), ly, sans, 24, MUTED);
        }
    }

    /** Wave with amplitude and wavelength markers. title = caption, lines = ["λ = 2 m", "A = 5 cm"], style "square" for a square wave. */
    private void wave(Graphics2D g, Element e, Rectangle2D r) {
        double x0 = r.getX() + 40, x1 = r.getMaxX() - 40, cy = r.getCenterY() + 10, amp = r.getHeight() * 0.28;
        g.setColor(MUTED); g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{6, 6}, 0));
        g.draw(new Line2D.Double(x0, cy, x1, cy));
        boolean square = nz(e.style()).toLowerCase(Locale.ROOT).contains("square");
        Path2D p = new Path2D.Double();
        double cycles = 2.5, wl = (x1 - x0) / cycles;
        for (int i = 0; i <= 400; i++) {
            double x = x0 + (x1 - x0) * i / 400.0, ph = (x - x0) / wl * 2 * Math.PI;
            double y = cy - amp * (square ? Math.signum(Math.sin(ph)) : Math.sin(ph));
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        g.setColor(CYAN); g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(p);
        // wavelength bracket (crest to crest) and amplitude marker
        double c1 = x0 + wl * 0.25, c2 = c1 + wl;
        g.setColor(HL); g.setStroke(new BasicStroke(2.5f));
        g.draw(new Line2D.Double(c1, cy - amp - 30, c2, cy - amp - 30));
        arrowHead(g, c1 + 20, cy - amp - 30, c1, cy - amp - 30, HL); arrowHead(g, c2 - 20, cy - amp - 30, c2, cy - amp - 30, HL);
        g.setColor(PINK);
        g.draw(new Line2D.Double(c1, cy, c1, cy - amp));
        List<String> ls = listOrEmpty(e);
        centered(g, ls.size() > 0 ? ls.get(0) : "\u03bb (wavelength)", (c1 + c2) / 2, cy - amp - 44, sansBold, 26, HL, wl * 1.4);
        text(g, ls.size() > 1 ? ls.get(1) : "A", (float) c1 + 10, (float) (cy - amp / 2), sansBold, 24, PINK);
        if (!nz(e.title()).isEmpty()) centered(g, nz(e.title()), r.getCenterX(), r.getMaxY() - 6, sans, 24, MUTED, r.getWidth());
    }

    /** Series circuit drawn as a loop: lines = "battery|9 V", "resistor|R = 3 Ω", "bulb|", "switch|open", "ammeter|A". */
    private void circuit(Graphics2D g, Element e, Rectangle2D r) {
        double x0 = r.getX() + 40, y0 = r.getY() + 50, x1 = r.getMaxX() - 40, y1 = r.getMaxY() - 50;
        List<String> parts = listOrEmpty(e);
        // place components around the loop: top edge, right edge, bottom edge, left edge
        double[][] slots = {{0.3, 0}, {0.7, 0}, {1, 0.5}, {0.6, 1}, {0.25, 1}, {0, 0.5}};
        g.setColor(TEXT); g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0));
        for (int i = 0; i < parts.size() && i < slots.length; i++) {
            double px = x0 + (x1 - x0) * slots[i][0], py = y0 + (y1 - y0) * slots[i][1];
            boolean vertical = slots[i][0] == 0 || slots[i][0] == 1;
            String kind = part(parts.get(i), 0).toLowerCase(Locale.ROOT), lab = part(parts.get(i), 1);
            g.setColor(BG); g.fill(new Rectangle2D.Double(px - (vertical ? 8 : 46), py - (vertical ? 46 : 8), vertical ? 16 : 92, vertical ? 92 : 16));
            Color c = series()[i % series().length];
            g.setColor(c); g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            var at = g.getTransform();
            if (vertical) g.rotate(Math.PI / 2, px, py);
            switch (kind.isEmpty() ? "resistor" : kind.substring(0, Math.min(4, kind.length()))) {
                case "batt", "cell" -> { g.draw(new Line2D.Double(px - 8, py - 30, px - 8, py + 30)); g.setStroke(new BasicStroke(8f)); g.draw(new Line2D.Double(px + 8, py - 16, px + 8, py + 16));
                    g.setStroke(new BasicStroke(4f)); g.draw(new Line2D.Double(px - 46, py, px - 8, py)); g.draw(new Line2D.Double(px + 8, py, px + 46, py)); }
                case "bulb", "lamp" -> { g.draw(new Line2D.Double(px - 46, py, px - 24, py)); g.draw(new Line2D.Double(px + 24, py, px + 46, py));
                    g.draw(new Ellipse2D.Double(px - 24, py - 24, 48, 48)); g.draw(new Line2D.Double(px - 16, py - 16, px + 16, py + 16)); g.draw(new Line2D.Double(px + 16, py - 16, px - 16, py + 16)); }
                case "swit" -> { g.draw(new Line2D.Double(px - 46, py, px - 18, py)); g.draw(new Line2D.Double(px - 18, py, px + 18, py - 24)); g.draw(new Line2D.Double(px + 22, py, px + 46, py));
                    g.fill(new Ellipse2D.Double(px - 23, py - 5, 10, 10)); g.fill(new Ellipse2D.Double(px + 17, py - 5, 10, 10)); }
                case "amme", "volt", "mete" -> { g.draw(new Line2D.Double(px - 46, py, px - 24, py)); g.draw(new Line2D.Double(px + 24, py, px + 46, py)); g.draw(new Ellipse2D.Double(px - 24, py - 24, 48, 48));
                    String m = kind.startsWith("volt") ? "V" : "A"; text(g, m, (float) (px - widthOf(m, sansBold, 26) / 2), (float) py + 9, sansBold, 26, c); }
                default -> { Path2D z = new Path2D.Double(); z.moveTo(px - 46, py); z.lineTo(px - 30, py);
                    for (int k = 0; k < 6; k++) z.lineTo(px - 30 + 10 * k + 5, py + (k % 2 == 0 ? -14 : 14));
                    z.lineTo(px + 30, py); z.lineTo(px + 46, py); g.draw(z); }
            }
            g.setTransform(at);
            if (!lab.isEmpty()) {
                if (vertical) text(g, lab, (float) (px + (slots[i][0] == 1 ? 28 : -28 - widthOf(lab, sansBold, 24))), (float) py + 8, sansBold, 24, c);
                else centered(g, lab, px, slots[i][1] == 0 ? py - 36 : py + 56, sansBold, 24, c, 220);
            }
        }
        if (!nz(e.text()).isEmpty()) centered(g, nz(e.text()), r.getCenterX(), r.getCenterY() + 10, sans, 26, MUTED, (x1 - x0) * 0.7);
    }

    /** Bohr model: text = symbol in the nucleus, lines = electrons per shell ["2","8","1"]. */
    private void atom(Graphics2D g, Element e, Rectangle2D r) {
        double cx = r.getCenterX(), cy = r.getCenterY(), max = Math.min(r.getWidth(), r.getHeight()) / 2 - 10;
        List<String> shells = listOrEmpty(e);
        int n = Math.max(1, shells.size());
        for (int s2 = 0; s2 < shells.size(); s2++) {
            double rad = max * (0.38 + 0.62 * (s2 + 1.0) / n);
            g.setColor(MUTED); g.setStroke(new BasicStroke(2f)); g.draw(new Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2));
            int el = (int) parseNum(shells.get(s2));
            for (int k = 0; k < el; k++) {
                double a = -Math.PI / 2 + k * 2 * Math.PI / Math.max(1, el) + s2 * 0.3;
                g.setColor(CYAN); g.fill(new Ellipse2D.Double(cx + Math.cos(a) * rad - 10, cy + Math.sin(a) * rad - 10, 20, 20));
            }
        }
        double nr = max * 0.26;
        g.setColor(PINK); g.fill(new Ellipse2D.Double(cx - nr, cy - nr, nr * 2, nr * 2));
        centered(g, nz(e.text()), cx, cy + nr * 0.35, sansBold, (int) Math.max(22, nr * 0.85), Color.WHITE, nr * 1.9);
        if (!nz(e.title()).isEmpty()) centered(g, nz(e.title()), cx, r.getMaxY() + 30, sans, 24, MUTED, r.getWidth());
    }

    private Color cpk(String sym) {
        return switch (sym.replaceAll("[^A-Za-z]", "")) {
            case "H" -> new Color(0xF2F2F2); case "C" -> new Color(0x4A4A4A); case "O" -> new Color(0xE84141); case "N" -> new Color(0x3B6FE8);
            case "Cl" -> new Color(0x3BBF4B); case "S" -> new Color(0xE8C93B); case "Na", "K" -> new Color(0x9B5DE5); case "P" -> new Color(0xF28C28);
            default -> new Color(0x8FA3B8);
        };
    }

    /** Ball-and-stick molecule: lines = atoms, the first is central ("O","H","H"); prefix "=" for a double bond. text = name / formula. */
    private void molecule(Graphics2D g, Element e, Rectangle2D r) {
        List<String> at = listOrEmpty(e);
        if (at.isEmpty()) return;
        double cx = r.getCenterX(), cy = r.getCenterY() - 20, bond = Math.min(r.getWidth(), r.getHeight()) * 0.3;
        int n = at.size() - 1;
        double start = n == 2 ? Math.toRadians(-90 - 52) : -Math.PI / 2, step = n == 2 ? Math.toRadians(104.5) : 2 * Math.PI / Math.max(1, n);
        double[][] pos = new double[n][2];
        for (int i = 0; i < n; i++) { double a = start + i * step; pos[i][0] = cx + Math.cos(a) * bond; pos[i][1] = cy + Math.sin(a) * bond; }
        for (int i = 0; i < n; i++) {
            boolean dbl = at.get(i + 1).startsWith("=");
            g.setColor(MUTED); g.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (dbl) {
                double ox = -(pos[i][1] - cy) / bond * 7, oy = (pos[i][0] - cx) / bond * 7;
                g.draw(new Line2D.Double(cx + ox, cy + oy, pos[i][0] + ox, pos[i][1] + oy)); g.draw(new Line2D.Double(cx - ox, cy - oy, pos[i][0] - ox, pos[i][1] - oy));
            } else g.draw(new Line2D.Double(cx, cy, pos[i][0], pos[i][1]));
        }
        java.util.function.BiConsumer<String, double[]> ball = (sym, p) -> {
            String s0 = sym.replace("=", "").trim();
            double rr = s0.equals("H") ? 34 : 50;
            Color c = cpk(s0);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(p[0] - rr * 0.35, p[1] - rr * 0.35), (float) (rr * 1.3), new float[]{0f, 1f}, new Color[]{c.brighter(), c.darker()}));
            g.fill(new Ellipse2D.Double(p[0] - rr, p[1] - rr, rr * 2, rr * 2));
            Color ink = (c.getRed() + c.getGreen() + c.getBlue()) > 500 ? new Color(0x333333) : Color.WHITE;
            centered(g, s0, p[0], p[1] + 10, sansBold, 30, ink, rr * 1.8);
        };
        for (int i = 0; i < n; i++) ball.accept(at.get(i + 1), pos[i]);
        ball.accept(at.get(0), new double[]{cx, cy});
        if (!nz(e.text()).isEmpty()) drawChem(g, nz(e.text()), r.getCenterX(), r.getMaxY() - 14, 34, TEXT, true);
    }

    /** Chemistry text with subscripts (H2O -> H₂O) and arrows; returns width. */
    private float drawChem(Graphics2D g, String s0, double x, double base, int size, Color c, boolean center) {
        String s1 = s0.replace("->", "\u2192").replace("<=>", "\u21cc");
        List<Object[]> runs = new ArrayList<>();   // {text, isSub}
        StringBuilder cur = new StringBuilder();
        boolean sub = false;
        for (int i = 0; i < s1.length(); i++) {
            char ch = s1.charAt(i);
            boolean digitSub = Character.isDigit(ch) && i > 0 && (Character.isLetter(s1.charAt(i - 1)) || s1.charAt(i - 1) == ')' || (sub && Character.isDigit(s1.charAt(i - 1))));
            if (digitSub != sub && cur.length() > 0) { runs.add(new Object[]{cur.toString(), sub}); cur.setLength(0); }
            sub = digitSub;
            cur.append(ch);
        }
        if (cur.length() > 0) runs.add(new Object[]{cur.toString(), sub});
        Font chem = baseBold;   // chemistry needs plain, unambiguous glyphs (+, arrows, subscripts) in every look
        float total = 0;
        for (Object[] rn : runs) total += widthOf((String) rn[0], chem, (boolean) rn[1] ? (int) (size * 0.62) : size);
        float px = (float) (center ? x - total / 2 : x);
        for (Object[] rn : runs) {
            boolean sb = (boolean) rn[1];
            int sz = sb ? (int) (size * 0.62) : size;
            text(g, (String) rn[0], px, (float) (base + (sb ? size * 0.22 : 0)), chem, sz, c);
            px += widthOf((String) rn[0], chem, sz);
        }
        return total;
    }

    /** Reaction equation (text, "->" becomes an arrow, digits become subscripts); title = condition above the arrow;
     *  lines = labels under the reactants / products, in order. */
    private void reaction(Graphics2D g, Element e, Rectangle2D r) {
        card(g, r);
        String eq = nz(e.text());
        int size = 54;
        while (size > 28 && widthOf(eq, sansBold, size) > r.getWidth() - 80) size -= 4;
        double base = r.getY() + r.getHeight() * 0.55;
        drawChem(g, eq, r.getCenterX(), base, size, TEXT, true);
        if (!nz(e.title()).isEmpty()) centered(g, nz(e.title()), r.getCenterX(), base - size - 6, sans, 24, HL, r.getWidth() * 0.4);
        List<String> labs = listOrEmpty(e);
        if (!labs.isEmpty()) {
            double slot = (r.getWidth() - 80) / labs.size();
            for (int i = 0; i < labs.size(); i++) centered(g, labs.get(i), r.getX() + 40 + slot * i + slot / 2, base + 52, sans, 22, MUTED, slot - 10);
        }
    }

    /** Periodic-table tile: text = symbol, title = name, lines = [atomic number, atomic mass], color = group colour. */
    private void element(Graphics2D g, Element e, Rectangle2D r) {
        Color c = accent(e.color(), CYAN);
        RoundRectangle2D rr = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 18, 18);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), light ? 45 : 60)); g.fill(rr);
        g.setColor(c); g.setStroke(new BasicStroke(3f)); g.draw(rr);
        List<String> ls = listOrEmpty(e);
        if (!ls.isEmpty()) text(g, ls.get(0), (float) r.getX() + 18, (float) r.getY() + 40, sansBold, 30, c);
        if (ls.size() > 1) text(g, ls.get(1), (float) (r.getMaxX() - 18 - widthOf(ls.get(1), sans, 22)), (float) r.getY() + 38, sans, 22, MUTED);
        centered(g, nz(e.text()), r.getCenterX(), r.getCenterY() + r.getHeight() * 0.14, sansBold, (int) (r.getWidth() * 0.36), TEXT, r.getWidth() - 20);
        centered(g, nz(e.title()), r.getCenterX(), r.getMaxY() - 22, sans, 26, TEXT, r.getWidth() - 20);
    }

    /** Labelled pins on another element (usually a photo / anatomy picture): from = that element's id,
     *  lines = "Eardrum@40,55" (label @ x%,y% inside the target). Labels go to the nearer side with leader lines. */
    private void pinsOverlay(Graphics2D g, Element e, Map<String, Rectangle2D> b) {
        Rectangle2D t = b.get(nz(e.from()));
        if (t == null) return;
        int iL = 0, iR = 0;
        List<String> ls = listOrEmpty(e);
        for (String row : ls) {
            int at = row.lastIndexOf('@');
            if (at < 0) continue;
            String lab = row.substring(0, at).trim();
            String[] xy = row.substring(at + 1).split("[,;\\s]+");
            if (xy.length < 2) continue;
            double px = t.getX() + t.getWidth() * parseNum(xy[0]) / 100, py = t.getY() + t.getHeight() * parseNum(xy[1]) / 100;
            boolean left = px < t.getCenterX();
            double ly = t.getY() + 40 + (left ? iL++ : iR++) * 66;
            double lx = left ? t.getX() - 30 : t.getMaxX() + 30;
            g.setColor(HL); g.setStroke(new BasicStroke(2.6f));
            g.draw(new Line2D.Double(px, py, lx, ly));
            g.fill(new Ellipse2D.Double(px - 9, py - 9, 18, 18));
            g.setColor(light ? Color.WHITE : BG); g.fill(new Ellipse2D.Double(px - 4, py - 4, 8, 8));
            float w = widthOf(lab, sansBold, 26) + 28;
            RoundRectangle2D tag = new RoundRectangle2D.Double(left ? lx - w : lx, ly - 22, w, 44, 22, 22);
            g.setColor(light ? Color.WHITE : TEAL_BTN); g.fill(tag);
            g.setColor(HL); g.draw(tag);
            text(g, lab, (float) tag.getX() + 14, (float) ly + 9, sansBold, 26, TEXT);
        }
    }

    /** Number line: title = "min..max", lines = marked points "2|x" (value|label); style = interval "1..3" to shade. */
    private void numberline(Graphics2D g, Element e, Rectangle2D r) {
        String[] rng = nz(e.title()).split("\\.\\.");
        double lo = rng.length == 2 ? parseSigned(rng[0]) : -5, hi = rng.length == 2 ? parseSigned(rng[1]) : 5;
        if (hi <= lo) hi = lo + 10;
        double x0 = r.getX() + 40, x1 = r.getMaxX() - 40, y = r.getCenterY();
        final double flo = lo, fhi = hi;
        java.util.function.DoubleUnaryOperator X = v -> x0 + (v - flo) / (fhi - flo) * (x1 - x0);
        String[] sh = nz(e.style()).split("\\.\\.");
        if (sh.length == 2) {
            g.setColor(new Color(GREEN.getRed(), GREEN.getGreen(), GREEN.getBlue(), 90));
            double a = X.applyAsDouble(parseSigned(sh[0])), b2 = X.applyAsDouble(parseSigned(sh[1]));
            g.fill(new RoundRectangle2D.Double(Math.min(a, b2), y - 12, Math.abs(b2 - a), 24, 12, 12));
        }
        g.setColor(TEXT); g.setStroke(new BasicStroke(3f));
        g.draw(new Line2D.Double(x0 - 20, y, x1 + 20, y));
        arrowHead(g, x1, y, x1 + 30, y, TEXT); arrowHead(g, x0, y, x0 - 30, y, TEXT);
        int span = (int) Math.round(hi - lo), stepv = span <= 12 ? 1 : (int) Math.ceil(span / 10.0);
        for (double v = Math.ceil(lo); v <= hi; v += stepv) {
            double x = X.applyAsDouble(v);
            g.draw(new Line2D.Double(x, y - 12, x, y + 12));
            centered(g, String.valueOf((long) v), x, y + 48, sans, 24, MUTED, 60);
        }
        int i = 0;
        for (String p : listOrEmpty(e)) {
            double v = parseSigned(part(p, 0));
            double x = X.applyAsDouble(v);
            Color c = series()[i++ % series().length];
            g.setColor(c); g.fill(new Ellipse2D.Double(x - 14, y - 14, 28, 28));
            String lab = part(p, 1);
            if (!lab.isEmpty()) centered(g, lab, x, y - 34, sansBold, 28, c, 160);
        }
    }

    private static double parseSigned(String s) {
        try { return Double.parseDouble(s.trim().replace("\u2212", "-").replaceAll("[^0-9.\\-]", "")); } catch (Exception e) { return 0; }
    }

    /** Triangle with labelled sides (lines = ["a|3","b|4","c|5"] for base, height side, hypotenuse) and an angle label (text);
     *  style "right" draws the right-angle mark. */
    private void triangle(Graphics2D g, Element e, Rectangle2D r) {
        double ax = r.getX() + 40, ay = r.getMaxY() - 40, bx = r.getMaxX() - 40, by = ay, cx2 = nz(e.style()).contains("right") ? bx : r.getCenterX(), cy2 = r.getY() + 30;
        Path2D t = new Path2D.Double();
        t.moveTo(ax, ay); t.lineTo(bx, by); t.lineTo(cx2, cy2); t.closePath();
        g.setColor(new Color(CYAN.getRed(), CYAN.getGreen(), CYAN.getBlue(), light ? 35 : 45)); g.fill(t);
        g.setColor(CYAN); g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(t);
        if (nz(e.style()).contains("right")) { g.setStroke(new BasicStroke(2.5f)); g.draw(new Rectangle2D.Double(bx - 30, by - 30, 30, 30)); }
        List<String> sides = listOrEmpty(e);
        double[][] mids = {{(ax + bx) / 2, ay + 42}, {(bx + cx2) / 2 + 40, (by + cy2) / 2}, {(ax + cx2) / 2 - 40, (ay + cy2) / 2 - 10}};
        for (int i = 0; i < Math.min(3, sides.size()); i++) {
            String lab = part(sides.get(i), 0) + (part(sides.get(i), 1).isEmpty() ? "" : " = " + part(sides.get(i), 1));
            centered(g, lab, mids[i][0], mids[i][1], sansBold, 28, series()[i], 220);
        }
        if (!nz(e.text()).isEmpty()) {
            g.setColor(HL); g.setStroke(new BasicStroke(2.5f));
            g.draw(new Arc2D.Double(ax - 50, ay - 50, 100, 100, 0, Math.toDegrees(Math.atan2(ay - cy2, cx2 - ax)), Arc2D.OPEN));
            text(g, nz(e.text()), (float) ax + 60, (float) ay - 14, sansBold, 26, HL);
        }
    }

    /** Fraction bars: lines = ["3/4", "1/2|half a pizza"]. */
    private void fraction(Graphics2D g, Element e, Rectangle2D r) {
        int i = 0;
        for (String row : listOrEmpty(e)) {
            String[] f = part(row, 0).split("/");
            int num = (int) parseNum(f[0]), den = f.length > 1 ? Math.max(1, (int) parseNum(f[1])) : 1;
            double y = r.getY() + 20 + i * 110, x = r.getX() + 160, w = r.getWidth() - 180, cw = w / den;
            Color c = series()[i % series().length];
            for (int k = 0; k < den; k++) {
                RoundRectangle2D cell = new RoundRectangle2D.Double(x + k * cw + 3, y, cw - 6, 70, 10, 10);
                if (k < num) { g.setColor(c); g.fill(cell); }
                g.setColor(c); g.setStroke(new BasicStroke(2.5f)); g.draw(cell);
            }
            text(g, num + "/" + den, (float) r.getX() + 10, (float) y + 50, kidOr(sansBold), 40, c);
            String lab = part(row, 1);
            if (!lab.isEmpty()) text(g, lab, (float) x, (float) y + 100, sans, 22, MUTED);
            i++;
        }
    }

    /** Quiz: title = question, lines = options, style = number of the right option. The answer is revealed from the first
     *  highlightAt sentence on (or straight away when none is given). */
    private void quiz(Graphics2D g, Element e, Rectangle2D r) {
        card(g, r);
        float x = (float) r.getX() + 34, y = (float) r.getY() + 58;
        text(g, "QUIZ", x, y - 4, sansBold, 22, HL);
        y += 26;
        for (String l : wrap(nz(e.title()), kidOr(sansBold), 38, (int) r.getWidth() - 70)) { text(g, l, x, y + 34, kidOr(sansBold), 38, TEXT); y += 46; }
        y += 34;
        int right = (int) parseNum(nz(e.style()));
        int reveal = e.highlight() == null || e.highlight().isEmpty() ? -1 : e.highlight().stream().min(Integer::compare).orElse(-1);
        boolean shown = right > 0 && (reveal < 0 || curStep >= reveal);
        char letter = 'A';
        for (String o : listOrEmpty(e)) {
            boolean ok = shown && letter - 'A' + 1 == right;
            RoundRectangle2D box = new RoundRectangle2D.Double(x, y - 40, r.getWidth() - 68, 66, 33, 33);
            g.setColor(ok ? GREEN : light ? Color.WHITE : TEAL_BTN); g.fill(box);
            g.setColor(ok ? GREEN : TEAL_BTN_EDGE); g.setStroke(new BasicStroke(2.4f)); g.draw(box);
            Color ink = ok ? Color.WHITE : TEXT;
            text(g, letter + ")", x + 22, y + 4, sansBold, 30, ok ? Color.WHITE : HL);
            text(g, fit(o, kidOr(sans), 30, (int) r.getWidth() - 200), x + 76, y + 4, kidOr(sans), 30, ink);
            if (ok) text(g, "\u2713", (float) (r.getMaxX() - 80), y + 6, sansBold, 34, Color.WHITE);
            y += 86;
            letter++;
        }
    }

    /** "Did you know?" fact: title = kicker, text = the fact, icon optional. */
    private void factcard(Graphics2D g, Element e, Rectangle2D r) {
        RoundRectangle2D rr = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 30, 30);
        g.setColor(new Color(HL.getRed(), HL.getGreen(), HL.getBlue(), light ? 40 : 35)); g.fill(rr);
        g.setColor(HL); g.setStroke(new BasicStroke(3f)); g.draw(rr);
        double iconW = e.icon() == null || e.icon().isBlank() ? 0 : 150;
        if (iconW > 0) icon(g, e.icon(), r.getX() + 90, r.getCenterY(), 110, HL);
        float x = (float) (r.getX() + 40 + iconW), y = (float) r.getY() + 60;
        text(g, (nz(e.title()).isEmpty() ? "DID YOU KNOW?" : nz(e.title())).toUpperCase(Locale.ROOT), x, y, sansBold, 24, HL);
        y += 54;
        for (String l : wrap(nz(e.text()), kidOr(sansBold), 40, (int) (r.getWidth() - 80 - iconW))) { text(g, l, x, y, kidOr(sansBold), 40, TEXT); y += 50; }
    }

    /** The kids' guide character "Robo": style = happy | wow | thinking | wave; text = name under it. */
    private void mascot(Graphics2D g, Element e, Rectangle2D r) {
        double s0 = r.getWidth(), cx = r.getCenterX(), top = r.getY() + s0 * 0.12;
        String mood = nz(e.style()).toLowerCase(Locale.ROOT);
        Color body = accent(e.color(), CYAN), dark = body.darker();
        // antenna
        g.setColor(dark); g.setStroke(new BasicStroke((float) (s0 * 0.025), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(cx, top, cx, top - s0 * 0.1));
        g.setColor(PINK); g.fill(new Ellipse2D.Double(cx - s0 * 0.04, top - s0 * 0.15, s0 * 0.08, s0 * 0.08));
        // head
        RoundRectangle2D head = new RoundRectangle2D.Double(cx - s0 * 0.36, top, s0 * 0.72, s0 * 0.5, s0 * 0.22, s0 * 0.22);
        g.setColor(body); g.fill(head);
        g.setColor(dark); g.setStroke(new BasicStroke((float) (s0 * 0.018))); g.draw(head);
        g.setColor(Color.WHITE); g.fill(new RoundRectangle2D.Double(cx - s0 * 0.28, top + s0 * 0.08, s0 * 0.56, s0 * 0.3, s0 * 0.15, s0 * 0.15));
        // eyes
        double ey = top + s0 * 0.2, er = s0 * (mood.contains("wow") ? 0.075 : 0.06);
        g.setColor(new Color(0x2B2340));
        if (mood.contains("think")) {
            g.fill(new Ellipse2D.Double(cx - s0 * 0.14 - er, ey - er * 0.5, er * 2, er));
            g.fill(new Ellipse2D.Double(cx + s0 * 0.14 - er, ey - er * 0.5, er * 2, er));
        } else {
            g.fill(new Ellipse2D.Double(cx - s0 * 0.14 - er, ey - er, er * 2, er * 2));
            g.fill(new Ellipse2D.Double(cx + s0 * 0.14 - er, ey - er, er * 2, er * 2));
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(cx - s0 * 0.14 - er * 0.3, ey - er * 0.6, er * 0.6, er * 0.6));
            g.fill(new Ellipse2D.Double(cx + s0 * 0.14 - er * 0.3, ey - er * 0.6, er * 0.6, er * 0.6));
        }
        // mouth
        g.setColor(PINK); g.setStroke(new BasicStroke((float) (s0 * 0.02), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (mood.contains("wow")) g.fill(new Ellipse2D.Double(cx - s0 * 0.04, top + s0 * 0.28, s0 * 0.08, s0 * 0.07));
        else if (mood.contains("think")) g.draw(new Line2D.Double(cx - s0 * 0.05, top + s0 * 0.31, cx + s0 * 0.06, top + s0 * 0.3));
        else g.draw(new Arc2D.Double(cx - s0 * 0.08, top + s0 * 0.22, s0 * 0.16, s0 * 0.1, 200, 140, Arc2D.OPEN));
        // cheeks
        g.setColor(new Color(PINK.getRed(), PINK.getGreen(), PINK.getBlue(), 90));
        g.fill(new Ellipse2D.Double(cx - s0 * 0.26, top + s0 * 0.27, s0 * 0.07, s0 * 0.04));
        g.fill(new Ellipse2D.Double(cx + s0 * 0.19, top + s0 * 0.27, s0 * 0.07, s0 * 0.04));
        // body + arms
        double by = top + s0 * 0.54;
        RoundRectangle2D torso = new RoundRectangle2D.Double(cx - s0 * 0.26, by, s0 * 0.52, s0 * 0.36, s0 * 0.14, s0 * 0.14);
        g.setColor(body); g.fill(torso); g.setColor(dark); g.setStroke(new BasicStroke((float) (s0 * 0.018))); g.draw(torso);
        g.setColor(ARROW); g.fill(new Ellipse2D.Double(cx - s0 * 0.06, by + s0 * 0.1, s0 * 0.12, s0 * 0.12));
        g.setColor(dark); g.setStroke(new BasicStroke((float) (s0 * 0.04), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(cx - s0 * 0.26, by + s0 * 0.1, cx - s0 * 0.4, by + s0 * 0.26));
        if (mood.contains("wave") || mood.contains("happy")) g.draw(new Line2D.Double(cx + s0 * 0.26, by + s0 * 0.1, cx + s0 * 0.42, by - s0 * 0.08));
        else g.draw(new Line2D.Double(cx + s0 * 0.26, by + s0 * 0.1, cx + s0 * 0.4, by + s0 * 0.26));
        if (!nz(e.text()).isEmpty()) centered(g, nz(e.text()), cx, r.getMaxY() - 4, kidOr(sansBold), 30, TEXT, s0 * 1.4);
    }

    /** Speech bubble (kids): text; style "left"/"right" = which side the tail points to. */
    private void speech(Graphics2D g, Element e, Rectangle2D r) {
        boolean tailLeft = !nz(e.style()).toLowerCase(Locale.ROOT).contains("right");
        RoundRectangle2D bubble = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 50, 50);
        Path2D tail = new Path2D.Double();
        double ty = r.getMaxY() - 30;
        if (tailLeft) { tail.moveTo(r.getX() + 40, ty - 10); tail.lineTo(r.getX() - 34, r.getMaxY() + 14); tail.lineTo(r.getX() + 80, r.getMaxY() - 4); }
        else { tail.moveTo(r.getMaxX() - 40, ty - 10); tail.lineTo(r.getMaxX() + 34, r.getMaxY() + 14); tail.lineTo(r.getMaxX() - 80, r.getMaxY() - 4); }
        tail.closePath();
        // tail first (filled + outlined), then the bubble on top so the join between them disappears
        g.setColor(light ? Color.WHITE : TEAL_BTN); g.fill(tail);
        g.setColor(TEXT); g.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(tail);
        g.setColor(light ? Color.WHITE : TEAL_BTN); g.fill(bubble);
        g.setColor(TEXT); g.draw(bubble);
        float y = (float) r.getY() + 64;
        for (String l : wrap(nz(e.text()), kidOr(sansBold), 36, (int) r.getWidth() - 60)) { centered(g, l, r.getCenterX(), y, kidOr(sansBold), 36, TEXT, r.getWidth() - 40); y += 46; }
    }

    /** Celebration star badge: text ("Great job!"). */
    private void badge(Graphics2D g, Element e, Rectangle2D r) {
        double cx = r.getCenterX(), cy = r.getCenterY(), ro = Math.min(r.getWidth(), r.getHeight()) / 2, ri = ro * 0.78;
        Path2D star = new Path2D.Double();
        int pts = 16;
        for (int i = 0; i < pts * 2; i++) {
            double a = -Math.PI / 2 + i * Math.PI / pts, rad = i % 2 == 0 ? ro : ri;
            if (i == 0) star.moveTo(cx + Math.cos(a) * rad, cy + Math.sin(a) * rad); else star.lineTo(cx + Math.cos(a) * rad, cy + Math.sin(a) * rad);
        }
        star.closePath();
        g.setColor(ARROW); g.fill(star);
        g.setColor(HL.darker()); g.setStroke(new BasicStroke(3f)); g.draw(star);
        g.setColor(Color.WHITE); g.fill(new Ellipse2D.Double(cx - ri * 0.82, cy - ri * 0.82, ri * 1.64, ri * 1.64));
        List<String> ls = wrap(nz(e.text()), kidOr(sansBold), 36, (int) (ri * 1.4));
        float y = (float) (cy - (ls.size() - 1) * 21 + 12);
        for (String l : ls) { centered(g, l, cx, y, kidOr(sansBold), 36, new Color(0x2B2340), ri * 1.5); y += 42; }
    }


    // ------------------------------------------------------------------ ages 2-5 and 10-15

    /** "Pip", the little friend for ages 2-5: a soft round body, big eyes looking at the viewer. style = happy | wow | listen | wave. */
    private void pip(Graphics2D g, Element e, Rectangle2D r) {
        double s0 = r.getWidth(), cx = r.getCenterX(), cy = r.getY() + s0 * 0.55;
        String mood = nz(e.style()).toLowerCase(Locale.ROOT);
        Color body = accent(e.color(), new Color(0xFFB000));
        g.setColor(new Color(0, 0, 0, 25)); g.fill(new Ellipse2D.Double(cx - s0 * 0.38, cy + s0 * 0.38, s0 * 0.76, s0 * 0.1));
        Path2D blob = new Path2D.Double();
        blob.moveTo(cx, cy - s0 * 0.45);
        blob.curveTo(cx + s0 * 0.42, cy - s0 * 0.45, cx + s0 * 0.48, cy + s0 * 0.42, cx, cy + s0 * 0.42);
        blob.curveTo(cx - s0 * 0.48, cy + s0 * 0.42, cx - s0 * 0.42, cy - s0 * 0.45, cx, cy - s0 * 0.45);
        g.setColor(body); g.fill(blob);
        g.setColor(body.darker()); g.setStroke(new BasicStroke((float) (s0 * 0.02))); g.draw(blob);
        // arms
        g.setStroke(new BasicStroke((float) (s0 * 0.05), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(cx - s0 * 0.4, cy + s0 * 0.05, cx - s0 * 0.5, cy + s0 * 0.16));
        if (mood.contains("wave") || mood.contains("wow")) g.draw(new Line2D.Double(cx + s0 * 0.4, cy - s0 * 0.02, cx + s0 * 0.52, cy - s0 * 0.2));
        else g.draw(new Line2D.Double(cx + s0 * 0.4, cy + s0 * 0.05, cx + s0 * 0.5, cy + s0 * 0.16));
        // big eyes (looking at the child), highlights
        double er = s0 * (mood.contains("wow") ? 0.11 : 0.095);
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            double ex = cx + sgn * s0 * 0.15, ey = cy - s0 * 0.1;
            g.setColor(Color.WHITE); g.fill(new Ellipse2D.Double(ex - er, ey - er, er * 2, er * 2));
            g.setColor(new Color(0x2A2238)); g.fill(new Ellipse2D.Double(ex - er * 0.6, ey - er * 0.55, er * 1.2, er * 1.2));
            g.setColor(Color.WHITE); g.fill(new Ellipse2D.Double(ex - er * 0.3, ey - er * 0.45, er * 0.45, er * 0.45));
        }
        g.setColor(new Color(0xF0436C));
        if (mood.contains("wow")) g.fill(new Ellipse2D.Double(cx - s0 * 0.05, cy + s0 * 0.08, s0 * 0.1, s0 * 0.12));
        else if (mood.contains("listen")) { g.setStroke(new BasicStroke((float) (s0 * 0.025), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(new Line2D.Double(cx - s0 * 0.04, cy + s0 * 0.14, cx + s0 * 0.04, cy + s0 * 0.14)); }
        else { g.setStroke(new BasicStroke((float) (s0 * 0.025), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(new Arc2D.Double(cx - s0 * 0.1, cy + s0 * 0.02, s0 * 0.2, s0 * 0.16, 200, 140, Arc2D.OPEN)); }
        g.setColor(new Color(240, 67, 108, 70));
        g.fill(new Ellipse2D.Double(cx - s0 * 0.3, cy + s0 * 0.06, s0 * 0.09, s0 * 0.05));
        g.fill(new Ellipse2D.Double(cx + s0 * 0.21, cy + s0 * 0.06, s0 * 0.09, s0 * 0.05));
        if (!nz(e.text()).isEmpty()) centered(g, nz(e.text()), cx, r.getMaxY() + 44, kidOr(sansBold), 36, TEXT, s0 * 1.4);
    }

    /** One huge thing in the middle and its word underneath (ages 2-5): icon, text = the word, color. */
    private void bigpic(Graphics2D g, Element e, Rectangle2D r) {
        double d = r.getWidth();
        Color c = accent(e.color(), CYAN);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40));
        g.fill(new Ellipse2D.Double(r.getX(), r.getY(), d, d));
        icon(g, icon(e, "sparkle"), r.getCenterX(), r.getY() + d / 2, d * 0.6, c);
        centered(g, nz(e.text()), r.getCenterX(), r.getMaxY() - 16, kidOr(sansBold), (int) Math.min(96, d * 0.2), TEXT, r.getWidth() * 1.3);
    }

    /** Counting: text = how many (1-10), icon = what is counted; each item gets its number under it. */
    private void count(Graphics2D g, Element e, Rectangle2D r) {
        int n = (int) Math.max(1, Math.min(10, parseNum(nz(e.text()))));
        double c = r.getWidth() / n;
        for (int i = 0; i < n; i++) {
            Color col = series()[i % series().length];
            icon(g, icon(e, "apple"), r.getX() + c * i + c / 2, r.getY() + c / 2, c * 0.92, col);
            centered(g, String.valueOf(i + 1), r.getX() + c * i + c / 2, r.getY() + c + 70, kidOr(sansBold), (int) Math.min(72, c * 0.45), col, c);
        }
    }

    /** "Which one is the ...?" - big picture options (lines = "label|icon"), style = number of the right one; it lights up from the
     *  first highlightAt sentence (the answer beat). No reading needed. */
    private void choose(Graphics2D g, Element e, Rectangle2D r) {
        List<String> ops = listOrEmpty(e);
        int n = Math.max(1, ops.size()), right = (int) parseNum(nz(e.style()));
        int reveal = e.highlight() == null || e.highlight().isEmpty() ? -1 : e.highlight().stream().min(Integer::compare).orElse(-1);
        boolean shown = right > 0 && (reveal < 0 || curStep >= reveal);
        double cw = r.getWidth() / n;
        for (int i = 0; i < n; i++) {
            boolean ok = shown && i + 1 == right;
            double x = r.getX() + cw * i + 16, w = cw - 32, h = r.getHeight() - 20;
            RoundRectangle2D card = new RoundRectangle2D.Double(x, r.getY(), w, h, 48, 48);
            g.setColor(ok ? new Color(mix(GREEN.getRed(), 255, 0.72), mix(GREEN.getGreen(), 255, 0.72), mix(GREEN.getBlue(), 255, 0.72)) : Color.WHITE); g.fill(card);   // opaque over pictures
            g.setColor(ok ? GREEN : new Color(0xD9D2C3)); g.setStroke(new BasicStroke(ok ? 9f : 5f)); g.draw(card);
            String label = part(ops.get(i), 0), second = part(ops.get(i), 1);
            boolean glyph = label.codePointCount(0, label.length()) <= 2 && !label.isBlank() && (colourNamed(second, null) != null || !hasIcon(second));   // a letter, digit or emoji (second = a colour or nothing): draw IT, big
            if (glyph) {
                Color gc = colourNamed(second, series()[i % series().length]);
                int fs = (int) Math.min(w, h) * 6 / 10;
                centered(g, label, x + w / 2, r.getY() + h / 2 + fs * 0.36, kidOr(sansBold), fs, gc, w - 20);
            } else {
                String ic = second.isEmpty() ? "sparkle" : second;
                icon(g, ic, x + w / 2, r.getY() + h * 0.42, Math.min(w, h) * 0.5, series()[i % series().length]);
                centered(g, label, x + w / 2, r.getY() + h - 30, kidOr(sansBold), 40, TEXT, w - 20);
            }
            if (ok) icon(g, "check", x + w - 40, r.getY() + 40, 60, GREEN);
        }
    }

    /** A colour word ("red", "blue" ...) as a colour, otherwise {@code fallback}. */
    private static Color colourNamed(String name, Color fallback) {
        return switch (name == null ? "" : name.trim().toLowerCase(Locale.ROOT)) {
            case "red" -> new Color(0xE5484D); case "blue" -> new Color(0x2F80ED); case "green" -> new Color(0x2FB344); case "yellow" -> new Color(0xF5B700);
            case "orange" -> new Color(0xF2711C); case "purple", "violet" -> new Color(0x8E5CF7); case "pink" -> new Color(0xFF5D8F); case "brown" -> new Color(0x8D5A2B);
            default -> fallback;
        };
    }

    /** Big and small (ages 2-5): lines = ["big label", "small label"], icon = the thing. */
    private void sizes(Graphics2D g, Element e, Rectangle2D r) {
        List<String> ls = listOrEmpty(e);
        String ic = icon(e, "apple");
        double big = r.getHeight() * 0.68, small = big * 0.38;
        icon(g, ic, r.getX() + r.getWidth() * 0.3, r.getY() + big / 2 + 10, big, PINK);
        icon(g, ic, r.getX() + r.getWidth() * 0.75, r.getY() + big - small / 2 + 10, small, CYAN);
        centered(g, ls.size() > 0 ? ls.get(0) : "BIG", r.getX() + r.getWidth() * 0.3, r.getMaxY() - 10, kidOr(sansBold), 60, PINK, r.getWidth() * 0.45);
        centered(g, ls.size() > 1 ? ls.get(1) : "small", r.getX() + r.getWidth() * 0.75, r.getMaxY() - 10, kidOr(sansBold), 40, CYAN, r.getWidth() * 0.4);
    }

    /** Level header with an XP bar (ages 10-15): title = "LEVEL 2", text = what this level unlocks, style = progress percent. */
    private void level(Graphics2D g, Element e, Rectangle2D r) {
        double pct = Math.max(0, Math.min(100, parseNum(nz(e.style()))));
        if (overlayOnly) {   // over an illustration: a dark glass plate keeps the header readable on any picture
            g.setColor(new Color(13, 11, 30, 170));
            g.fill(new RoundRectangle2D.Double(r.getX() - 22, r.getY() - 18, r.getWidth() + 44, r.getHeight() + 30, 22, 22));
        }
        String lv = (nz(e.title()).isEmpty() ? "LEVEL" : nz(e.title())).toUpperCase(Locale.ROOT);
        float lw = widthOf(lv, sansBold, 26) + 36;
        RoundRectangle2D tag = new RoundRectangle2D.Double(r.getX(), r.getY(), lw, 46, 10, 10);
        g.setColor(PINK); g.fill(tag);
        text(g, lv, (float) r.getX() + 18, (float) r.getY() + 32, sansBold, 26, Color.WHITE);
        text(g, fit(nz(e.text()), sansBold, 40, (int) (r.getWidth() - lw - 30)), (float) (r.getX() + lw + 22), (float) r.getY() + 36, sansBold, 40, TEXT);
        double by = r.getY() + 84, bw = r.getWidth() - 110;
        g.setColor(new Color(255, 255, 255, 30)); g.fill(new RoundRectangle2D.Double(r.getX(), by, bw, 22, 22, 22));
        g.setPaint(new GradientPaint((float) r.getX(), 0, CYAN, (float) (r.getX() + bw), 0, GREEN));
        g.fill(new RoundRectangle2D.Double(r.getX(), by, Math.max(22, bw * pct / 100), 22, 22, 22));
        text(g, (int) pct + " XP", (float) (r.getX() + bw + 16), (float) by + 20, mono, 22, GREEN);
    }

    /** Challenge card (ages 10-15): title = the puzzle, lines = options, style = right option, text = timer label ("10 s");
     *  answer revealed from the first highlightAt sentence - the curiosity gap closes on screen. */
    private void challenge(Graphics2D g, Element e, Rectangle2D r) {
        RoundRectangle2D rr = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 24, 24);
        g.setColor(new Color(22, 18, 58, 235)); g.fill(rr);
        g.setColor(PINK); g.setStroke(new BasicStroke(3f)); g.draw(rr);
        float x = (float) r.getX() + 34, y = (float) r.getY() + 54;
        text(g, "CHALLENGE", x, y, mono, 24, PINK);
        String timer = nz(e.text());
        if (!timer.isEmpty()) {
            float tw = widthOf("\u23F1 " + timer, mono, 24);
            text(g, "\u23F1 " + timer, (float) (r.getMaxX() - 34 - tw), y, mono, 24, CYAN);
        }
        y += 22;
        for (String l : wrap(nz(e.title()), sansBold, 40, (int) r.getWidth() - 68)) { y += 48; text(g, l, x, y, sansBold, 40, TEXT); }
        y += 76;
        int right = (int) parseNum(nz(e.style()));
        int reveal = e.highlight() == null || e.highlight().isEmpty() ? -1 : e.highlight().stream().min(Integer::compare).orElse(-1);
        boolean shown = right > 0 && (reveal < 0 || curStep >= reveal);
        char letter = 'A';
        for (String o : listOrEmpty(e)) {
            boolean ok = shown && letter - 'A' + 1 == right;
            RoundRectangle2D box = new RoundRectangle2D.Double(x, y - 38, r.getWidth() - 68, 58, 12, 12);
            g.setColor(ok ? new Color(GREEN.getRed(), GREEN.getGreen(), GREEN.getBlue(), 60) : new Color(255, 255, 255, 12)); g.fill(box);
            g.setColor(ok ? GREEN : TEAL_BTN_EDGE); g.setStroke(new BasicStroke(2f)); g.draw(box);
            text(g, letter + "", x + 20, y + 2, mono, 28, ok ? GREEN : CYAN);
            text(g, fit(o, sans, 28, (int) r.getWidth() - 170), x + 64, y + 2, sans, 28, TEXT);
            y += 70;
            letter++;
        }
    }


    /** Puffy cloud banner (as on kids' posters): text = big title, title = small subtitle ("Explore · Play · Learn"). */
    private void cloudtitle(Graphics2D g, Element e, Rectangle2D r) {
        Color fill = themeId.equals("teen") ? new Color(0xDDF4FF) : themeId.equals("kids") ? new Color(0xFFF7D6) : new Color(0xFFF3B8);
        double x = r.getX(), y = r.getY(), w = r.getWidth(), h = r.getHeight();
        java.awt.geom.Area cloud = new java.awt.geom.Area(new RoundRectangle2D.Double(x + w * 0.04, y + h * 0.22, w * 0.92, h * 0.72, h * 0.6, h * 0.6));
        double[][] puffs = {{0.08, 0.30, 0.30}, {0.24, 0.12, 0.42}, {0.46, 0.04, 0.48}, {0.66, 0.10, 0.42}, {0.82, 0.26, 0.32}};
        for (double[] p : puffs) cloud.add(new java.awt.geom.Area(new Ellipse2D.Double(x + w * p[0] - h * p[2] * 0.5, y + h * p[1], h * p[2] * 1.6, h * p[2] * 1.6)));
        g.setColor(new Color(0, 0, 0, 40));
        g.fill(java.awt.geom.AffineTransform.getTranslateInstance(0, 10).createTransformedShape(cloud));
        g.setColor(fill); g.fill(cloud);
        g.setColor(new Color(255, 255, 255, 200)); g.setStroke(new BasicStroke(4f)); g.draw(cloud);
        Font f = kidOr(sansBold);
        boolean sub = !nz(e.title()).isEmpty();
        int size = 84;
        while (size > 40 && widthOf(nz(e.text()), f, size) > w * 0.8) size -= 4;
        Color ink = new Color(0x1C2A6B);
        centered(g, nz(e.text()), r.getCenterX(), y + h * (sub ? 0.56 : 0.66), f, size, ink, w * 0.84);
        if (sub) {
            int ss = 44;
            while (ss > 24 && widthOf(nz(e.title()), f, ss) > w * 0.8) ss -= 2;
            centered(g, nz(e.title()), r.getCenterX(), y + h * 0.84, f, ss, ink, w * 0.84);
        }
    }

    /** Row of glossy round icon badges with labels: lines = "Letters|ABC|pink" or "Coding|code|violet" (label|icon or short text|colour). */
    private void iconrow(Graphics2D g, Element e, Rectangle2D r) {
        List<String> items = listOrEmpty(e);
        int n = Math.max(1, items.size());
        double cw = r.getWidth() / n, d = Math.min(cw * 0.72, r.getHeight() - 80);
        Color[] def = {new Color(0xF0436C), new Color(0x22B455), new Color(0x7A4DFF), new Color(0xFF8A00), new Color(0x1E90FF), new Color(0x14B8A6)};
        for (int i = 0; i < items.size(); i++) {
            String label = part(items.get(i), 0), sym = part(items.get(i), 1), col = part(items.get(i), 2);
            Color c = col.isEmpty() ? def[i % def.length] : accent(col, def[i % def.length]);
            double cx = r.getX() + cw * i + cw / 2, cy = r.getY() + d / 2 + 6;
            boolean teen = themeId.equals("teen");
            if (teen) {   // neon ring badge for 10-15
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60)); g.fill(new Ellipse2D.Double(cx - d / 2 - 8, cy - d / 2 - 8, d + 16, d + 16));
            }
            g.setPaint(new RadialGradientPaint(new Point2D.Double(cx - d * 0.2, cy - d * 0.25), (float) (d * 0.8), new float[]{0f, 1f}, new Color[]{c.brighter(), c}));
            g.fill(new Ellipse2D.Double(cx - d / 2, cy - d / 2, d, d));
            g.setColor(new Color(255, 255, 255, 230)); g.setStroke(new BasicStroke(teen ? 4f : 6f)); g.draw(new Ellipse2D.Double(cx - d / 2, cy - d / 2, d, d));
            String s1 = sym.isEmpty() ? label.substring(0, Math.min(1, label.length())) : sym;
            if (!hasIcon(s1) && s1.length() > 3 && s1.matches("[A-Za-z]+")) s1 = s1.substring(0, 1).toUpperCase(Locale.ROOT);
            if (hasIcon(s1)) icon(g, s1, cx, cy, d * 0.5, Color.WHITE);
            else centered(g, s1, cx, cy + d * 0.14, kidOr(sansBold), (int) Math.min(d * 0.36, 60), Color.WHITE, d * 0.85);
            // label on a soft pill so it reads over any illustration
            Font lf = kidOr(sansBold);
            float lw = Math.min((float) cw - 8, widthOf(label, lf, 30) + 30);
            RoundRectangle2D pill = new RoundRectangle2D.Double(cx - lw / 2, cy + d / 2 + 14, lw, 46, 23, 23);
            g.setColor(teen ? new Color(13, 11, 30, 190) : new Color(255, 255, 255, 215)); g.fill(pill);
            centered(g, label, cx, cy + d / 2 + 47, lf, 30, teen ? new Color(0xF2F0FF) : new Color(0x1C2A6B), lw - 12);
        }
    }


    // ------------------------------------------------------------------ technology / science / business diagrams

    /** Label on a soft pill of the background colour, so it stays readable where it crosses a line or a curve. */
    private void tagText(Graphics2D g, String t, double x, double baseline, int size, Color col, int align) {
        float w = widthOf(t, sansBold, size);
        double x0 = align == 0 ? x - w / 2 : align > 0 ? x - w : x;          // 0 centred, >0 right-aligned at x, <0 left-aligned at x
        g.setColor(new Color(BG.getRed(), BG.getGreen(), BG.getBlue(), 222));
        g.fill(new RoundRectangle2D.Double(x0 - 8, baseline - size * 0.95, w + 16, size * 1.3, 12, 12));
        text(g, t, (float) x0, (float) baseline, sansBold, size, col);
    }

    /** Flowchart nodes "id|text|kind" (kind: start, process, decision, end, io) and edges text "a>b, b>c:yes, b>d:no". */
    private record FlowNode(String id, String text, String kind) { }

    private List<FlowNode> flowNodes(Element e) {
        List<FlowNode> out = new ArrayList<>();
        for (String row : listOrEmpty(e)) {
            String id = part(row, 0), tx = part(row, 1), k = part(row, 2).toLowerCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            out.add(new FlowNode(id, tx.isEmpty() ? id : tx, k.isEmpty() ? (out.isEmpty() ? "start" : "process") : k));
        }
        return out;
    }

    private List<String[]> flowEdges(Element e, List<FlowNode> nodes) {
        List<String[]> out = new ArrayList<>();
        String spec = nz(e.text());
        if (spec.isBlank()) { for (int i = 0; i + 1 < nodes.size(); i++) out.add(new String[]{nodes.get(i).id(), nodes.get(i + 1).id(), ""}); return out; }
        for (String part : spec.split(",")) {
            String[] ab = part.split(">", 2);
            if (ab.length < 2) continue;
            String[] bl = ab[1].split(":", 2);
            out.add(new String[]{ab[0].trim(), bl[0].trim(), bl.length > 1 ? bl[1].trim() : ""});
        }
        return out;
    }

    /** {layers, widest layer} - the same layout the drawing uses, so the box can be sized before drawing. */
    private int[] flowShape(Element e) {
        List<FlowNode> nodes = flowNodes(e);
        int[] layer = flowLayers(nodes, flowEdges(e, nodes));
        int max = 0, widest = 1;
        Map<Integer, Integer> per = new HashMap<>();
        for (int l : layer) { max = Math.max(max, l + 1); widest = Math.max(widest, per.merge(l, 1, Integer::sum)); }
        return new int[]{Math.max(1, max), widest};
    }

    private int[] flowLayers(List<FlowNode> nodes, List<String[]> edges) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) idx.put(nodes.get(i).id(), i);
        int[] layer = new int[nodes.size()];
        for (int pass = 0; pass < nodes.size(); pass++) {
            boolean changed = false;
            for (String[] ed : edges) {
                Integer a = idx.get(ed[0]), b = idx.get(ed[1]);
                if (a == null || b == null || b <= a) continue;                    // an edge back to an earlier node is a loop, not a layer
                if (layer[b] < layer[a] + 1) { layer[b] = layer[a] + 1; changed = true; }
            }
            if (!changed) break;
        }
        return layer;
    }

    private void flowchart(Graphics2D g, Element e, Rectangle2D r) {
        List<FlowNode> nodes = flowNodes(e);
        if (nodes.isEmpty()) return;
        List<String[]> edges = flowEdges(e, nodes);
        int[] layer = flowLayers(nodes, edges);
        int layers = 0;
        for (int l : layer) layers = Math.max(layers, l + 1);
        Map<Integer, List<Integer>> byLayer = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) byLayer.computeIfAbsent(layer[i], k -> new ArrayList<>()).add(i);
        int widest = 1;
        for (var l : byLayer.values()) widest = Math.max(widest, l.size());
        double usable = r.getWidth() - 140, nodeW = Math.min(310, usable / widest - 30), rowH = (r.getHeight() - 20) / layers;
        double[][] c = new double[nodes.size()][2];
        double[] hh = new double[nodes.size()], ww = new double[nodes.size()];
        for (var en : byLayer.entrySet()) {
            List<Integer> row = en.getValue();
            for (int k = 0; k < row.size(); k++) {
                int i = row.get(k);
                c[i][0] = r.getX() + 60 + usable * (k + 0.5) / row.size();
                c[i][1] = r.getY() + 10 + rowH * (en.getKey() + 0.5);
                boolean dec = nodes.get(i).kind().startsWith("dec");
                ww[i] = dec ? nodeW * 1.12 : nodeW;
                hh[i] = dec ? 104 : 78;
            }
        }
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) idx.put(nodes.get(i).id(), i);
        // edges first, so the boxes sit on top
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (String[] ed : edges) {
            Integer a = idx.get(ed[0]), b = idx.get(ed[1]);
            if (a == null || b == null) continue;
            Color col = ed[2].toLowerCase(Locale.ROOT).startsWith("n") ? PINK : ed[2].isEmpty() ? MUTED : GREEN;
            g.setColor(col);
            double sx, sy, ex, ey;
            Path2D path = new Path2D.Double();
            if (b > a && layer[b] > layer[a]) {
                sx = c[a][0]; sy = c[a][1] + hh[a] / 2; ex = c[b][0]; ey = c[b][1] - hh[b] / 2;
                if (nodes.get(a).kind().startsWith("dec") && Math.abs(sx - ex) > 8) { sx = c[a][0] + Math.signum(ex - sx) * ww[a] / 2; sy = c[a][1]; }
                path.moveTo(sx, sy);
                if (sy == c[a][1]) path.lineTo(ex, sy); else path.lineTo(sx, ey - 20);
                path.lineTo(ex, ey - 0.01);
                if (sy == c[a][1]) { path.reset(); path.moveTo(sx, sy); path.lineTo(ex, sy); path.lineTo(ex, ey); }
                else if (Math.abs(sx - ex) > 1) { path.reset(); path.moveTo(sx, sy); path.lineTo(sx, (sy + ey) / 2); path.lineTo(ex, (sy + ey) / 2); path.lineTo(ex, ey); }
                g.draw(path);
                arrowHead(g, ex, ey - 22, ex, ey, col);
            } else {                                                   // a loop back: out of the right side, up, and into the target
                double right = Math.max(c[a][0] + ww[a] / 2, c[b][0] + ww[b] / 2) + 44;
                sx = c[a][0] + ww[a] / 2; sy = c[a][1]; ex = c[b][0] + ww[b] / 2; ey = c[b][1];
                path.moveTo(sx, sy); path.lineTo(right, sy); path.lineTo(right, ey); path.lineTo(ex + 2, ey);
                g.draw(path);
                arrowHead(g, ex + 24, ey, ex, ey, col);
            }
            if (!ed[2].isEmpty()) {
                double lx = (b > a && layer[b] > layer[a]) ? (sx + ex) / 2 + (Math.abs(sx - ex) < 2 ? 22 : 0) : Math.max(c[a][0] + ww[a] / 2, c[b][0] + ww[b] / 2) + 44;
                double ly = (b > a && layer[b] > layer[a]) ? (sy + ey) / 2 - (Math.abs(sx - ex) < 2 ? 0 : 6) : (c[a][1] + c[b][1]) / 2;
                float lw = widthOf(ed[2], sansBold, 27) + 18;
                g.setColor(light ? Color.WHITE : BG);
                g.fill(new RoundRectangle2D.Double(lx - lw / 2, ly - 22, lw, 36, 18, 18));
                centered(g, ed[2], lx, ly + 6, sansBold, 27, col, lw);
            }
        }
        int i = 0;
        for (FlowNode n : nodes) {
            Color col = series()[i % series().length];
            String k = n.kind();
            double x = c[i][0], y = c[i][1], w = ww[i], h = hh[i];
            Shape shape;
            if (k.startsWith("dec")) { Path2D d = new Path2D.Double(); d.moveTo(x, y - h / 2); d.lineTo(x + w / 2, y); d.lineTo(x, y + h / 2); d.lineTo(x - w / 2, y); d.closePath(); shape = d; }
            else if (k.startsWith("start") || k.startsWith("end")) shape = new RoundRectangle2D.Double(x - w / 2, y - h / 2, w, h, h, h);
            else if (k.startsWith("io")) { Path2D d = new Path2D.Double(); d.moveTo(x - w / 2 + 22, y - h / 2); d.lineTo(x + w / 2 + 22, y - h / 2); d.lineTo(x + w / 2 - 22, y + h / 2); d.lineTo(x - w / 2 - 22, y + h / 2); d.closePath(); shape = d; }
            else shape = new RoundRectangle2D.Double(x - w / 2, y - h / 2, w, h, 16, 16);
            g.setColor(light ? Color.WHITE : TEAL_BTN); g.fill(shape);
            g.setColor(col); g.setStroke(new BasicStroke(3.2f)); g.draw(shape);
            List<String> ls = wrap(n.text(), sansBold, 29, (int) (w * (k.startsWith("dec") ? 0.58 : 0.84)));
            float ty = (float) (y - (Math.min(2, ls.size()) - 1) * 18 + 11);
            for (int q = 0; q < Math.min(2, ls.size()); q++) { centered(g, ls.get(q), x, ty, sansBold, 29, TEXT, w * 0.86); ty += 36; }
            i++;
        }
    }

    /** Sequence diagram: lines "Name|icon" are the participants, text "A>B:request, B>A:response!" the messages (a trailing ! = dashed reply). */
    private void sequence(Graphics2D g, Element e, Rectangle2D r) {
        List<String> parts = listOrEmpty(e);
        int n = parts.size();
        if (n < 2) return;
        double colW = r.getWidth() / n;
        double headH = 96, top = r.getY() + 6, bottom = r.getMaxY() - 8;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = r.getX() + colW * (i + 0.5);
            Color col = series()[i % series().length];
            g.setColor(new Color(MUTED.getRed(), MUTED.getGreen(), MUTED.getBlue(), 120));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{8, 8}, 0));
            g.draw(new Line2D.Double(x[i], top + headH, x[i], bottom));
            RoundRectangle2D box = new RoundRectangle2D.Double(x[i] - Math.min(135, colW * 0.42), top, Math.min(270, colW * 0.84), headH, 20, 20);
            g.setColor(light ? Color.WHITE : TEAL_BTN); g.fill(box);
            g.setColor(col); g.setStroke(new BasicStroke(3f)); g.draw(box);
            String ic = part(parts.get(i), 1);
            if (!ic.isEmpty() && hasIcon(ic)) { icon(g, ic, box.getX() + 44, top + headH / 2, 48, col); centered(g, part(parts.get(i), 0), x[i] + 22, top + headH / 2 + 10, sansBold, 30, TEXT, box.getWidth() - 100); }
            else centered(g, part(parts.get(i), 0), x[i], top + headH / 2 + 10, sansBold, 30, TEXT, box.getWidth() - 20);
        }
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(part(parts.get(i), 0).toLowerCase(Locale.ROOT), i);
        String[] msgs = nz(e.text()).isBlank() ? new String[0] : nz(e.text()).split(",");
        double y = top + headH + 64, step = Math.max(76, (bottom - top - headH - 80) / Math.max(1, msgs.length));
        int k = 0;
        for (String msg : msgs) {
            String m = msg.trim();
            boolean dashed = m.endsWith("!");
            if (dashed) m = m.substring(0, m.length() - 1);
            String[] ab = m.split(">", 2);
            if (ab.length < 2) continue;
            String[] bl = ab[1].split(":", 2);
            Integer a = idx.get(ab[0].trim().toLowerCase(Locale.ROOT)), b = idx.get(bl[0].trim().toLowerCase(Locale.ROOT));
            if (a == null || b == null || a.equals(b)) continue;
            Color col = dashed ? MUTED : series()[(k++) % 3];
            g.setColor(col);
            g.setStroke(dashed ? new BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{10, 8}, 0) : new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double sx = x[a] + Math.signum(x[b] - x[a]) * 6, ex = x[b] - Math.signum(x[b] - x[a]) * 6;
            g.draw(new Line2D.Double(sx, y, ex, y));
            arrowHead(g, ex - Math.signum(ex - sx) * 22, y, ex, y, col);
            if (bl.length > 1) centered(g, bl[1].trim(), (sx + ex) / 2, y - 14, sans, 28, TEXT, Math.abs(ex - sx) - 20);
            y += step;
        }
    }

    /** Data structures. style = array | stack | queue | linkedlist | hash (":2" after it highlights item 2). lines = the values (hash: "bucket|k:v,k:v"). */
    private void datastruct(Graphics2D g, Element e, Rectangle2D r) {
        String style = nz(e.style()).toLowerCase(Locale.ROOT);
        int hl = -1;
        int colon = style.indexOf(':');
        if (colon > 0) { hl = (int) parseNum(style.substring(colon + 1)); style = style.substring(0, colon); }
        List<String> vals = listOrEmpty(e);
        int n = vals.size();
        if (n == 0) return;
        if (style.startsWith("stack")) {
            double cw = Math.min(r.getWidth() * 0.8, 380), ch = 78, y = r.getY() + 60;
            for (int i = 0; i < n; i++) {
                Color col = i == hl ? HL : series()[0];
                RoundRectangle2D cell = new RoundRectangle2D.Double(r.getCenterX() - cw / 2, y + i * (ch + 6), cw, ch, 12, 12);
                g.setColor(i == hl ? new Color(HL.getRed(), HL.getGreen(), HL.getBlue(), 90) : light ? Color.WHITE : TEAL_BTN); g.fill(cell);
                g.setColor(col); g.setStroke(new BasicStroke(i == hl ? 4f : 2.6f)); g.draw(cell);
                centered(g, vals.get(i), r.getCenterX(), y + i * (ch + 6) + 52, sansBold, 38, TEXT, cw - 20);
            }
            g.setColor(PINK); g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double ax = r.getCenterX() + cw / 2 + 16;
            g.draw(new Line2D.Double(ax + 56, y + ch / 2, ax + 4, y + ch / 2)); arrowHead(g, ax + 24, y + ch / 2, ax, y + ch / 2, PINK);
            text(g, "TOP", (float) ax + 62, (float) (y + ch / 2 + 11), sansBold, 32, PINK);
            return;
        }
        if (style.startsWith("hash")) {
            double rowH = 66, y = r.getY() + 24, kw = 110;
            for (int i = 0; i < n; i++) {
                String bucket = part(vals.get(i), 0), items = part(vals.get(i), 1);
                RoundRectangle2D head = new RoundRectangle2D.Double(r.getX() + 20, y + i * (rowH + 8), kw, rowH, 10, 10);
                g.setColor(series()[1]); g.fill(head);
                centered(g, bucket, head.getCenterX(), head.getCenterY() + 12, sansBold, 34, light ? Color.WHITE : BG, kw);
                double x = r.getX() + 20 + kw + 26;
                if (items.isEmpty()) { text(g, "empty", (float) x, (float) (y + i * (rowH + 8) + 36), sans, 24, MUTED); continue; }
                for (String it : items.split(",")) {
                    float w = Math.max(150, widthOf(it.trim(), sansBold, 32) + 44);
                    RoundRectangle2D cell = new RoundRectangle2D.Double(x, y + i * (rowH + 8), w, rowH, 12, 12);
                    g.setColor(light ? Color.WHITE : TEAL_BTN); g.fill(cell);
                    g.setColor(series()[0]); g.setStroke(new BasicStroke(2.6f)); g.draw(cell);
                    centered(g, it.trim(), x + w / 2, y + i * (rowH + 8) + 46, sansBold, 32, TEXT, w - 10);
                    g.setColor(MUTED); g.draw(new Line2D.Double(x + w, y + i * (rowH + 8) + rowH / 2, x + w + 22, y + i * (rowH + 8) + rowH / 2));
                    x += w + 26;
                }
            }
            return;
        }
        boolean linked = style.startsWith("linked"), queue = style.startsWith("queue");
        double cw = linked ? Math.min(190, (r.getWidth() - 160) / n - 40) : Math.min(140, (r.getWidth() - 30) / n - 6), ch = linked ? 98 : 108;
        double gap = linked ? 36 : 6, total = n * cw + (n - 1) * gap + (linked ? 120 : 0);
        double x0 = r.getCenterX() - total / 2, y = r.getY() + 60;
        for (int i = 0; i < n; i++) {
            double x = x0 + i * (cw + gap);
            Color col = i == hl ? HL : series()[0];
            RoundRectangle2D cell = new RoundRectangle2D.Double(x, y, cw, ch, 12, 12);
            g.setColor(i == hl ? new Color(HL.getRed(), HL.getGreen(), HL.getBlue(), 90) : light ? Color.WHITE : TEAL_BTN); g.fill(cell);
            g.setColor(col); g.setStroke(new BasicStroke(i == hl ? 4.2f : 2.8f)); g.draw(cell);
            centered(g, vals.get(i), x + (linked ? cw * 0.36 : cw / 2), y + ch / 2 + 14, sansBold, 40, TEXT, cw * (linked ? 0.6 : 0.88));
            if (linked) {
                g.setColor(col); g.draw(new Line2D.Double(x + cw * 0.72, y + 8, x + cw * 0.72, y + ch - 8));
                g.fill(new Ellipse2D.Double(x + cw * 0.86 - 6, y + ch / 2 - 6, 12, 12));
                g.setColor(MUTED); g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                double sx = x + cw * 0.86, ex = x + cw + gap + 2;
                g.draw(new Line2D.Double(sx, y + ch / 2, ex - 4, y + ch / 2));
                if (i < n - 1) arrowHead(g, ex - 24, y + ch / 2, ex, y + ch / 2, MUTED);
            } else centered(g, String.valueOf(i), x + cw / 2, y + ch + 40, mono, 28, MUTED, cw);
        }
        if (linked) {
            double ex = x0 + n * (cw + gap) - gap + 8;
            g.setColor(MUTED); g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(ex, y + ch / 2, ex + 40, y + ch / 2)); arrowHead(g, ex + 22, y + ch / 2, ex + 44, y + ch / 2, MUTED);
            text(g, "NULL", (float) ex + 52, (float) (y + ch / 2 + 10), mono, 32, PINK);
            centered(g, "HEAD", x0 + cw * 0.36, y - 18, sansBold, 30, GREEN, cw);
        }
        if (queue) {
            centered(g, "FRONT", x0 + cw / 2, y - 18, sansBold, 30, GREEN, cw + 30);
            centered(g, "REAR", x0 + (n - 1) * (cw + gap) + cw / 2, y - 18, sansBold, 30, PINK, cw + 30);
        }
    }

    // ---- periodic table
    private static final String[] ELEMENT_SYMBOLS = ("H He Li Be B C N O F Ne Na Mg Al Si P S Cl Ar K Ca Sc Ti V Cr Mn Fe Co Ni Cu Zn Ga Ge As Se Br Kr Rb Sr Y Zr Nb Mo Tc "
            + "Ru Rh Pd Ag Cd In Sn Sb Te I Xe Cs Ba La Ce Pr Nd Pm Sm Eu Gd Tb Dy Ho Er Tm Yb Lu Hf Ta W Re Os Ir Pt Au Hg Tl Pb Bi Po At Rn Fr Ra Ac Th Pa U Np Pu Am "
            + "Cm Bk Cf Es Fm Md No Lr Rf Db Sg Bh Hs Mt Ds Rg Cn Nh Fl Mc Lv Ts Og").split(" ");

    private static String elementGroup(int z) {
        if (List.of(3, 11, 19, 37, 55, 87).contains(z)) return "alkali";
        if (List.of(4, 12, 20, 38, 56, 88).contains(z)) return "alkaline";
        if (List.of(9, 17, 35, 53, 85, 117).contains(z)) return "halogen";
        if (List.of(2, 10, 18, 36, 54, 86, 118).contains(z)) return "noble";
        if (List.of(5, 14, 32, 33, 51, 52).contains(z)) return "metalloid";
        if (List.of(1, 6, 7, 8, 15, 16, 34).contains(z)) return "nonmetal";
        if (z >= 57 && z <= 71) return "lanthanide";
        if (z >= 89 && z <= 103) return "actinide";
        if (List.of(13, 31, 49, 50, 81, 82, 83, 84, 113, 114, 115, 116).contains(z)) return "post-transition";
        return "transition";
    }

    /** Period (1-9, 8-9 = the f-block rows) and group (1-18) of element z. */
    private static int[] elementCell(int z) {
        if (z == 1) return new int[]{1, 1};
        if (z == 2) return new int[]{1, 18};
        if (z <= 4) return new int[]{2, z - 2};
        if (z <= 10) return new int[]{2, z + 8};
        if (z <= 12) return new int[]{3, z - 10};
        if (z <= 18) return new int[]{3, z};
        if (z <= 36) return new int[]{4, z - 18};
        if (z <= 54) return new int[]{5, z - 36};
        if (z <= 56) return new int[]{6, z - 54};
        if (z >= 57 && z <= 70) return new int[]{8, z - 54};       // La - Yb sit in the f-block row
        if (z == 71) return new int[]{6, 3};
        if (z <= 86) return new int[]{6, z - 68};
        if (z <= 88) return new int[]{7, z - 86};
        if (z >= 89 && z <= 102) return new int[]{9, z - 86};      // Ac - No
        if (z == 103) return new int[]{7, 3};
        return new int[]{7, z - 100};
    }

    private Color groupColour(String grp) {
        return switch (grp) {
            case "alkali" -> PINK; case "alkaline" -> new Color(0xF0A060); case "transition" -> CYAN; case "post-transition" -> new Color(0x6FA8DC);
            case "metalloid" -> GREEN; case "nonmetal" -> new Color(0x7BD389); case "halogen" -> HL; case "noble" -> VIOLET;
            case "lanthanide" -> new Color(0xE06FB5); default -> new Color(0xB57FE0);
        };
    }

    /** Periodic table. text = what to highlight, comma separated: symbols (Na, Cl) and/or groups (alkali, alkaline, transition, halogen, noble, metalloid,
     *  nonmetal, lanthanide, actinide, post-transition). Empty = the whole table coloured by group. title = caption. */
    private void periodic(Graphics2D g, Element e, Rectangle2D r) {
        java.util.Set<String> want = new java.util.HashSet<>();
        for (String t : nz(e.text()).split(",")) if (!t.isBlank()) want.add(t.trim().toLowerCase(Locale.ROOT).replace("alkali metals", "alkali").replace("noble gases", "noble").replace("halogens", "halogen"));
        double cell = Math.min(r.getWidth() / 18.4, (r.getHeight() - 24) / 9.6);
        double x0 = r.getCenterX() - cell * 9, y0 = r.getY() + 8;
        for (int z = 1; z <= 118; z++) {
            int[] pc = elementCell(z);
            double x = x0 + (pc[1] - 1) * cell, y = y0 + (pc[0] - 1) * cell + (pc[0] >= 8 ? cell * 0.45 : 0);
            String sym = ELEMENT_SYMBOLS[z - 1], grp = elementGroup(z);
            boolean on = want.isEmpty() || want.contains(sym.toLowerCase(Locale.ROOT)) || want.contains(grp);
            Color c = groupColour(grp);
            RoundRectangle2D box = new RoundRectangle2D.Double(x + 1.5, y + 1.5, cell - 3, cell - 3, cell * 0.14, cell * 0.14);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), on ? (want.isEmpty() ? 150 : 230) : 38)); g.fill(box);
            if (on && !want.isEmpty()) { g.setColor(c.brighter()); g.setStroke(new BasicStroke(2.4f)); g.draw(box); }
            Color ink = on ? (want.isEmpty() ? TEXT : (light ? new Color(0x14212B) : Color.WHITE)) : new Color(MUTED.getRed(), MUTED.getGreen(), MUTED.getBlue(), 120);
            centered(g, sym, x + cell / 2, y + cell * 0.62, sansBold, (int) Math.max(11, cell * 0.38), ink, cell - 4);
            if (cell > 40) text(g, String.valueOf(z), (float) (x + 4), (float) (y + cell * 0.26), sans, (int) Math.max(8, cell * 0.2), ink);
        }
        if (!nz(e.title()).isEmpty()) centered(g, nz(e.title()), r.getCenterX(), r.getMaxY() - 2, sansBold, 26, TEXT, r.getWidth());
    }

    /** Reaction energy profile. lines = "Reactants|0", "Transition state|60", "Products|-25" (kJ, relative); style "catalyst" adds the lower dashed route;
     *  title = x-axis caption. Ea and delta H are measured and drawn. */
    private void energy(Graphics2D g, Element e, Rectangle2D r) {
        List<String> pts = listOrEmpty(e);
        if (pts.size() < 2) return;
        double[] val = new double[pts.size()];
        double lo = 1e9, hi = -1e9;
        for (int i = 0; i < pts.size(); i++) { val[i] = parseSigned(part(pts.get(i), 1)); lo = Math.min(lo, val[i]); hi = Math.max(hi, val[i]); }
        if (hi - lo < 1) hi = lo + 1;
        double left = r.getX() + 90, right = r.getMaxX() - 40, top = r.getY() + 40, bottom = r.getMaxY() - 70;
        final double fLo = lo, fHi = hi, fBottom = bottom, fTop = top;
        java.util.function.DoubleUnaryOperator Y = v -> fBottom - (v - fLo) / (fHi - fLo) * (fBottom - fTop);
        g.setColor(MUTED); g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(left, top - 10, left, bottom + 10)); g.draw(new Line2D.Double(left, bottom + 10, right, bottom + 10));
        arrowHead(g, left, top + 8, left, top - 14, MUTED); arrowHead(g, right - 20, bottom + 10, right + 4, bottom + 10, MUTED);
        var at = g.getTransform();
        g.rotate(-Math.PI / 2, left - 52, (top + bottom) / 2);
        centered(g, "Energy", left - 52, (top + bottom) / 2 + 8, sansBold, 26, MUTED, 200);
        g.setTransform(at);
        if (!nz(e.title()).isEmpty()) centered(g, nz(e.title()), (left + right) / 2, r.getMaxY() - 6, sansBold, 26, MUTED, right - left);
        int n = pts.size();
        double[] px = new double[n], py = new double[n];
        for (int i = 0; i < n; i++) { px[i] = left + 50 + (right - left - 100) * i / (n - 1.0); py[i] = Y.applyAsDouble(val[i]); }
        java.util.function.BiFunction<double[], Color, Path2D> curve = (ys, col) -> {
            Path2D path = new Path2D.Double();
            path.moveTo(px[0] - 40, ys[0]);
            path.lineTo(px[0], ys[0]);
            for (int i = 0; i < n - 1; i++) {
                double mx = (px[i] + px[i + 1]) / 2;
                path.curveTo(mx, ys[i], mx, ys[i + 1], px[i + 1], ys[i + 1]);       // flat at every stage, smooth in between
            }
            path.lineTo(px[n - 1] + 40, ys[n - 1]);
            return path;
        };
        boolean cat = nz(e.style()).toLowerCase(Locale.ROOT).contains("catalyst");
        if (cat) {
            double[] low = py.clone();
            for (int i = 1; i < n - 1; i++) low[i] = py[0] + (py[i] - py[0]) * 0.5;
            g.setColor(GREEN); g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{12, 9}, 0));
            g.draw(curve.apply(low, GREEN));
            tagText(g, "with catalyst", px[n / 2], low[n / 2] + 40, 24, GREEN, 0);
        }
        g.setColor(CYAN); g.setStroke(new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(curve.apply(py, CYAN));
        int peak = 0;
        for (int i = 1; i < n; i++) if (val[i] > val[peak]) peak = i;
        for (int i = 0; i < n; i++) {
            g.setColor(HL); g.fill(new Ellipse2D.Double(px[i] - 7, py[i] - 7, 14, 14));
            boolean above = i == peak && peak != 0 && peak != n - 1;
            centered(g, part(pts.get(i), 0), px[i], above ? py[i] - 20 : py[i] + 40, sansBold, 24, TEXT, (right - left) / n);
        }
        if (peak != 0) {   // activation energy: from the reactants' level up to the peak
            double ax = px[peak] - 46;
            g.setColor(PINK); g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(ax, py[0], ax, py[peak] + 6)); arrowHead(g, ax, py[0] - 22, ax, py[peak], PINK);
            tagText(g, "Ea = " + fmtNum(val[peak] - val[0]), ax - 14, (py[0] + py[peak]) / 2 + 10, 28, PINK, 1);
        }
        double dH = val[n - 1] - val[0];
        double dx = px[n - 1] + 34;
        g.setColor(GREEN); g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(dx, py[0], dx, py[n - 1])); arrowHead(g, dx, py[0] + (dH < 0 ? 22 : -22), dx, py[n - 1], GREEN);
        g.setColor(new Color(MUTED.getRed(), MUTED.getGreen(), MUTED.getBlue(), 140)); g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{7, 7}, 0));
        g.draw(new Line2D.Double(px[0], py[0], dx, py[0]));
        tagText(g, "\u0394H = " + (dH > 0 ? "+" : "") + fmtNum(dH) + (dH < 0 ? " (exothermic)" : " (endothermic)"), dx + 8, py[0] - 14, 26, GREEN, 1);
    }

    private static String fmtNum(double v) { return Math.abs(v - Math.rint(v)) < 0.05 ? String.valueOf((long) Math.rint(v)) : String.format(Locale.ROOT, "%.1f", v); }

    /** Thin-lens ray diagram. style = convex | concave; text = "u=30, f=10" (object distance and focal length, same unit). Shows the image and where it forms. */
    private void lens(Graphics2D g, Element e, Rectangle2D r) {
        boolean convex = !nz(e.style()).toLowerCase(Locale.ROOT).contains("concave");
        double u = 30, f = 10;
        for (String part : nz(e.text()).split(",")) {
            String[] kv = part.split("=");
            if (kv.length == 2 && kv[0].trim().equalsIgnoreCase("u")) u = Math.max(1, parseNum(kv[1]));
            if (kv.length == 2 && kv[0].trim().equalsIgnoreCase("f")) f = Math.max(1, parseNum(kv[1]));
        }
        double fs = convex ? f : -f;
        double v = (u * fs) / (u - fs);                            // 1/v = 1/f - 1/(-u) with real distances positive: v = u f / (u - f)
        if (convex && Math.abs(u - f) < 1e-6) v = 1e6;
        double ho = 1.0, hi = -ho * v / u;                         // image height relative to the object (negative = inverted)
        double scale = Math.min((r.getWidth() / 2 - 70) / Math.max(u, 1.0), (r.getWidth() / 2 - 70) / Math.max(Math.min(Math.abs(v), 3 * u), f * 1.4));
        scale = Math.min(scale, (r.getWidth() / 2 - 70) / Math.max(u, f * 1.6));
        double cx = r.getCenterX(), cy = r.getCenterY() + 6, hpx = Math.min(r.getHeight() * 0.22, 92);
        double ox = cx - u * scale, oy = cy - hpx;
        g.setColor(MUTED); g.setStroke(new BasicStroke(2.4f));
        g.draw(new Line2D.Double(r.getX() + 10, cy, r.getMaxX() - 10, cy));
        // the lens
        g.setColor(CYAN); g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double lh = r.getHeight() * 0.42;
        g.draw(new Line2D.Double(cx, cy - lh, cx, cy + lh));
        if (convex) { arrowHead(g, cx, cy - lh + 24, cx, cy - lh - 2, CYAN); arrowHead(g, cx, cy + lh - 24, cx, cy + lh + 2, CYAN); }
        else { arrowHead(g, cx - 22, cy - lh, cx + 2, cy - lh, CYAN); arrowHead(g, cx + 22, cy - lh, cx - 2, cy - lh, CYAN); arrowHead(g, cx - 22, cy + lh, cx + 2, cy + lh, CYAN); arrowHead(g, cx + 22, cy + lh, cx - 2, cy + lh, CYAN); }
        // focal points
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            g.setColor(HL); g.fill(new Ellipse2D.Double(cx + sgn * f * scale - 6, cy - 6, 12, 12));
            tagText(g, sgn < 0 ? "F" : "F\u2032", cx + sgn * f * scale, cy + 44, 26, HL, 0);
        }
        // object
        g.setColor(GREEN); g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(ox, cy, ox, oy)); arrowHead(g, ox, oy + 24, ox, oy - 2, GREEN);
        centered(g, "object", ox, cy + 38, sans, 22, GREEN, 120);
        // image
        double ix = cx + v * scale, iy = cy - hi * hpx;
        boolean real = v > 0;
        Color[] rc = {PINK, VIOLET, new Color(0xF0A060)};
        // Where each ray meets the lens plane. Ray 1 runs parallel to the axis, ray 2 goes straight through the centre, ray 3 is aimed at
        // the near focus (convex) or away from the far one (concave) and leaves the lens parallel to the axis.
        double y3;
        if (!convex) y3 = cy - ho * hpx * f / (f + u);
        else if (u > f) y3 = cy + ho * hpx * f / (u - f);
        else y3 = cy - Math.abs(ho * hpx * f / (u - f));
        double reach = r.getMaxX() - 24;
        double[] hit = {oy, cy, y3};
        for (int k = 0; k < 3; k++) {
            g.setColor(rc[k]); g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(ox, oy, cx, hit[k]));
            double dx = (ix - cx), dy = (iy - hit[k]);
            if (Math.abs(dx) < 1e-6) continue;
            double slope = dy / dx;
            if (real) {
                double ex = Math.min(reach, ix + 60);
                g.draw(new Line2D.Double(cx, hit[k], ex, hit[k] + slope * (ex - cx)));
            } else {                                                            // virtual image: rays leave the lens spreading, dashed lines trace back to the image
                double dir = Math.signum(cx - ix);
                double ex = reach;
                double sl = (hit[k] - iy) / (cx - ix);
                g.draw(new Line2D.Double(cx, hit[k], ex, hit[k] + sl * (ex - cx)));
                g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{8, 8}, 0));
                g.draw(new Line2D.Double(cx, hit[k], ix, iy));
            }
        }
        // image arrow
        if (Math.abs(v) < 1e5) {
            g.setColor(real ? CYAN : new Color(CYAN.getRed(), CYAN.getGreen(), CYAN.getBlue(), 170));
            g.setStroke(real ? new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND) : new BasicStroke(4.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{9, 7}, 0));
            g.draw(new Line2D.Double(ix, cy, ix, iy));
            arrowHead(g, ix, iy + (iy < cy ? 24 : -24), ix, iy, CYAN);
        }
        String desc = (convex ? "convex lens" : "concave lens") + ": u = " + fmtNum(u) + ", f = " + fmtNum(f)
                + (Math.abs(v) < 1e5 ? ", v = " + fmtNum(v) + "  \u2192  " + (real ? "real, inverted" : "virtual, upright") + ", " + (Math.abs(hi) > 1.02 ? "magnified" : Math.abs(hi) < 0.98 ? "diminished" : "same size")
                : "  \u2192  image at infinity");
        centered(g, desc, r.getCenterX(), r.getMaxY() - 6, sansBold, 26, TEXT, r.getWidth() - 20);
    }

    /** Funnel. lines = "Stage|value" (largest first). The funnel is on the left; each stage's name, value and conversion from the previous stage are beside it. */
    private void funnel(Graphics2D g, Element e, Rectangle2D r) {
        List<String> st = listOrEmpty(e);
        int n = st.size();
        if (n < 2) return;
        double[] v = new double[n];
        double max = 1;
        for (int i = 0; i < n; i++) { v[i] = Math.max(0, parseNum(part(st.get(i), 1))); max = Math.max(max, v[i]); }
        double fw = r.getWidth() * 0.5, cx = r.getX() + fw / 2 + 6, labelX = r.getX() + fw + 44, rowH = (r.getHeight() - 20) / n;
        for (int i = 0; i < n; i++) {
            double wt = Math.max(0.2, v[i] / max) * fw, wb = (i + 1 < n ? Math.max(0.2, v[i + 1] / max) : Math.max(0.2, v[i] / max) * 0.8) * fw;
            double y = r.getY() + 10 + i * rowH;
            Path2D t = new Path2D.Double();
            t.moveTo(cx - wt / 2, y); t.lineTo(cx + wt / 2, y); t.lineTo(cx + wb / 2, y + rowH - 6); t.lineTo(cx - wb / 2, y + rowH - 6); t.closePath();
            Color col = series()[i % series().length];
            g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), light ? 80 : 110)); g.fill(t);
            g.setColor(col); g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(t);
            g.setColor(new Color(MUTED.getRed(), MUTED.getGreen(), MUTED.getBlue(), 120)); g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{5, 6}, 0));
            g.draw(new Line2D.Double(cx + (wt + wb) / 4 + 6, y + rowH / 2 - 3, labelX - 12, y + rowH / 2 - 3));
            text(g, fit(part(st.get(i), 0), sansBold, 30, (int) (r.getMaxX() - labelX)), (float) labelX, (float) (y + rowH / 2 - 4), sansBold, 30, col);
            String value = String.format(Locale.ROOT, "%,d", Math.round(v[i]));
            String line2 = value;
            if (i > 0 && v[i - 1] > 0) line2 += "   \u2193 " + Math.round(v[i] / v[i - 1] * 100) + "% of previous";
            text(g, fit(line2, sans, 25, (int) (r.getMaxX() - labelX)), (float) labelX, (float) (y + rowH / 2 + 28), sans, 25, TEXT);
        }
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
        if (!t.isEmpty()) pendingLabels.add(new PendingLabel(t, new CubicCurve2D.Double(p1.getX(), p1.getY(), c1.getX(), c1.getY(),
                c2.getX(), c2.getY(), p2.getX(), p2.getY()), g.getComposite()));
    }

    private record PendingLabel(String text, CubicCurve2D curve, java.awt.Composite composite) { }

    private final List<PendingLabel> pendingLabels = new ArrayList<>();

    private static Point2D at(CubicCurve2D c, double t) {
        double u = 1 - t;
        return new Point2D.Double(u * u * u * c.getX1() + 3 * u * u * t * c.getCtrlX1() + 3 * u * t * t * c.getCtrlX2() + t * t * t * c.getX2(),
                u * u * u * c.getY1() + 3 * u * u * t * c.getCtrlY1() + 3 * u * t * t * c.getCtrlY2() + t * t * t * c.getY2());
    }

    /**
     * Arrow labels are drawn LAST (so boxes never cover them) and each one slides along its own arrow until it overlaps neither
     * an element nor another label - several arrows leaving one box used to stack their labels on top of each other.
     */
    private void flushLabels(Graphics2D g, Map<String, Rectangle2D> bounds) {
        List<Rectangle2D> taken = new ArrayList<>();
        double[] tries = {0.6, 0.45, 0.72, 0.35, 0.82, 0.5, 0.66, 0.28, 0.9};
        for (PendingLabel pl : pendingLabels) {
            float tw = widthOf(pl.text(), sans, 22);
            Rectangle2D best = null;
            double bestCost = Double.MAX_VALUE;
            for (double off : new double[]{0, -30, 30}) {
                for (double t : tries) {
                    Point2D m = at(pl.curve(), t);
                    Rectangle2D r = new Rectangle2D.Double(m.getX() - tw / 2 - 16, m.getY() - 20 + off, tw + 32, 40);
                    double cost = 0;
                    for (Rectangle2D b : bounds.values()) if (b.intersects(r)) cost += area(b.createIntersection(r));
                    for (Rectangle2D b : taken) if (b.intersects(r)) cost += 4 * area(b.createIntersection(r));
                    cost += Math.abs(off) * 2 + Math.abs(t - 0.55) * 10;
                    if (cost < bestCost) { bestCost = cost; best = r; }
                }
                if (bestCost < 40) break;
            }
            if (best == null) continue;
            taken.add(best);
            var old = g.getComposite();
            g.setComposite(pl.composite());
            RoundRectangle2D tag = new RoundRectangle2D.Double(best.getX(), best.getY(), best.getWidth(), best.getHeight(), 10, 10);
            g.setColor(TAG_BG);
            g.fill(tag);
            g.setColor(themeId.equals("reference") ? new Color(0x2FB3A6) : ARROW);
            g.setStroke(new BasicStroke(1.6f));
            g.draw(tag);
            text(g, pl.text(), (float) (best.getX() + 16), (float) (best.getY() + 28), sans, 22, TEXT);
            g.setComposite(old);
        }
        pendingLabels.clear();
    }

    private static double area(Rectangle2D r) { return Math.max(0, r.getWidth()) * Math.max(0, r.getHeight()); }

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
    /** Library name to draw instead of a hand-drawn icon, or null. Kids themes take colourful emoji first; others keep the hand-drawn icons first. */
    private String libraryIcon(String n) {
        boolean kidsLook = themeId.equals("kids") || themeId.equals("toddler");
        if (!kidsLook && ICONS.contains(n)) return null;
        String found = IconLibrary.find(n, kidsLook);
        return found != null && IconLibrary.image(found) != null ? found : null;
    }

    /** True when a name can be drawn (hand-drawn or from the library). */
    boolean hasIcon(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT).trim();
        return ICONS.contains(n) || IconLibrary.find(n, false) != null;
    }

    private void drawLibraryIcon(Graphics2D g, String lib, double cx, double cy, double s, Color col) {
        BufferedImage img = IconLibrary.isColour(lib) ? IconLibrary.image(lib) : IconLibrary.tinted(lib, col);
        if (img == null) return;
        Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        double d = IconLibrary.isColour(lib) ? s * 1.08 : s;          // emoji have built-in padding; line icons have a safe margin
        g.drawImage(img, (int) Math.round(cx - d / 2), (int) Math.round(cy - d / 2), (int) Math.round(d), (int) Math.round(d), null);
        if (old != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
    }

    void icon(Graphics2D g, String name, double cx, double cy, double s, Color col) {
        String n = name == null ? "box" : name.toLowerCase(Locale.ROOT).trim();
        String lib = libraryIcon(n);
        if (lib != null) { drawLibraryIcon(g, lib, cx, cy, s, col); return; }
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

            // ---- health / body / everyday glyphs
            case "ear" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(cx - h * 0.35, cy + h * 0.55);
                p.curveTo(cx - h * 0.75, cy + h * 0.1, cx - h * 0.75, cy - h * 0.85, cx + h * 0.05, cy - h * 0.85);
                p.curveTo(cx + h * 0.75, cy - h * 0.85, cx + h * 0.8, cy - h * 0.05, cx + h * 0.35, cy + h * 0.3);
                p.curveTo(cx + h * 0.1, cy + h * 0.55, cx + h * 0.15, cy + h * 0.9, cx - h * 0.2, cy + h * 0.85);
                g.draw(p);
                g.draw(new Arc2D.Double(cx - h * 0.35, cy - h * 0.45, h * 0.7, h * 0.7, 160, -230, Arc2D.OPEN));
            }
            case "eye" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(cx - h * 0.9, cy); p.quadTo(cx, cy - h * 0.8, cx + h * 0.9, cy); p.quadTo(cx, cy + h * 0.8, cx - h * 0.9, cy);
                g.draw(p);
                g.fill(new Ellipse2D.Double(cx - h * 0.28, cy - h * 0.28, h * 0.56, h * 0.56));
            }
            case "heart" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(cx, cy + h * 0.8);
                p.curveTo(cx - h * 1.1, cy, cx - h * 0.6, cy - h * 0.95, cx, cy - h * 0.35);
                p.curveTo(cx + h * 0.6, cy - h * 0.95, cx + h * 1.1, cy, cx, cy + h * 0.8);
                g.fill(p);
            }
            case "lungs" -> {
                g.draw(new Line2D.Double(cx, cy - h * 0.85, cx, cy - h * 0.1));
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    Path2D p = new Path2D.Double();
                    p.moveTo(cx + sgn * h * 0.12, cy - h * 0.3);
                    p.curveTo(cx + sgn * h * 0.9, cy - h * 0.6, cx + sgn * h * 0.95, cy + h * 0.8, cx + sgn * h * 0.2, cy + h * 0.7);
                    p.closePath();
                    g.draw(p);
                }
            }
            case "tooth" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(cx - h * 0.6, cy - h * 0.5); p.curveTo(cx - h * 0.6, cy - h * 0.95, cx - h * 0.1, cy - h * 0.75, cx, cy - h * 0.7);
                p.curveTo(cx + h * 0.1, cy - h * 0.75, cx + h * 0.6, cy - h * 0.95, cx + h * 0.6, cy - h * 0.5);
                p.curveTo(cx + h * 0.6, cy, cx + h * 0.4, cy + h * 0.9, cx + h * 0.2, cy + h * 0.9);
                p.curveTo(cx + h * 0.05, cy + h * 0.9, cx + h * 0.05, cy + h * 0.2, cx, cy + h * 0.2);
                p.curveTo(cx - h * 0.05, cy + h * 0.2, cx - h * 0.05, cy + h * 0.9, cx - h * 0.2, cy + h * 0.9);
                p.curveTo(cx - h * 0.4, cy + h * 0.9, cx - h * 0.6, cy, cx - h * 0.6, cy - h * 0.5);
                g.draw(p);
            }
            case "bone" -> {
                g.draw(new Line2D.Double(cx - h * 0.55, cy + h * 0.55, cx + h * 0.55, cy - h * 0.55));
                for (double[] q : new double[][]{{-0.62, 0.42}, {-0.42, 0.62}, {0.42, -0.62}, {0.62, -0.42}})
                    g.fill(new Ellipse2D.Double(cx + q[0] * h - h * 0.18, cy + q[1] * h - h * 0.18, h * 0.36, h * 0.36));
            }
            case "pill" -> {
                java.awt.geom.AffineTransform at = g.getTransform();
                g.rotate(-Math.PI / 4, cx, cy);
                g.draw(new RoundRectangle2D.Double(cx - h * 0.85, cy - h * 0.32, h * 1.7, h * 0.64, h * 0.64, h * 0.64));
                g.fill(new RoundRectangle2D.Double(cx - h * 0.85, cy - h * 0.32, h * 0.85, h * 0.64, h * 0.64, h * 0.64));
                g.setTransform(at);
            }
            case "syringe" -> {
                java.awt.geom.AffineTransform at = g.getTransform();
                g.rotate(-Math.PI / 4, cx, cy);
                g.draw(new Rectangle2D.Double(cx - h * 0.55, cy - h * 0.22, h * 1.0, h * 0.44));
                g.draw(new Line2D.Double(cx + h * 0.45, cy, cx + h * 0.95, cy));
                g.draw(new Line2D.Double(cx - h * 0.55, cy, cx - h * 0.85, cy));
                g.draw(new Line2D.Double(cx - h * 0.85, cy - h * 0.25, cx - h * 0.85, cy + h * 0.25));
                g.setTransform(at);
            }
            case "stethoscope" -> {
                g.draw(new Arc2D.Double(cx - h * 0.6, cy - h * 0.9, h * 0.8, h * 1.1, 180, 180, Arc2D.OPEN));
                g.draw(new CubicCurve2D.Double(cx - h * 0.2, cy + h * 0.2, cx - h * 0.2, cy + h * 0.8, cx + h * 0.5, cy + h * 0.8, cx + h * 0.5, cy + h * 0.2));
                g.draw(new Ellipse2D.Double(cx + h * 0.3, cy - h * 0.15, h * 0.4, h * 0.4));
            }
            case "virus", "bacteria" -> {
                g.draw(new Ellipse2D.Double(cx - h * 0.5, cy - h * 0.5, h, h));
                int spikes = n.equals("virus") ? 10 : 6;
                for (int i = 0; i < spikes; i++) {
                    double a = i * 2 * Math.PI / spikes;
                    g.draw(new Line2D.Double(cx + Math.cos(a) * h * 0.5, cy + Math.sin(a) * h * 0.5, cx + Math.cos(a) * h * 0.85, cy + Math.sin(a) * h * 0.85));
                    if (n.equals("virus")) g.fill(new Ellipse2D.Double(cx + Math.cos(a) * h * 0.85 - h * 0.08, cy + Math.sin(a) * h * 0.85 - h * 0.08, h * 0.16, h * 0.16));
                }
            }
            case "cell", "microscope" -> {
                if (n.equals("cell")) {
                    g.draw(new Ellipse2D.Double(cx - h * 0.85, cy - h * 0.7, h * 1.7, h * 1.4));
                    g.fill(new Ellipse2D.Double(cx - h * 0.25, cy - h * 0.25, h * 0.5, h * 0.5));
                } else {
                    g.draw(new Line2D.Double(cx - h * 0.1, cy - h * 0.85, cx + h * 0.3, cy + h * 0.1));
                    g.draw(new Arc2D.Double(cx - h * 0.55, cy - h * 0.2, h * 1.0, h * 1.0, 300, 150, Arc2D.OPEN));
                    g.draw(new Line2D.Double(cx - h * 0.7, cy + h * 0.85, cx + h * 0.7, cy + h * 0.85));
                }
            }
            case "dna" -> {
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    Path2D p = new Path2D.Double();
                    for (int i = 0; i <= 20; i++) {
                        double t = i / 20.0, y = cy - h * 0.9 + t * h * 1.8, x = cx + sgn * Math.sin(t * Math.PI * 2) * h * 0.45;
                        if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                    }
                    g.draw(p);
                }
                for (int i = 1; i < 8; i++) {
                    double t = i / 8.0, y = cy - h * 0.9 + t * h * 1.8, dx = Math.sin(t * Math.PI * 2) * h * 0.45;
                    g.draw(new Line2D.Double(cx - dx, y, cx + dx, y));
                }
            }
            case "drop", "water" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(cx, cy - h * 0.9); p.curveTo(cx + h * 0.2, cy - h * 0.4, cx + h * 0.7, cy, cx + h * 0.6, cy + h * 0.35);
                p.curveTo(cx + h * 0.5, cy + h * 0.95, cx - h * 0.5, cy + h * 0.95, cx - h * 0.6, cy + h * 0.35);
                p.curveTo(cx - h * 0.7, cy, cx - h * 0.2, cy - h * 0.4, cx, cy - h * 0.9);
                if (n.equals("drop")) g.fill(p); else g.draw(p);
            }
            case "thermometer" -> {
                g.draw(new RoundRectangle2D.Double(cx - h * 0.18, cy - h * 0.9, h * 0.36, h * 1.3, h * 0.36, h * 0.36));
                g.fill(new Ellipse2D.Double(cx - h * 0.35, cy + h * 0.3, h * 0.7, h * 0.7));
                g.fill(new Rectangle2D.Double(cx - h * 0.07, cy - h * 0.3, h * 0.14, h * 0.7));
            }
            case "hospital", "plus" -> {
                if (n.equals("hospital")) g.draw(new RoundRectangle2D.Double(cx - h * 0.85, cy - h * 0.85, h * 1.7, h * 1.7, h * 0.3, h * 0.3));
                g.fill(new Rectangle2D.Double(cx - h * 0.16, cy - h * 0.55, h * 0.32, h * 1.1));
                g.fill(new Rectangle2D.Double(cx - h * 0.55, cy - h * 0.16, h * 1.1, h * 0.32));
            }
            case "stomach", "kidney" -> {
                Path2D p = new Path2D.Double();
                if (n.equals("kidney")) {
                    p.moveTo(cx + h * 0.2, cy - h * 0.8); p.curveTo(cx - h * 0.9, cy - h * 0.9, cx - h * 0.9, cy + h * 0.9, cx + h * 0.2, cy + h * 0.8);
                    p.curveTo(cx + h * 0.6, cy + h * 0.7, cx + h * 0.2, cy + h * 0.2, cx + h * 0.25, cy);
                    p.curveTo(cx + h * 0.2, cy - h * 0.2, cx + h * 0.6, cy - h * 0.7, cx + h * 0.2, cy - h * 0.8);
                } else {
                    p.moveTo(cx + h * 0.1, cy - h * 0.9); p.lineTo(cx + h * 0.1, cy - h * 0.5);
                    p.curveTo(cx + h * 0.9, cy - h * 0.6, cx + h * 0.9, cy + h * 0.8, cx, cy + h * 0.7);
                    p.curveTo(cx - h * 0.5, cy + h * 0.65, cx - h * 0.6, cy + h * 0.3, cx - h * 0.8, cy + h * 0.5);
                }
                g.draw(p);
            }
            case "muscle" -> g.draw(new Arc2D.Double(cx - h * 0.85, cy - h * 0.6, h * 1.7, h * 1.2, 0, 180, Arc2D.CHORD));
            case "apple", "food" -> {
                g.fill(new Ellipse2D.Double(cx - h * 0.7, cy - h * 0.45, h * 0.8, h * 1.25));
                g.fill(new Ellipse2D.Double(cx - h * 0.1, cy - h * 0.45, h * 0.8, h * 1.25));
                g.draw(new Line2D.Double(cx, cy - h * 0.45, cx + h * 0.1, cy - h * 0.85));
            }
            case "leaf" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(cx - h * 0.8, cy + h * 0.8); p.curveTo(cx - h * 0.8, cy - h * 0.5, cx + h * 0.3, cy - h * 0.9, cx + h * 0.85, cy - h * 0.85);
                p.curveTo(cx + h * 0.9, cy - h * 0.2, cx + h * 0.5, cy + h * 0.8, cx - h * 0.8, cy + h * 0.8);
                g.draw(p);
                g.draw(new Line2D.Double(cx - h * 0.8, cy + h * 0.8, cx + h * 0.4, cy - h * 0.4));
            }
            case "sun", "moon" -> {
                if (n.equals("moon")) {
                    Path2D p = new Path2D.Double();
                    p.moveTo(cx + h * 0.2, cy - h * 0.85);
                    p.curveTo(cx - h * 0.95, cy - h * 0.7, cx - h * 0.7, cy + h * 1.0, cx + h * 0.6, cy + h * 0.55);
                    p.curveTo(cx - h * 0.2, cy + h * 0.45, cx - h * 0.3, cy - h * 0.45, cx + h * 0.2, cy - h * 0.85);
                    g.fill(p);
                } else {
                    g.fill(new Ellipse2D.Double(cx - h * 0.4, cy - h * 0.4, h * 0.8, h * 0.8));
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        g.draw(new Line2D.Double(cx + Math.cos(a) * h * 0.6, cy + Math.sin(a) * h * 0.6, cx + Math.cos(a) * h * 0.9, cy + Math.sin(a) * h * 0.9));
                    }
                }
            }
            case "wind" -> {
                for (int i = 0; i < 3; i++) {
                    double y = cy - h * 0.5 + i * h * 0.5, len = h * (1.4 - i * 0.3);
                    g.draw(new Line2D.Double(cx - h * 0.85, y, cx - h * 0.85 + len, y));
                    g.draw(new Arc2D.Double(cx - h * 0.85 + len - h * 0.2, y - h * 0.4, h * 0.4, h * 0.4, 270, 270, Arc2D.OPEN));
                }
            }
            case "gauge" -> {
                g.draw(new Arc2D.Double(cx - h * 0.85, cy - h * 0.7, h * 1.7, h * 1.7, 0, 180, Arc2D.OPEN));
                g.draw(new Line2D.Double(cx, cy + h * 0.15, cx + h * 0.45, cy - h * 0.45));
                g.fill(new Ellipse2D.Double(cx - h * 0.1, cy + h * 0.05, h * 0.2, h * 0.2));
            }
            case "person", "baby", "family" -> {
                if (n.equals("family")) {
                    userFigure(g, cx - h * 0.45, cy + h * 0.1, s * 0.7, col);
                    userFigure(g, cx + h * 0.45, cy + h * 0.1, s * 0.7, col);
                    userFigure(g, cx, cy + h * 0.35, s * 0.45, col);
                } else userFigure(g, cx, cy, n.equals("baby") ? s * 0.65 : s, col);
            }
            case "home" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(cx - h * 0.85, cy - h * 0.05); p.lineTo(cx, cy - h * 0.85); p.lineTo(cx + h * 0.85, cy - h * 0.05);
                g.draw(p);
                g.draw(new Rectangle2D.Double(cx - h * 0.6, cy - h * 0.25, h * 1.2, h * 1.05));
                g.fill(new Rectangle2D.Double(cx - h * 0.15, cy + h * 0.3, h * 0.3, h * 0.5));
            }
            case "car" -> {
                g.draw(new RoundRectangle2D.Double(cx - h * 0.9, cy - h * 0.1, h * 1.8, h * 0.6, h * 0.2, h * 0.2));
                g.draw(new Arc2D.Double(cx - h * 0.55, cy - h * 0.6, h * 1.1, h * 1.0, 0, 180, Arc2D.OPEN));
                g.fill(new Ellipse2D.Double(cx - h * 0.65, cy + h * 0.35, h * 0.35, h * 0.35));
                g.fill(new Ellipse2D.Double(cx + h * 0.3, cy + h * 0.35, h * 0.35, h * 0.35));
            }
            case "bed" -> {
                g.draw(new Line2D.Double(cx - h * 0.9, cy - h * 0.6, cx - h * 0.9, cy + h * 0.7));
                g.draw(new Rectangle2D.Double(cx - h * 0.9, cy, h * 1.8, h * 0.35));
                g.fill(new RoundRectangle2D.Double(cx - h * 0.75, cy - h * 0.3, h * 0.5, h * 0.3, h * 0.15, h * 0.15));
            }
            case "mask" -> {
                g.draw(new RoundRectangle2D.Double(cx - h * 0.6, cy - h * 0.4, h * 1.2, h * 0.8, h * 0.4, h * 0.4));
                g.draw(new Line2D.Double(cx - h * 0.6, cy - h * 0.2, cx - h * 0.95, cy - h * 0.45));
                g.draw(new Line2D.Double(cx + h * 0.6, cy - h * 0.2, cx + h * 0.95, cy - h * 0.45));
                for (int i = -1; i <= 1; i++) g.draw(new Line2D.Double(cx - h * 0.35, cy + i * h * 0.18, cx + h * 0.35, cy + i * h * 0.18));
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

    private static String unquote(String s) { return nz(s).replaceAll("^[\"'\u201C\u201D\u2018\u2019]+|[\"'\u201C\u201D\u2018\u2019]+$", "").trim(); }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(Math.max(lo, hi), v)); }
}
