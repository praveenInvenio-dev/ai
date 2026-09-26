package com.aistorystudio.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fills a ComfyUI workflow template. Unlike naive string replacement this
 * parses the template first and injects <em>typed</em> values, so
 * {@code "steps": "{{STEPS}}"} becomes {@code "steps": 8} (a JSON number)
 * rather than the string {@code "8"} - which ComfyUI rejects with a
 * validation error on some node types.
 *
 * Text placeholders may also appear inside a larger string (e.g.
 * "{{POSITIVE_PROMPT}}, masterpiece") and are substituted in place.
 *
 * Shared by every ComfyUI-driving provider (image, video, ...) rather than
 * each keeping its own copy - the substitution mechanism itself has nothing
 * provider-specific about it, only the placeholder names and values differ
 * per workflow.
 */
public final class WorkflowTemplateFiller {

    private WorkflowTemplateFiller() {}

    public static String fill(ObjectMapper mapper, String workflowName,
                              Map<String, String> text, Map<String, Number> numeric) {
        String raw = com.aistorystudio.util.ClasspathResources.readWorkflow(workflowName);
        try {
            JsonNode root = mapper.readTree(raw);
            stripComments(root);
            substitute(mapper, root, text, numeric);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Could not fill ComfyUI workflow template '" + workflowName + "'", e);
        }
    }

    /**
     * ComfyUI's /prompt endpoint treats every top-level key as a node and rejects
     * anything without a class_type. Drop "_comment"-style keys so templates can
     * carry their own documentation without breaking validation.
     */
    private static void stripComments(JsonNode root) {
        if (root instanceof ObjectNode obj) {
            List<String> toRemove = new ArrayList<>();
            obj.fieldNames().forEachRemaining(f -> {
                if (f.startsWith("_")) {
                    toRemove.add(f);
                }
            });
            toRemove.forEach(obj::remove);
        }
    }

    private static void substitute(ObjectMapper mapper, JsonNode node,
                                   Map<String, String> text, Map<String, Number> numeric) {
        if (node instanceof ObjectNode obj) {
            List<String> fields = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                JsonNode child = obj.get(field);
                JsonNode replacement = replacementFor(mapper, child, text, numeric);
                if (replacement != null) {
                    obj.set(field, replacement);
                } else {
                    substitute(mapper, child, text, numeric);
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                JsonNode replacement = replacementFor(mapper, arr.get(i), text, numeric);
                if (replacement != null) {
                    arr.set(i, replacement);
                } else {
                    substitute(mapper, arr.get(i), text, numeric);
                }
            }
        }
    }

    private static JsonNode replacementFor(ObjectMapper mapper, JsonNode candidate,
                                           Map<String, String> text, Map<String, Number> numeric) {
        if (candidate == null || !candidate.isTextual()) {
            return null;
        }
        String value = candidate.asText();

        for (Map.Entry<String, Number> e : numeric.entrySet()) {
            if (value.equals(e.getKey())) {
                Number n = e.getValue();
                return (n instanceof Double || n instanceof Float)
                        ? mapper.getNodeFactory().numberNode(n.doubleValue())
                        : mapper.getNodeFactory().numberNode(n.longValue());
            }
        }

        String replaced = value;
        for (Map.Entry<String, String> e : text.entrySet()) {
            if (e.getValue() != null && replaced.contains(e.getKey())) {
                replaced = replaced.replace(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, Number> e : numeric.entrySet()) {
            if (replaced.contains(e.getKey())) {
                replaced = replaced.replace(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return replaced.equals(value) ? null : mapper.getNodeFactory().textNode(replaced);
    }
}
