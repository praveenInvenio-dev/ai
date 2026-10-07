package com.aistorystudio.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to a local Ollama instance (see OLLAMA_BASE_URL / OLLAMA_MODEL config).
 * OLLAMA_MODEL is only the DEFAULT model, used when a caller doesn't supply an
 * override. The Studio lets the user pick any model actually installed on
 * the Ollama server via listModels() / the /api/models/ollama endpoint, so
 * nothing is permanently hard-coded to one model.
 */
@Component
public class OllamaLLMProvider implements StoryLLMProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaLLMProvider.class);

    private final WebClient webClient;
    private final String defaultModel;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OllamaLLMProvider(
            org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder,
            @Value("${studio.ollama.baseUrl}") String baseUrl,
            @Value("${studio.ollama.model}") String defaultModel,
            @Value("${studio.ollama.timeoutSeconds:120}") long timeoutSeconds) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.defaultModel = defaultModel;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public String generate(String systemPrompt, String userPrompt, String modelOverride) {
        return callOllama(systemPrompt, userPrompt, false, modelOverride);
    }

    @Override
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public String generateStructured(String systemPrompt, String userPrompt, String modelOverride) {
        return callOllama(systemPrompt, userPrompt, true, modelOverride);
    }

    private String callOllama(String systemPrompt, String userPrompt, boolean jsonFormat, String modelOverride) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", resolveModel(modelOverride));
        body.put("stream", false);
        body.put("messages", new Object[]{
                Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        });
        if (jsonFormat) {
            body.put("format", "json");
        }
        body.put("options", Map.of("temperature", jsonFormat ? 0.5 : 0.8));

        Map<?, ?> response = webClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(timeout);

        if (response == null) {
            throw new IllegalStateException("Ollama returned no response");
        }
        Object message = response.get("message");
        if (message instanceof Map<?, ?> m && m.get("content") != null) {
            String content = m.get("content").toString();
            return jsonFormat ? sanitizeStructuredJson(content) : content;
        }
        throw new IllegalStateException("Unexpected Ollama response shape: " + response);
    }

    /**
     * Ollama's JSON format is a strong constraint, but some local models can
     * still surround the JSON with Markdown fences or a short preamble. Keep
     * structured callers resilient by extracting and validating the first JSON
     * object/array before returning it.
     */
    private String sanitizeStructuredJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Ollama returned an empty structured response");
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(?:json|JSON)?\\s*", "");
            int fence = s.lastIndexOf("```");
            if (fence >= 0) s = s.substring(0, fence);
            s = s.trim();
        }
        try {
            return objectMapper.readTree(s).toString();
        } catch (Exception ignored) {
            int firstObj = s.indexOf('{');
            int firstArr = s.indexOf('[');
            int start;
            if (firstObj < 0) start = firstArr;
            else if (firstArr < 0) start = firstObj;
            else start = Math.min(firstObj, firstArr);
            if (start >= 0) {
                int endObj = s.lastIndexOf('}');
                int endArr = s.lastIndexOf(']');
                int end = Math.max(endObj, endArr);
                if (end > start) {
                    String candidate = s.substring(start, end + 1).trim();
                    try {
                        return objectMapper.readTree(candidate).toString();
                    } catch (Exception ignoredAgain) {
                        // Let @Retryable retry the structured generation.
                    }
                }
            }
            throw new IllegalStateException("Ollama returned invalid JSON for structured generation");
        }
    }

    @Override
    public Flux<String> stream(String systemPrompt, String userPrompt, String modelOverride) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", resolveModel(modelOverride));
        body.put("stream", true);
        body.put("messages", new Object[]{
                Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        });
        return webClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }

    @Override
    public List<String> listModels() {
        try {
            JsonNode response = webClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(10));

            List<String> names = new ArrayList<>();
            if (response != null && response.has("models")) {
                for (JsonNode m : response.get("models")) {
                    if (m.has("name")) {
                        names.add(m.get("name").asText());
                    } else if (m.has("model")) {
                        names.add(m.get("model").asText());
                    }
                }
            }
            return names;
        } catch (Exception e) {
            log.warn("Could not list Ollama models: {}", e.getMessage());
            return List.of();
        }
    }

    private String resolveModel(String modelOverride) {
        return (modelOverride != null && !modelOverride.isBlank()) ? modelOverride.trim() : defaultModel;
    }

    @Override
    public boolean healthCheck() {
        try {
            webClient.get().uri("/api/tags").retrieve().toBodilessEntity().block(Duration.ofSeconds(5));
            return true;
        } catch (Exception e) {
            log.warn("Ollama health check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String providerName() {
        return "ollama:" + defaultModel;
    }

    @Override
    public String defaultModel() {
        return defaultModel;
    }
}
