package com.aistorystudio.pipeline.promptbuilder;

import com.aistorystudio.domain.Scene;

/** Renders the actual scene intent. The LLM's imagePrompt is intentionally included. */
public class ScenePromptBuilder {

    public String build(Scene scene) {
        StringBuilder sb = new StringBuilder();
        // The explicit imagePrompt is the scene's visual brief. Previously it was
        // persisted but never sent to ComfyUI, which made story-to-image grounding
        // depend almost entirely on short action/location fields.
        appendIfPresent(sb, scene.getImagePrompt());
        appendIfPresent(sb, scene.getAction());
        appendIfPresent(sb, scene.getLocation() != null ? "in " + scene.getLocation() : null);
        appendIfPresent(sb, scene.getEmotion() != null ? "emotion: " + scene.getEmotion() : null);
        appendIfPresent(sb, scene.getCamera());
        appendIfPresent(sb, scene.getLighting());
        return sb.toString().trim();
    }

    private void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(value.trim());
        }
    }
}
