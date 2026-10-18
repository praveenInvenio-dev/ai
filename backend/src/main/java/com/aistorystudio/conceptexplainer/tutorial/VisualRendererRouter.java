package com.aistorystudio.conceptexplainer.tutorial;

import com.aistorystudio.conceptexplainer.ConceptExplainerJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;

/** Deterministic, safe per-scene visual-engine planner. Never executes model-generated source code. */
public final class VisualRendererRouter {
    public record Route(String engine, String reason, String productionAdapter) {}
    private static final ObjectMapper M = new ObjectMapper();
    private VisualRendererRouter() {}

    public static Route route(ConceptExplainerJob job, ConceptExplainerJob.Scene scene, boolean manimAvailable) {
        String text = (safe(scene.getTitle()) + " " + safe(scene.getNarration()) + " " + safe(scene.getVisualDescription()) + " " + safe(scene.getCode())).toLowerCase(Locale.ROOT);
        JsonNode plan;
        try { plan = M.readTree(scene.getPlanJson() == null ? "{}" : scene.getPlanJson()); } catch (Exception e) { plan = M.createObjectNode(); }
        if (job.isUseManim() && manimAvailable && plan.path("manim").isObject())
            return new Route("MANIM", "Scene contains a validated maths/physics animation spec.", "MANIM_HTTP");
        if (plan.path("riveAsset").isTextual() && !plan.path("riveAsset").asText().isBlank())
            return new Route("RIVE", "Scene explicitly references a reusable Rive state animation asset.", "RIVE_EXPORT");
        if (contains(text, "3d", "three-dimensional", "spatial", "orbit", "solar system", "molecule", "anatomy", "rotation in space", "coordinate system", "3d scene", "spatial layout", "camera orbit", "depth layers", "three-dimensional model"))
            return new Route("THREE_JS", "Spatial structure or rotation benefits from depth and a camera view.", "THREE_JS_EXPORT");
        if (contains(text, "race condition", "thread", "concurrency", "synchronized", "mutex", "lock", "queue", "request", "api", "data flow", "workflow", "pipeline", "sequence", "step-by-step", "process", "algorithm", "state transition", "before and after", "code", "function", "database", "server"))
            return new Route("MOTION_CANVAS", "Ordered actions, causal changes, code or system flow need progressive 2D animation.", "MOTION_CANVAS_EXPORT");
        return new Route("MOTION_CANVAS", "Default: compact 2D visual explanation synchronized to narration.", "MOTION_CANVAS_EXPORT");
    }
    private static String safe(String s) { return s == null ? "" : s; }
    private static boolean contains(String s, String... needles) { for (String n : needles) if (s.contains(n)) return true; return false; }
}
