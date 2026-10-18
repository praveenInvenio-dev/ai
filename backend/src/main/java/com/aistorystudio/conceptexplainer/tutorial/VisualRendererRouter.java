package com.aistorystudio.conceptexplainer.tutorial;

import com.aistorystudio.conceptexplainer.ConceptExplainerJob;
import com.aistorystudio.conceptexplainer.tutorial.EngineScorer.Capabilities;
import com.aistorystudio.conceptexplainer.tutorial.EngineScorer.Decision;
import com.aistorystudio.conceptexplainer.tutorial.EngineScorer.Engine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic, safe per-scene visual-engine planner. Never executes model-generated source code.
 *
 * <p>Each scene is scored by {@link EngineScorer} from its own topic, narration, code and diagram devices, against what the
 * renderer service can really do right now ({@link Capabilities}). A second, lesson-wide pass ({@link #planJob}) keeps the
 * lesson varied and honest: 3D is for the few scenes that truly need depth, never most of the lesson, and the same
 * special engine is not repeated three scenes in a row.</p>
 */
public final class VisualRendererRouter {
    public record Route(String engine, String reason, String productionAdapter, String sceneKind) {
        public Route(String engine, String reason, String productionAdapter) { this(engine, reason, productionAdapter, null); }
    }

    private static final ObjectMapper M = new ObjectMapper();
    /** The only shape of Rive asset reference the pipeline accepts: a file stored by the upload endpoint. */
    static final java.util.regex.Pattern RIVE_PATH = java.util.regex.Pattern.compile("rive-assets/[A-Za-z0-9-]+\\.riv");
    /** At most this share of a lesson's scenes (rounded, minimum one) may be 3D: depth is for the scenes that need it. */
    static final double MAX_THREE_SHARE = 0.35;
    /** Same idea for animated step flows: a lesson of nothing but moving boxes is as dull as one of nothing but still cards. */
    static final double MAX_MOTION_SHARE = 0.4;

    private VisualRendererRouter() {}

    /** Single-scene decision with the legacy capability set (Three.js and Rive assumed up, Motion Canvas down). */
    public static Route route(ConceptExplainerJob job, ConceptExplainerJob.Scene scene, boolean manimAvailable) {
        return route(job, scene, new Capabilities(manimAvailable, true, true, false));
    }

    public static Route route(ConceptExplainerJob job, ConceptExplainerJob.Scene scene, Capabilities caps) {
        return toRoute(decide(job, scene, caps));
    }

    /**
     * Routes every scene of a lesson, then applies the variety rules. Index i of the result belongs to scene i.
     * Deterministic: the same lesson and capabilities always give the same plan.
     */
    public static List<Route> planJob(ConceptExplainerJob job, Capabilities caps) {
        List<ConceptExplainerJob.Scene> scenes = job.getScenes();
        List<Decision> decisions = new ArrayList<>();
        for (ConceptExplainerJob.Scene s : scenes) decisions.add(decide(job, s, caps));

        // 1) depth is for a minority of scenes: keep the strongest 3D scenes, hand the rest to the native renderer
        int maxThree = Math.max(1, (int) Math.round(scenes.size() * MAX_THREE_SHARE));
        List<Integer> threeIdx = new ArrayList<>();
        for (int i = 0; i < decisions.size(); i++) if (decisions.get(i).engine() == Engine.THREE_JS) threeIdx.add(i);
        threeIdx.sort((a, b) -> Double.compare(decisions.get(b).margin(), decisions.get(a).margin()));
        for (int k = maxThree; k < threeIdx.size(); k++) {
            int i = threeIdx.get(k);
            decisions.set(i, decisions.get(i).withEngine(Engine.NATIVE_DIAGRAM, "Kept on the native renderer: this lesson already has enough 3D scenes, and a flat diagram explains this one well too."));
        }
        int maxMotion = Math.max(1, (int) Math.round(scenes.size() * MAX_MOTION_SHARE));
        List<Integer> motionIdx = new ArrayList<>();
        for (int i = 0; i < decisions.size(); i++) if (decisions.get(i).engine() == Engine.MOTION_CANVAS) motionIdx.add(i);
        motionIdx.sort((a, b) -> Double.compare(decisions.get(b).margin(), decisions.get(a).margin()));
        for (int k = maxMotion; k < motionIdx.size(); k++) {
            int i = motionIdx.get(k);
            decisions.set(i, decisions.get(i).withEngine(Engine.NATIVE_DIAGRAM, "Kept on the native renderer: this lesson already has enough animated step flows."));
        }
        // 2) never the same special engine three scenes running (RIVE with an explicit asset and MANIM specs are deliberate, so exempt)
        for (int i = 2; i < decisions.size(); i++) {
            Engine e = decisions.get(i).engine();
            if (e == Engine.NATIVE_DIAGRAM || e == Engine.MANIM || e == Engine.RIVE) continue;
            if (decisions.get(i - 1).engine() == e && decisions.get(i - 2).engine() == e)
                decisions.set(i, decisions.get(i).withEngine(Engine.NATIVE_DIAGRAM, "Kept on the native renderer to vary the look: the previous two scenes already used " + e.name() + "."));
        }
        List<Route> out = new ArrayList<>();
        for (Decision d : decisions) out.add(toRoute(d));
        return out;
    }

    // ------------------------------------------------------------------------------------------------------- internals
    static Decision decide(ConceptExplainerJob job, ConceptExplainerJob.Scene scene, Capabilities caps) {
        JsonNode plan;
        try { plan = M.readTree(scene.getPlanJson() == null ? "{}" : scene.getPlanJson()); } catch (Exception e) { plan = M.createObjectNode(); }
        Set<String> types = new HashSet<>();
        JsonNode elements = plan.path("elements");
        if (elements.isArray()) for (JsonNode e : elements) {
            String t = e.path("type").asText("").trim().toLowerCase(java.util.Locale.ROOT);
            if (!t.isEmpty()) types.add(t);
        }
        boolean manimSpec = job.isUseManim() && plan.path("manim").isObject();
        boolean explicitRive = plan.path("riveAsset").isTextual() && RIVE_PATH.matcher(plan.path("riveAsset").asText().replace('\\', '/')).matches();
        boolean uploadedRive = job.getRiveAssetPath() != null && !job.getRiveAssetPath().isBlank();
        int kidsBand = switch (job.getTrack() == null ? "" : job.getTrack()) { case "KIDS_2_5" -> 1; case "KIDS" -> 2; case "KIDS_10_15" -> 3; default -> 0; };
        var signals = new EngineScorer.Signals(scene.getTitle(), scene.getNarration(), scene.getVisualDescription(), scene.getCode(),
                job.getDomain(), job.getSubject(), types, manimSpec, explicitRive, uploadedRive, kidsBand, scene.getRiveAssetHint() != null);
        return EngineScorer.decide(signals, caps);
    }

    private static Route toRoute(Decision d) {
        String adapter = switch (d.engine()) {
            case MANIM -> "MANIM_HTTP";
            case RIVE -> "RIVE_EXPORT";
            case THREE_JS -> "THREE_JS_EXPORT";
            case MOTION_CANVAS -> "MOTION_CANVAS_EXPORT";
            case NATIVE_DIAGRAM -> "NATIVE_DIAGRAM";
        };
        return new Route(d.engine().name(), d.reason(), adapter, d.threeKind());
    }
}
