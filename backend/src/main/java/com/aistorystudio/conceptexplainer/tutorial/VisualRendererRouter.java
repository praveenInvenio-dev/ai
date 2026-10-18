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

        // Rive is selected only when a scene explicitly names an asset. The renderer client
        // validates that the referenced file exists before sending it to the renderer.
        boolean explicitRiveAsset = plan.path("riveAsset").isTextual() && !plan.path("riveAsset").asText().isBlank();
        boolean uploadedRiveAsset = job.getRiveAssetPath() != null && !job.getRiveAssetPath().isBlank();
        boolean riveSuitable = contains(text, "character", "avatar", "mascot", "talking head", "user interface", "interface animation", "animated ui", "ui animation", "button animation", "rive animation");
        if (explicitRiveAsset || (uploadedRiveAsset && riveSuitable))
            return new Route("RIVE", explicitRiveAsset ? "Scene explicitly references a reusable Rive animation asset." : "Uploaded Rive asset assigned to a matching character/UI scene.", "RIVE_EXPORT");

        if (contains(text, "3d", "three-dimensional", "spatial", "orbit", "solar system", "molecule", "anatomy", "rotation in space", "coordinate system", "3d scene", "spatial layout", "camera orbit", "depth layers", "three-dimensional model"))
            return new Route("THREE_JS", "Spatial structure or rotation benefits from depth and a camera view.", "THREE_JS_EXPORT");

        // Motion Canvas currently has no working server-side export adapter. Use the tested
        // Three.js renderer for progressive process/code visuals instead of selecting a known
        // failing adapter for every lesson.
        if (contains(text, "race condition", "thread", "concurrency", "synchronized", "mutex", "lock", "queue", "request", "api", "data flow", "workflow", "pipeline", "sequence", "step-by-step", "process", "algorithm", "state transition", "before and after", "code", "function", "database", "server", "program", "threading", "multithreading"))
            return new Route("THREE_JS", "Process, code, or system flow; routed to the verified Three.js renderer while Motion Canvas export is unavailable.", "THREE_JS_EXPORT");

        // Keep ordinary explanatory scenes on the existing synchronized native renderer;
        // do not mark an unavailable Motion Canvas export as the default.
        return new Route("NATIVE_DIAGRAM", "General 2D explanation; use the synchronized native diagram renderer.", "NATIVE_DIAGRAM");
    }
    private static String safe(String s) { return s == null ? "" : s; }
    private static boolean contains(String s, String... needles) { for (String n : needles) if (s.contains(n)) return true; return false; }
}
