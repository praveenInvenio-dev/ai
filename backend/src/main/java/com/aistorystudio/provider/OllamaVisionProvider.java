package com.aistorystudio.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional local semantic QA using an Ollama vision-capable model. */
@Component
@ConditionalOnProperty(name = "studio.vision.provider", havingValue = "ollama")
public class OllamaVisionProvider implements VisionProvider {
    private final WebClient client;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaVisionProvider(WebClient.Builder builder,
                                @Value("${studio.ollama.baseUrl}") String baseUrl,
                                @Value("${studio.vision.model:llava:latest}") String model) {
        this.client = builder.baseUrl(baseUrl).build();
        this.model = model;
    }

    @Override
    public String describeCharacterImage(byte[] imageBytes, String mimeType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", new Object[]{Map.of(
                "role", "user",
                "content", "Describe this character reference for a consistent story image pipeline. "
                        + "Return species, age, colors, face, clothing, proportions and distinctive features.",
                "images", new String[]{Base64.getEncoder().encodeToString(imageBytes)}
        )});
        JsonNode response = client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(JsonNode.class).block();
        return response == null ? "" : response.path("message").path("content").asText("");
    }

    @Override
    public ImageValidationResult validateGeneratedImage(byte[] imageBytes, String expectedPrompt,
                                                          String negativePrompt, String visualStyle,
                                                          String storyContext) {
        String instruction = "You are a strict visual QA checker. Compare the supplied image to the expected scene. "
                + "Return ONLY JSON: {\"passed\":true|false,\"confidence\":0..1,\"feedback\":\"short reason\"}. "
                + "Check: selected visual style, named characters, species, age, clothing/colors, location, "
                + "major objects, and the actual action. Reject unrelated people/animals or a different art style.\n"
                + "STYLE: " + visualStyle + "\nSCENE: " + expectedPrompt + "\nCONTEXT: " + storyContext;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", new Object[]{Map.of(
                "role", "user", "content", instruction,
                "images", new String[]{Base64.getEncoder().encodeToString(imageBytes)}
        )});
        JsonNode response = client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(JsonNode.class).block();
        String raw = response == null ? "" : response.path("message").path("content").asText("");
        try {
            String cleaned = raw.trim().replaceFirst("^```json\\s*", "").replaceFirst("^```\\s*", "").replaceFirst("\\s*```$", "");
            JsonNode n = mapper.readTree(cleaned);
            return new ImageValidationResult(n.path("passed").asBoolean(true), n.path("confidence").asDouble(0.5), n.path("feedback").asText(""));
        } catch (Exception e) {
            return new ImageValidationResult(true, 0.0, "Vision model returned non-JSON; image was not rejected.");
        }
    }

    @Override public boolean healthCheck() {
        try { client.get().uri("/api/tags").retrieve().toBodilessEntity().block(); return true; }
        catch (Exception e) { return false; }
    }

    @Override public String providerName() { return "ollama-vision:" + model; }
}
