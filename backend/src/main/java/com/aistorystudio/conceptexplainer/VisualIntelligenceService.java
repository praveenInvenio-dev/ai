package com.aistorystudio.conceptexplainer;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.aistorystudio.provider.StoryLLMProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.util.regex.Pattern;
import java.util.Set;

/** Explainable, deterministic first-pass planner for Concept Studio. It proposes render strategies;
 * actual engine use is still gated by the registered renderer/capability checks in the render pipeline. */
@Service
public class VisualIntelligenceService {
    private final String rendererBaseUrl;
    private final StoryLLMProvider llm;
    private final ObjectMapper mapper = new ObjectMapper();
    public VisualIntelligenceService() { this.rendererBaseUrl = ""; this.llm = null; }
    @Autowired
    public VisualIntelligenceService(@Value("${studio.visual-renderer.base-url:}") String rendererBaseUrl, StoryLLMProvider llm) {
        this.rendererBaseUrl = rendererBaseUrl == null ? "" : rendererBaseUrl;
        this.llm = llm;
    }

    public record PlanRequest(String topic, String audience, String subject, String duration, String complexity) {}
    public record EngineOption(String id, String label, int score, boolean available, String reason) {}
    public record SceneIdea(String visualIntent, String suggestedEngine, List<String> alternatives, String timingGuidance, String reason) {}
    public record ConceptNode(String id, String label, String explanation) {}
    public record ConceptEdge(String from, String to, String relationship) {}
    public record ConceptGraph(List<ConceptNode> nodes, List<ConceptEdge> relationships) {}
    public record Plan(String topic, String learningGoal, String visualStrategy, List<EngineOption> engines, List<SceneIdea> sceneIdeas, String policyNote, ConceptGraph conceptGraph) {}

    public Plan plan(PlanRequest request) {
        String topic = clean(request == null ? null : request.topic());
        if (topic.isBlank()) throw new IllegalArgumentException("Enter a topic to preview the visual plan.");
        String text = (topic + " " + clean(request == null ? null : request.subject())).toLowerCase(Locale.ROOT);
        boolean math = has(text, "equation", "formula", "derivative", "integral", "proof", "vector", "projectile", "kinematics", "algebra", "calculus", "math", "physics");
        boolean spatial = has(text, "molecule", "atom", "planet", "orbit", "solar system", "anatomy", "organ", "3d", "crystal", "geometry in space");
        boolean dynamic2d = has(text, "particle", "charge", "current", "wave", "network packet", "data flow", "queue", "signal", "propagation", "motion");
        boolean process = has(text, "workflow", "process", "pipeline", "algorithm", "request", "response", "multithreading", "steps", "sequence", "architecture");
        var rendererCaps = new com.aistorystudio.conceptexplainer.tutorial.VisualEngineRenderClient(rendererBaseUrl).capabilities();
        List<EngineOption> engines = new ArrayList<>();
        engines.add(option("MANIM", "Manim", math ? 94 : 30, true, math ? "Strong fit for precise equations, graphs and mathematical transformations." : "Use when the scene needs mathematical typesetting or a precise derivation."));
        engines.add(option("THREE_JS", "Three.js", spatial ? 93 : 28, rendererCaps.threeJs(), spatial ? "Spatial structure or depth is central to this topic." : "Use when rotation, depth or a real 3D structure improves understanding."));
        engines.add(option("PIXIJS", "PixiJS", dynamic2d ? 92 : 42, rendererCaps.pixiJs(), dynamic2d ? (rendererCaps.pixiJs() ? "Renderer health confirms PixiJS is available for 2D particles and continuously moving objects." : "Strong fit for 2D particles and continuous motion, but renderer health has not confirmed availability; use an eligible fallback.") : "Use for particle effects and high-volume 2D motion when renderer health confirms availability."));
        engines.add(option("MOTION_CANVAS", "Motion Canvas", process || dynamic2d ? 88 : 72, rendererCaps.motionCanvas(), process ? "Good for narration-timed steps, transitions and coordinated visual sequences." : "Good for timed 2D animation and visual choreography."));
        engines.add(option("NATIVE_DIAGRAM", "Native diagrams", 86, true, "Reliable fallback for labels, comparisons, flowcharts, code, tables and structured diagrams."));
        engines.sort(Comparator.comparingInt(EngineOption::score).reversed());
        List<SceneIdea> scenes = new ArrayList<>();
        String sequenceEngine = rendererCaps.motionCanvas() ? "MOTION_CANVAS" : "NATIVE_DIAGRAM";
        List<String> sequenceAlternatives = rendererCaps.motionCanvas() ? List.of("NATIVE_DIAGRAM") : List.of();
        scenes.add(new SceneIdea("hook_and_question", sequenceEngine, sequenceAlternatives, "First 10–15% of narration", "Introduce the question and establish the visual context before adding detail."));
        if (math) scenes.add(new SceneIdea("equation_or_graph", "MANIM", rendererCaps.motionCanvas() ? List.of("MOTION_CANVAS", "NATIVE_DIAGRAM") : List.of("NATIVE_DIAGRAM"), "When the formula or relationship is spoken", "Reveal one mathematical relationship at a time; preserve notation and units."));
        if (spatial) scenes.add(new SceneIdea("spatial_structure", rendererCaps.threeJs() ? "THREE_JS" : "NATIVE_DIAGRAM", rendererCaps.motionCanvas() ? List.of("MOTION_CANVAS", "NATIVE_DIAGRAM") : List.of("NATIVE_DIAGRAM"), "During the mechanism or structure explanation", "Rotate or isolate parts only when it clarifies spatial relationships."));
        if (dynamic2d) scenes.add(new SceneIdea("particle_or_flow_motion", rendererCaps.pixiJs() ? "PIXIJS" : sequenceEngine, rendererCaps.pixiJs() ? (rendererCaps.motionCanvas() ? List.of("MOTION_CANVAS", "NATIVE_DIAGRAM") : List.of("NATIVE_DIAGRAM")) : sequenceAlternatives, "Synchronise movement to the narrated mechanism", "Use moving particles or markers to make direction, rate or transfer visible."));
        if (process) scenes.add(new SceneIdea("ordered_process", sequenceEngine, sequenceAlternatives, "One event per spoken step", "Reveal each stage in order and keep the currently discussed step highlighted."));
        scenes.add(new SceneIdea("recap_and_check", "NATIVE_DIAGRAM", sequenceAlternatives, "Final 10–15% of narration", "Return to the core idea and show a concise takeaway or knowledge check."));
        String goal = "Explain " + topic + " with a clear mental model, a worked or real-world example, and a concise recap.";
        String strategy = "Use the simplest renderer that accurately expresses each visual intent; combine engines across scenes only when it improves learning.";
        String sourceNote = "Deterministic routing fallback is active.";
        List<ConceptNode> graphNodes = new ArrayList<>();
        List<ConceptEdge> graphEdges = new ArrayList<>();
        if (llm != null) {
            try {
                JsonNode ai = mapper.readTree(llm.generateStructured(
                        "You are the visual lesson planner for an educational video application. Return only JSON. Never write or request executable code. Select from MANIM, THREE_JS, PIXIJS, MOTION_CANVAS, NATIVE_DIAGRAM. Each scene must be a concise pedagogical beat, not merely a topic label. Respect the engine capabilities supplied in the user message. Do not choose unavailable engines. Use 4-7 scenes, including hook, core mechanism, example/application and recap. Keep visual intents from this allowlist: hook_and_question, equation_or_graph, spatial_structure, particle_or_flow_motion, ordered_process, comparison, worked_example, recap_and_check, biological_process, code_explanation. JSON shape: {\"learningGoal\":string,\"visualStrategy\":string,\"conceptGraph\":{\"nodes\":[{\"id\":string,\"label\":string,\"explanation\":string}],\"relationships\":[{\"from\":string,\"to\":string,\"relationship\":string}]},\"scenes\":[{\"visualIntent\":string,\"suggestedEngine\":string,\"alternatives\":[string],\"timingGuidance\":string,\"reason\":string}]}.",
                        "Topic: " + topic + "\nSubject: " + clean(request.subject()) + "\nAudience: " + clean(request.audience()) + "\nDuration: " + clean(request.duration()) + "\nComplexity: " + clean(request.complexity()) + "\nRenderer capabilities: THREE_JS=" + rendererCaps.threeJs() + ", PIXIJS=" + rendererCaps.pixiJs() + ", MOTION_CANVAS=" + rendererCaps.motionCanvas() + ". NATIVE_DIAGRAM=true. Manim can be used only when a validated Manim spec is produced later. Plan the teaching progression and narration-aligned timing guidance."));
                String aiGoal = bounded(ai.path("learningGoal").asText(""), 240);
                String aiStrategy = bounded(ai.path("visualStrategy").asText(""), 400);
                List<SceneIdea> aiScenes = new ArrayList<>();
                JsonNode list = ai.path("scenes");
                if (list.isArray()) for (JsonNode item : list) {
                    String intent = bounded(item.path("visualIntent").asText(""), 64);
                    String engine = engineId(item.path("suggestedEngine").asText(""));
                    if (intent.isBlank() || engine.isBlank() || !engineAvailable(engine, rendererCaps)) continue;
                    List<String> alternatives = new ArrayList<>();
                    JsonNode alt = item.path("alternatives");
                    if (alt.isArray()) for (JsonNode a : alt) { String id = engineId(a.asText("")); if (!id.isBlank() && !id.equals(engine) && engineAvailable(id, rendererCaps) && alternatives.size() < 3) alternatives.add(id); }
                    aiScenes.add(new SceneIdea(intent, engine, alternatives, bounded(item.path("timingGuidance").asText("Align animation events to narration sentence boundaries."), 160), bounded(item.path("reason").asText("Selected to support the learning objective."), 240)));
                    if (aiScenes.size() >= 7) break;
                }
                JsonNode graph = ai.path("conceptGraph");
                JsonNode nodes = graph.path("nodes");
                if (nodes.isArray()) for (JsonNode n : nodes) {
                    String id = bounded(n.path("id").asText(""), 40).replaceAll("[^A-Za-z0-9_-]", "");
                    String label = bounded(n.path("label").asText(""), 80);
                    if (!id.isBlank() && !label.isBlank() && graphNodes.size() < 16 && graphNodes.stream().noneMatch(x -> x.id().equals(id)))
                        graphNodes.add(new ConceptNode(id, label, bounded(n.path("explanation").asText(""), 220)));
                }
                JsonNode edges = graph.path("relationships");
                if (edges.isArray()) for (JsonNode e : edges) {
                    String from = bounded(e.path("from").asText(""), 40).replaceAll("[^A-Za-z0-9_-]", "");
                    String to = bounded(e.path("to").asText(""), 40).replaceAll("[^A-Za-z0-9_-]", "");
                    if (!from.isBlank() && !to.isBlank() && !from.equals(to) && graphNodes.stream().anyMatch(x -> x.id().equals(from)) && graphNodes.stream().anyMatch(x -> x.id().equals(to)) && graphEdges.size() < 24)
                        graphEdges.add(new ConceptEdge(from, to, bounded(e.path("relationship").asText("relates to"), 80)));
                }
                if (aiScenes.size() >= 3) {
                    scenes = aiScenes;
                    if (!aiGoal.isBlank()) goal = aiGoal;
                    if (!aiStrategy.isBlank()) strategy = aiStrategy;
                    sourceNote = "Ollama structured concept graph and scene plan validated; deterministic capability gates remain authoritative.";
                }
            } catch (Exception ignored) {
                sourceNote = "Ollama planning was unavailable or returned an invalid plan; deterministic routing fallback is active.";
            }
        }
        if (graphNodes.isEmpty()) {
            graphNodes.add(new ConceptNode("core", topic, "Central topic being explained."));
            graphNodes.add(new ConceptNode("mechanism", "How it works", "The mechanism or relationship that explains the topic."));
            graphNodes.add(new ConceptNode("application", "Example or application", "A worked example or real-world use."));
            graphEdges.add(new ConceptEdge("core", "mechanism", "explained by"));
            graphEdges.add(new ConceptEdge("mechanism", "application", "applied in"));
        }
        return new Plan(topic, goal, strategy, engines, scenes, "Concept Studio uses programmatic visuals only. " + sourceNote + " Engine availability is read from the renderer health endpoint; unavailable renderers are rejected. PixiJS supports frame rendering and narration-timed MP4 clips when ffmpeg is available. Wan/ComfyUI image generation is excluded from concept lessons.", new ConceptGraph(graphNodes, graphEdges));
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        String s = value.replaceAll("[\\p{Cntrl}<>]", " ").replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max).trim() : s;
    }
    private static String engineId(String raw) {
        String s = raw == null ? "" : raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z_]", "");
        return Set.of("MANIM", "THREE_JS", "PIXIJS", "MOTION_CANVAS", "NATIVE_DIAGRAM").contains(s) ? s : "";
    }
    private static boolean engineAvailable(String id, com.aistorystudio.conceptexplainer.tutorial.VisualEngineRenderClient.Caps caps) {
        return switch (id) {
            case "THREE_JS" -> caps.threeJs();
            case "PIXIJS" -> caps.pixiJs();
            case "MOTION_CANVAS" -> caps.motionCanvas();
            case "MANIM", "NATIVE_DIAGRAM" -> true; // Manim is rechecked by the production service against its own health/spec gate.
            default -> false;
        };
    }
    private static EngineOption option(String id, String label, int score, boolean available, String reason) { return new EngineOption(id, label, score, available, reason); }
    private static boolean has(String text, String... words) { for (String word : words) if (Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE).matcher(text).find()) return true; return false; }
    private static String clean(String s) { return s == null ? "" : s.trim(); }
}
