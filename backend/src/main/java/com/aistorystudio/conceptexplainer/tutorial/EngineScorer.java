package com.aistorystudio.conceptexplainer.tutorial;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Picks the visual engine that fits ONE scene best, from what the scene is about. Pure Java (no JSON, no Spring) so the
 * decision is deterministic and unit-testable.
 *
 * <p>The engines and what each is genuinely good at:</p>
 * <ul>
 *   <li><b>MANIM</b> - maths and physics animation. Only when the scene carries a validated Manim spec.</li>
 *   <li><b>RIVE</b> - a designed character / UI animation. Only when a .riv asset exists and the scene is about a character or UI.</li>
 *   <li><b>THREE_JS</b> - things with real depth: orbits, molecules, crystals, 3D shapes, layered structures, camera moves.</li>
 *   <li><b>MOTION_CANVAS</b> - tweened 2D flow animation; only offered when the renderer reports it can export it.</li>
 *   <li><b>NATIVE_DIAGRAM</b> - the app's own diagram renderer: flowcharts, sequences, data structures, code, comparisons,
 *       charts, illustrated scenes. It is the best fit for most explanatory content, so it is the default.</li>
 * </ul>
 *
 * <p>Matching is whole-word / whole-phrase (so "rapid" never matches "api" and "block" never matches "lock").</p>
 */
public final class EngineScorer {

    private EngineScorer() { }

    public enum Engine { MANIM, RIVE, THREE_JS, MOTION_CANVAS, NATIVE_DIAGRAM }

    /** What the renderer service can really do right now. */
    public record Capabilities(boolean manim, boolean rive, boolean threeJs, boolean motionCanvas) {
        public static Capabilities nativeOnly() { return new Capabilities(false, false, false, false); }
        public static Capabilities all() { return new Capabilities(true, true, true, true); }
    }

    /**
     * @param planTypes   lower-case element / device types used by the scene's diagram plan (flowchart, sequence, code ...)
     * @param manimSpec   the scene carries a validated Manim spec
     * @param explicitRive the plan itself names a Rive asset
     * @param uploadedRive the job has an uploaded Rive asset
     * @param kidsBand    0 = not a kids lesson, 1-3 = age bands
     */
    public record Signals(String title, String narration, String visual, String code, String domain, String subject, Set<String> planTypes,
                          boolean manimSpec, boolean explicitRive, boolean uploadedRive, int kidsBand, boolean libraryRive) {
        public Signals(String title, String narration, String visual, String code, String domain, String subject, Set<String> planTypes,
                       boolean manimSpec, boolean explicitRive, boolean uploadedRive, int kidsBand) {
            this(title, narration, visual, code, domain, subject, planTypes, manimSpec, explicitRive, uploadedRive, kidsBand, false);
        }
    }

    /** @param scores per-engine score (for transparency in the UI); @param hits the words that decided it; @param threeKind orbit | molecule | layers | axes */
    public record Decision(Engine engine, String reason, Map<Engine, Double> scores, List<String> hits, String threeKind, double margin) {
        public Decision withEngine(Engine e, String why) { return new Decision(e, why, scores, hits, threeKind, margin); }
    }

    // ------------------------------------------------------------------------------------------------- lexicons
    private record Lex(String kind, Pattern pattern, double weight) { }

    private static Pattern words(String... phrases) {
        List<String> parts = new ArrayList<>();
        for (String p : phrases) parts.add(Pattern.quote(p).replace(" ", "\\E[\\s-]+\\Q"));
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + String.join("|", parts) + ")(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
    }

    private static final List<Lex> THREE = List.of(
            new Lex("orbit", words("solar system", "orbit", "orbits", "orbiting", "planet", "planets", "satellite", "satellites", "galaxy", "eclipse", "revolves around", "revolve around"), 2.0),
            new Lex("molecule", words("molecule", "molecules", "atom", "atoms", "atomic structure", "crystal lattice", "crystal", "double helix", "dna helix", "electron shell", "nucleus", "chemical bond", "chemical bonds"), 2.0),
            new Lex("layers", words("layers of the earth", "earth's layers", "atmosphere", "crust and mantle", "cross-section", "cross section", "depth layers", "stack of layers", "strata",
                    "layered", "layers", "stack", "tiers", "sits on top", "on top of", "under the hood", "abstraction"), 2.0),
            new Lex("lanes", words("in parallel", "parallel", "concurrently", "simultaneously", "multithreading", "multiple threads", "worker", "workers", "at the same time", "cores",
                    "multi-core", "split the work", "one at a time", "in order", "one after another", "sequential", "sequentially", "single-threaded", "single threaded", "strict sequence"), 2.0),
            new Lex("axes", words("3d", "three-dimensional", "3d model", "3d scene", "coordinate system", "x y z", "xyz axes", "cube", "sphere", "cylinder", "pyramid", "rotation in space", "rotates in space", "camera orbit", "spatial layout", "in 3d"), 2.0));

    private static final Pattern PROCESS = words("race condition", "thread", "threads", "concurrency", "synchronized", "mutex", "request", "response", "api", "data flow", "workflow",
            "pipeline", "sequence", "step by step", "process", "algorithm", "state transition", "before and after", "function", "database", "server", "program", "multithreading",
            "queue", "loop", "variable", "compile", "http", "cache", "bug", "code");
    private static final Pattern COMPARE = words("versus", "compare", "comparison", "difference between", "pros and cons", "trade-off", "tradeoff", "myth", "mistake");
    /** Words that say something MOVES through ordered steps (a request travels, a task is handed on, step one then step two). */
    private static final Pattern MOTION_FLOW = words("flows through", "moves through", "travels", "passes through", "handoff", "hand off", "hands off", "pipeline", "data flow", "step by step", "timeline", "strict sequence");
    /** Ordering markers: counted every time they occur ("first ... then ... finally" is a sequence worth animating). */
    private static final Pattern ORDER_MARKERS = Pattern.compile("(?<![\\p{L}\\p{N}])(?:first|then|next|after that|finally|step (?:one|two|three|four|five|\\d))(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
    /** Devices that are the point of the scene and only the native renderer draws: no animation replaces them. */
    private static final Set<String> BLOCKS_MOTION = Set.of("code", "terminal", "browser", "datastruct", "formula", "plot", "bars", "periodic", "energy", "lens", "table");
    private static final Pattern RIVE_WORDS = words("character", "mascot", "avatar", "talking head", "user interface", "interface animation", "animated ui", "ui animation",
            "button animation", "rive animation", "loading animation", "toggle");

    /**
     * Plan devices that only the native renderer can draw (a real flowchart, a sequence diagram, a data structure, code, a formula, a plot ...).
     * Common devices such as photo, actor, analogy, compare, note, tile, button, layers and steps appear on almost every scene, so they must
     * NOT push a scene to native: the first version counted them and every scene of a real lesson ended up native.
     */
    private static final Set<String> NATIVE_TYPES = Set.of("flowchart", "sequence", "datastruct", "code", "terminal", "browser", "formula", "plot", "bars", "periodic", "energy", "lens", "funnel", "table");

    private static final double THREE_MIN = 3.0;
    private static final double THREE_OVER_NATIVE = 0.5;

    // --------------------------------------------------------------------------------------------------- decide
    public static Decision decide(Signals s, Capabilities caps) {
        String text = join(s.title(), s.narration(), s.visual());
        String titleText = nz(s.title());
        Map<Engine, Double> scores = new EnumMap<>(Engine.class);
        for (Engine e : Engine.values()) scores.put(e, 0.0);
        List<String> hits = new ArrayList<>();

        // ---- native: the default, with extra weight for the things only it draws well
        double nat = 1.0;
        int pc = count(PROCESS, join(text, s.code()), hits, "process");
        nat += Math.min(3.0, pc * 1.0);              // a tech word is a weak hint: lots of scenes mention "thread" without being about process
        int cc = count(COMPARE, text, hits, "compare");
        nat += Math.min(3.0, cc * 1.5);
        boolean nativeType = s.planTypes() != null && s.planTypes().stream().anyMatch(NATIVE_TYPES::contains);
        if (nativeType) nat += 1.5;
        if (nz(s.code()).length() > 8) nat += 1.5;
        if (s.kidsBand() > 0) nat += 2.0;                                  // illustrated scenes for children
        String dom = (nz(s.domain()) + " " + nz(s.subject())).toLowerCase(Locale.ROOT);
        if (dom.contains("tech") || dom.contains("program") || dom.contains("computer") || dom.contains("finance") || dom.contains("business")) nat += 0.5;
        scores.put(Engine.NATIVE_DIAGRAM, nat);

        // ---- three.js: only content with real depth, scored per kind
        double bestThree = 0; String bestKind = null; List<String> threeHits = new ArrayList<>();
        for (Lex lx : THREE) {
            List<String> found = new ArrayList<>();
            int n = countInto(lx.pattern(), text, found);
            int inTitle = countInto(lx.pattern(), titleText, new ArrayList<>());
            double sc = Math.min(6.0, n * lx.weight() + inTitle * 1.0);
            if (sc > bestThree) { bestThree = sc; bestKind = lx.kind(); threeHits = found; }
        }
        if (dom.contains("astronom") || dom.contains("space") || dom.contains("chemis") || dom.contains("geograph") || dom.contains("geometr")) bestThree += bestThree > 0 ? 1.0 : 0;
        if (s.kidsBand() > 0) bestThree *= 0.6;
        if (nativeType) bestThree -= 1.0;                                  // the plan chose a specialised 2D device on purpose
        scores.put(Engine.THREE_JS, Math.max(0, bestThree));

        // ---- motion canvas: ordered steps that move; never over a code window, data structure or formula
        int markers = (int) ORDER_MARKERS.matcher(text).results().count();
        double mc = Math.min(4.0, markers * 1.0) + Math.min(4.0, count(MOTION_FLOW, text, new ArrayList<>(), "motion") * 2.0);
        boolean blocksMotion = s.planTypes() != null && s.planTypes().stream().anyMatch(BLOCKS_MOTION::contains);
        if (blocksMotion || nz(s.code()).length() > 8) mc = 0;
        if (s.kidsBand() > 0) mc *= 0.6;
        scores.put(Engine.MOTION_CANVAS, mc);

        // ---- rive
        List<String> riveHits = new ArrayList<>();
        int rc = count(RIVE_WORDS, text, riveHits, "rive");
        double riveScore = s.explicitRive() ? 10 : s.libraryRive() ? 8 : (s.uploadedRive() && rc > 0 ? 6 + rc : 0);
        scores.put(Engine.RIVE, riveScore);

        // ---- manim
        scores.put(Engine.MANIM, s.manimSpec() ? 10.0 : 0.0);

        // ---- decision, strongest requirement first
        if (s.manimSpec() && caps.manim())
            return new Decision(Engine.MANIM, "Scene has a validated maths/physics animation spec, so Manim draws it.", scores, List.of("manim spec"), null, 10 - nat);
        if (riveScore >= 6 && caps.rive())
            return new Decision(Engine.RIVE, s.explicitRive() ? "The scene names a Rive asset, so the designed animation plays." : s.libraryRive() ? "A Rive animation in the library matches this topic, so the designed animation plays." : "A Rive file was uploaded and this scene is about a character or UI (" + String.join(", ", riveHits) + ").",
                    scores, riveHits, null, riveScore - nat);
        double three = scores.get(Engine.THREE_JS);
        if (caps.threeJs() && three >= THREE_MIN && three >= nat + THREE_OVER_NATIVE)
            return new Decision(Engine.THREE_JS, "Real depth matters here (" + String.join(", ", threeHits) + "): a 3D camera view explains it better than a flat diagram.", scores, threeHits, bestKind, three - nat);
        double motion = scores.get(Engine.MOTION_CANVAS);
        if (caps.motionCanvas() && motion >= 3.0 && motion >= nat - 1.0)
            return new Decision(Engine.MOTION_CANVAS, "Ordered steps that move one after another (" + markers + " ordering cues): an animated step flow shows the order better than a still diagram.", scores, List.of(), "flow", motion - nat);
        String why = nativeType ? "The scene's specialised diagram device (flowchart, sequence, code, chart ...) is what the native renderer draws best."
                : pc + cc > 0 ? "Process / code / comparison content (" + String.join(", ", hits) + "): the native diagram renderer shows steps, flows and code most clearly."
                : "General explanation: the native synchronized diagram renderer is the best fit.";
        return new Decision(Engine.NATIVE_DIAGRAM, why, scores, hits, null, nat - Math.max(three, 0));
    }

    // ------------------------------------------------------------------------------------------------ helpers
    private static int count(Pattern p, String text, List<String> hitsOut, String tag) {
        return countInto(p, text, hitsOut);
    }

    private static int countInto(Pattern p, String text, List<String> hitsOut) {
        var m = p.matcher(text == null ? "" : text);
        Set<String> seen = new java.util.LinkedHashSet<>();
        while (m.find()) seen.add(m.group().toLowerCase(Locale.ROOT));
        hitsOut.addAll(seen);
        return seen.size();
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (p != null && !p.isBlank()) sb.append(p).append(' ');
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
