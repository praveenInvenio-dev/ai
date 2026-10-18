package com.aistorystudio.conceptexplainer.tutorial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Calls the isolated renderer service. Generated model text is data only; no generated JS is executed by the backend. */
public final class VisualEngineRenderClient {
    private static final ObjectMapper M = new ObjectMapper();
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public VisualEngineRenderClient(String baseUrl) { this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", ""); }
    public boolean enabled() { return !baseUrl.isBlank(); }

    /** What the renderer service can export right now (from its /health). All false when it is not configured or not reachable. */
    public record Caps(boolean threeJs, boolean rive, boolean motionCanvas, String motionReason, String note) {
        public Caps(boolean threeJs, boolean rive, boolean motionCanvas) { this(threeJs, rive, motionCanvas, "", ""); }
    }

    private static volatile Caps cachedCaps;
    private static volatile long capsAt;

    public Caps capabilities() {
        if (!enabled()) return new Caps(false, false, false);
        long now = System.currentTimeMillis();
        Caps c = cachedCaps;
        if (c != null && now - capsAt < (c.threeJs() ? 30_000 : 5_000)) return c;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/health")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode root = r.statusCode() == 200 ? M.readTree(r.body()) : M.createObjectNode();
            JsonNode e = root.path("engines");
            c = new Caps(e.path("THREE_JS").asBoolean(false), e.path("RIVE").asBoolean(false), e.path("MOTION_CANVAS").asBoolean(false),
                    root.path("capabilities").path("MOTION_CANVAS").path("reason").asText(""), r.statusCode() == 200 ? "" : "renderer answered HTTP " + r.statusCode());
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            c = new Caps(false, false, false, "", "the renderer service is not reachable at " + baseUrl);
        }
        cachedCaps = c; capsAt = now;
        return c;
    }

    public List<byte[]> render(String engine, String title, String narration, String planJson, int steps,
                               int width, int height, Path riveAsset) throws IOException, InterruptedException {
        return render(engine, title, narration, planJson, steps, width, height, riveAsset, null);
    }

    public List<byte[]> render(String engine, String title, String narration, String planJson, int steps,
                               int width, int height, Path riveAsset, String sceneKind) throws IOException, InterruptedException {
        return render(engine, title, narration, planJson, steps, width, height, riveAsset, sceneKind, null);
    }

    /** {@code theme} is the lesson's look (reel, reference ...): the 3D / animated scene takes its background so it matches the rest of the video. */
    public List<byte[]> render(String engine, String title, String narration, String planJson, int steps,
                               int width, int height, Path riveAsset, String sceneKind, String theme) throws IOException, InterruptedException {
        if (!enabled()) throw new IOException("VISUAL_RENDERER_BASE_URL is not configured");
        ObjectNode payload = M.createObjectNode();
        payload.put("engine", engine); payload.put("title", safe(title)); payload.put("narration", safe(narration));
        payload.put("planJson", planJson == null ? "{}" : planJson); payload.put("steps", Math.max(2, Math.min(8, steps)));
        payload.put("width", width); payload.put("height", height);
        if (sceneKind != null && !sceneKind.isBlank()) payload.put("sceneKind", sceneKind);
        if (theme != null && !theme.isBlank()) payload.put("theme", theme);
        JsonNode plan; try { plan = M.readTree(planJson == null ? "{}" : planJson); } catch (Exception e) { plan = M.createObjectNode(); }
        List<String> labels = labelsFor(plan, sceneKind);
        payload.set("labels", M.valueToTree(labels));
        if ("RIVE".equalsIgnoreCase(engine)) {
            if (riveAsset == null || !Files.isRegularFile(riveAsset)) throw new IOException("Rive asset is missing or inaccessible; refusing to report a Rive render.");
            if (Files.size(riveAsset) > 25L * 1024 * 1024) throw new IOException("Rive asset exceeds the 25 MB render limit.");
            payload.put("riveAssetBase64", Base64.getEncoder().encodeToString(Files.readAllBytes(riveAsset)));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/render-frames"))
                .timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(payload))).build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("Visual renderer returned HTTP " + response.statusCode() + ": " + response.body());
        JsonNode root = M.readTree(response.body());
        if (!"RENDERED".equals(root.path("rendererStatus").asText())) throw new IOException("Visual renderer did not confirm a completed render.");
        List<byte[]> frames = new ArrayList<>();
        for (JsonNode frame : root.path("frames")) frames.add(Base64.getDecoder().decode(frame.asText()));
        if (frames.size() < 2) throw new IOException("Visual renderer returned fewer than two frames.");
        return frames;
    }
    /**
     * The words the 3D scene should show, taken from the lesson's own plan: the layers device for a layered stack, the steps / cycle / tiles
     * for parallel work, otherwise the first device with a list. Falls back to the old top-level lists. At most 6, in plan order.
     */
    static List<String> labelsFor(JsonNode plan, String sceneKind) {
        List<String> prefer = switch (sceneKind == null ? "" : sceneKind) {
            case "layers" -> List.of("layers", "steps", "tree", "cycle");
            case "lanes", "flow" -> List.of("steps", "cycle", "sequence", "tile", "layers");
            case "orbit", "molecule" -> List.of("cycle", "tree", "tile", "steps", "layers");
            default -> List.of("steps", "layers", "cycle", "tree", "tile");
        };
        List<String> labels = new ArrayList<>();
        JsonNode elements = plan.path("elements");
        if (elements.isArray()) {
            for (String type : prefer) {
                for (JsonNode e : elements) {
                    if (!type.equals(e.path("type").asText("").toLowerCase(java.util.Locale.ROOT))) continue;
                    if (e.path("lines").isArray()) for (JsonNode ln : e.path("lines")) addLabel(labels, ln.asText(""));
                    else addLabel(labels, e.path("text").asText(""));
                }
                if (labels.size() >= 3) return labels;
                labels.clear();
            }
            // any device that carries a short list or a name
            for (JsonNode e : elements) {
                if (e.path("lines").isArray()) for (JsonNode ln : e.path("lines")) addLabel(labels, ln.asText(""));
                if (labels.size() >= 3) return labels;
            }
            labels.clear();
        }
        for (String field : List.of("items", "nodes", "steps", "parts")) {
            JsonNode arr = plan.path(field);
            if (arr.isArray()) for (JsonNode n : arr) addLabel(labels, n.isTextual() ? n.asText() : n.path("label").asText(n.path("title").asText("")));
            if (labels.size() >= 3) break;
        }
        return labels;
    }

    /** "Apps|what you use" and "Swallow: muscles open" keep only the name before the separator; long names are shortened. */
    private static void addLabel(List<String> out, String raw) {
        if (raw == null || out.size() >= 6) return;
        String name = raw.split("[|:\u2014]", 2)[0].replaceAll("[\\p{Cntrl}<>]", " ").replaceAll("\\s+", " ").trim();
        if (name.isBlank()) return;
        if (name.length() > 22) name = name.substring(0, 21).trim() + "\u2026";
        out.add(name);
    }
    private static String safe(String s) { return s == null ? "" : s.replaceAll("[\\p{Cntrl}]", " ").trim(); }
}
