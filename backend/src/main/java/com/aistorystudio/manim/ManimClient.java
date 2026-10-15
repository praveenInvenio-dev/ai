package com.aistorystudio.manim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Client for the optional Manim sidecar (docker compose profile "manim"): animated maths and physics scenes.
 * The studio only ever sends a template name and parameters; the sidecar validates them again and renders the scene.
 */
@Component
public class ManimClient {

    /** Templates the sidecar offers (the planner prompt lists the same names). */
    public static final List<String> TEMPLATES = List.of("function_plot", "vector_add", "projectile", "equation_steps", "wave", "unit_circle", "pythagoras");

    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private long checkedAt;
    private boolean up;

    public ManimClient(@Value("${studio.manim.url:http://manim:5008}") String base) {
        this.base = base.replaceAll("/+$", "");
    }

    /** Whether the sidecar answers (cached for 30 s when up, 8 s when down, so planning never waits on it twice). */
    public synchronized boolean available() {
        long now = System.currentTimeMillis();
        if (checkedAt != 0 && now - checkedAt < (up ? 30_000 : 8_000)) return up;
        checkedAt = now;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(25)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            up = r.statusCode() == 200 && r.body().contains("\"ok\"");
        } catch (IOException e) {
            up = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            up = false;
        }
        return up;
    }

    private String body(String template, JsonNode params, double seconds, int w, int h, String theme, boolean preview) throws IOException {
        ObjectNode o = mapper.createObjectNode();
        o.put("template", template);
        o.set("params", params);
        o.put("seconds", seconds);
        o.put("theme", theme == null ? "reference" : theme);
        o.put("width", w);
        o.put("height", h);
        o.put("fps", 30);
        o.put("preview", preview);
        return mapper.writeValueAsString(o);
    }

    /** Null when the request is acceptable, otherwise the sidecar's plain-language reason (nothing is rendered). */
    public String validate(String template, JsonNode params, double seconds) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/validate")).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(template, params, seconds, 1280, 720, "reference", false))).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) return null;
            if (r.statusCode() == 404) return null;   // an older sidecar without /api/validate: the render itself validates, so do not drop the scene
            return reason(r.body(), r.statusCode());
        } catch (IOException e) {
            return "the maths animation service is not reachable";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    /** @param preview true = the finished frame as PNG (about a second), false = the animation as MP4 paced to {@code seconds} */
    public byte[] render(String template, JsonNode params, double seconds, int w, int h, String theme, boolean preview) throws IOException {
        try {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/render"))
                            .timeout(Duration.ofMinutes(preview ? 3 : 12)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body(template, params, seconds, w, h, theme, preview))).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new IOException(reason(new String(r.body(), java.nio.charset.StandardCharsets.UTF_8), r.statusCode()));
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private String reason(String json, int status) {
        try {
            String e = mapper.readTree(json).path("error").asText("");
            if (!e.isBlank()) return e;
        } catch (Exception ignored) { }
        return "the maths animation service answered " + status;
    }
}
