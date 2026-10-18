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

    public List<byte[]> render(String engine, String title, String narration, String planJson, int steps,
                               int width, int height, Path riveAsset) throws IOException, InterruptedException {
        if (!enabled()) throw new IOException("VISUAL_RENDERER_BASE_URL is not configured");
        ObjectNode payload = M.createObjectNode();
        payload.put("engine", engine); payload.put("title", safe(title)); payload.put("narration", safe(narration));
        payload.put("planJson", planJson == null ? "{}" : planJson); payload.put("steps", Math.max(2, Math.min(8, steps)));
        payload.put("width", width); payload.put("height", height);
        JsonNode plan; try { plan = M.readTree(planJson == null ? "{}" : planJson); } catch (Exception e) { plan = M.createObjectNode(); }
        List<String> labels = new ArrayList<>();
        for (String field : List.of("items", "nodes", "steps", "parts")) {
            JsonNode arr = plan.path(field);
            if (arr.isArray()) for (JsonNode n : arr) {
                String label = n.isTextual() ? n.asText() : n.path("label").asText(n.path("title").asText(""));
                if (!label.isBlank() && labels.size() < 6) labels.add(safe(label));
            }
            if (labels.size() >= 3) break;
        }
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
    private static String safe(String s) { return s == null ? "" : s.replaceAll("[\\p{Cntrl}]", " ").trim(); }
}
