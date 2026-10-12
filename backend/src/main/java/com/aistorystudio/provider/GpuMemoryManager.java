package com.aistorystudio.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * One 16 GB card, five GPU tenants: Ollama (LLM, on the host), ComfyUI (Qwen / Wan / H3), Chatterbox,
 * IndicF5 and Indic-Speak. Each one keeps its model resident after use, so running them one after the other
 * (plan with Ollama -> narrate with Chatterbox / IndicF5 -> draw with ComfyUI) piles up VRAM until the next
 * load fails with "CUDA out of memory".
 *
 * <p>This coordinator makes the card exclusive: before an engine is used, every OTHER engine is asked to
 * release its VRAM (Ollama: keep_alive=0 for loaded models, ComfyUI: /free, TTS sidecars: /api/unload).
 * Switching only happens when the engine changes, so 40 narration lines in a row cost nothing extra.
 * Every call is best-effort with short timeouts; a service that is not running is skipped for a minute.</p>
 *
 * Config: studio.gpu.mode = exclusive (default) | off.
 */
@Component
public class GpuMemoryManager {

    public enum Engine { OLLAMA, COMFYUI, CHATTERBOX, INDICF5, INDICSPEAK, COSYVOICE }

    private static final Logger log = LoggerFactory.getLogger(GpuMemoryManager.class);
    private static final long ABSENT_RETRY_MS = 60_000;

    private final boolean enabled;
    private final String ollamaUrl, comfyUrl, chatterboxUrl, indicf5Url, indicspeakUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Engine, Long> absentUntil = new EnumMap<>(Engine.class);
    private volatile Engine current;

    public GpuMemoryManager(@Value("${studio.gpu.mode:exclusive}") String mode,
                            @Value("${studio.demoMode:false}") boolean demoMode,
                            @Value("${studio.ollama.baseUrl:http://host.docker.internal:11434}") String ollamaUrl,
                            @Value("${studio.comfyui.baseUrl:http://comfyui:8188}") String comfyUrl,
                            @Value("${studio.tts.chatterbox.baseUrl:http://tts-chatterbox:5004}") String chatterboxUrl,
                            @Value("${studio.gpu.indicf5-url:${INDICF5_BASE_URL:http://tts-indic:5003}}") String indicf5Url,
                            @Value("${studio.gpu.indicspeak-url:${INDICSPEAK_BASE_URL:http://tts-indicspeak:5006}}") String indicspeakUrl) {
        this.enabled = !demoMode && !"off".equalsIgnoreCase(mode == null ? "" : mode.trim());
        this.ollamaUrl = trim(ollamaUrl);
        this.comfyUrl = trim(comfyUrl);
        this.chatterboxUrl = trim(chatterboxUrl);
        this.indicf5Url = trim(indicf5Url);
        this.indicspeakUrl = trim(indicspeakUrl);
        log.info("GPU memory coordination: {}", enabled ? "exclusive (one engine in VRAM at a time)" : "off");
    }

    /** Engine for a TTS voice id, or null when the voice does not use the GPU (Piper, Edge, cloud). */
    public static Engine engineForVoice(String voice) {
        if (voice == null) return null;
        String v = voice.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("indic:")) return Engine.INDICF5;
        if (v.startsWith("speak:")) return Engine.INDICSPEAK;
        return null;
    }

    /** Call right before using {@code engine}: frees the others if the GPU last belonged to someone else. */
    public void prepare(Engine engine) {
        if (!enabled || engine == null || engine == current) return;
        synchronized (this) {
            if (engine == current) return;
            long t0 = System.currentTimeMillis();
            for (Engine other : Engine.values()) if (other != engine) release(other);
            log.info("GPU handed to {} (from {}) in {} ms", engine, current, System.currentTimeMillis() - t0);
            current = engine;
        }
    }

    /** After a CUDA out-of-memory error: release everything except {@code engine}, even if it already "owns" the card. */
    public void recoverFromOom(Engine engine) {
        if (!enabled) return;
        synchronized (this) {
            log.warn("CUDA out of memory reported by {} - releasing every other GPU engine", engine);
            for (Engine other : Engine.values()) if (other != engine) release(other);
            current = engine;
        }
    }

    public static boolean isOom(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (m.contains("out of memory") || m.contains("cuda error") || m.contains("cublas") || m.contains("oom")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ release

    private void release(Engine e) {
        Long until = absentUntil.get(e);
        if (until != null && until > System.currentTimeMillis()) return;
        try {
            switch (e) {
                case OLLAMA -> unloadOllama();
                case COMFYUI -> post(comfyUrl + "/free", "{\"unload_models\":true,\"free_memory\":true}", 20);
                case CHATTERBOX -> post(chatterboxUrl + "/api/unload", "{}", 60);
                case INDICF5 -> post(indicf5Url + "/api/unload", "{}", 60);
                case INDICSPEAK -> post(indicspeakUrl + "/api/unload", "{}", 60);
                case COSYVOICE -> { /* sidecar has no unload endpoint */ }
            }
            absentUntil.remove(e);
        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException | java.nio.channels.UnresolvedAddressException ex) {
            absentUntil.put(e, System.currentTimeMillis() + ABSENT_RETRY_MS);   // service not running: don't ask again for a minute
        } catch (Exception ex) {
            if (ex.getCause() instanceof java.net.ConnectException || ex.getCause() instanceof java.nio.channels.UnresolvedAddressException) {
                absentUntil.put(e, System.currentTimeMillis() + ABSENT_RETRY_MS);
            } else {
                log.debug("Could not release {}: {}", e, ex.toString());
            }
        }
    }

    /** Ollama has no "unload all": ask which models are loaded, then send keep_alive=0 for each. */
    private void unloadOllama() throws Exception {
        HttpResponse<String> ps = http.send(HttpRequest.newBuilder(URI.create(ollamaUrl + "/api/ps")).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (ps.statusCode() != 200) return;
        JsonNode models = mapper.readTree(ps.body()).path("models");
        for (JsonNode m : models) {
            String name = m.path("name").asText(m.path("model").asText(""));
            if (name.isBlank()) continue;
            post(ollamaUrl + "/api/generate", mapper.writeValueAsString(Map.of("model", name, "keep_alive", 0)), 30);
            log.info("Ollama model {} unloaded from VRAM", name);
        }
    }

    private void post(String url, String json, int timeoutSeconds) throws Exception {
        HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() >= 400 && r.statusCode() != 404) log.debug("{} -> HTTP {}", url, r.statusCode());
    }

    private static String trim(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
