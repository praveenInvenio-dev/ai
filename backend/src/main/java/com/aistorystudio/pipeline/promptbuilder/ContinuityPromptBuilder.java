package com.aistorystudio.pipeline.promptbuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Renders compact continuity constraints carried forward into each image prompt. */
public class ContinuityPromptBuilder {

    private final ObjectMapper mapper = new ObjectMapper();

    public String build(List<String> continuityFacts) {
        if (continuityFacts == null || continuityFacts.isEmpty()) return "";
        return "continuity lock: " + String.join("; ", continuityFacts);
    }

    /** Extracts only compact, image-useful facts from the persisted scene continuity JSON. */
    public String buildFromJson(String continuityJson) {
        if (continuityJson == null || continuityJson.isBlank()) return "";
        try {
            JsonNode n = mapper.readTree(continuityJson);
            List<String> facts = new ArrayList<>();
            add(facts, n, "visualStyle", "locked visual style");
            add(facts, n, "colorPalette", "locked color palette");
            add(facts, n, "locationContinuity", "location continuity");
            add(facts, n, "characterContinuity", "character continuity");
            add(facts, n, "objectContinuity", "object continuity");
            add(facts, n, "sceneIntent", "scene intent");
            return build(facts);
        } catch (Exception ignored) {
            return "";
        }
    }

    private void add(List<String> facts, JsonNode node, String field, String label) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return;
        String text = value.isArray() ? value.toString() : value.asText();
        if (text != null && !text.isBlank()) facts.add(label + ": " + text);
    }
}
