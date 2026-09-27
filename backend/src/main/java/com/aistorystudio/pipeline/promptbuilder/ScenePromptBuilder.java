package com.aistorystudio.pipeline.promptbuilder;

import com.aistorystudio.domain.Scene;

/**
 * Renders the actual scene intent. Two paths: the rich structured
 * SceneVisualSpec (Phase 2 - see that class) when the scene has one, or the
 * original flat action/location/emotion/camera/lighting fields when it
 * doesn't. The flat path is not legacy code being phased out - it's the
 * permanent fallback for whenever the structured spec is absent or partial,
 * so a scene never renders with LESS detail than before this upgrade.
 */
public class ScenePromptBuilder {

    public String build(Scene scene) {
        SceneVisualSpec spec = SceneVisualSpec.parse(scene.getVisualSpecJson());
        if (spec != null) {
            String rendered = renderFromSpec(spec, scene);
            if (rendered != null && !rendered.isBlank()) {
                return rendered;
            }
        }
        return buildFromFlatFields(scene);
    }

    /** The example in the brief - "a medium-wide cinematic shot from
     *  slightly below Bunny's eye level, 35mm lens, Bunny positioned on the
     *  left third of the frame, walking along a narrow stone path..." - is a
     *  single flowing cinematic description, not a keyword list. This
     *  builds toward that shape: camera framing first (it sets the whole
     *  shot), then subjects and their placement/action, then environment
     *  layers back to front, then light and atmosphere last. */
    private String renderFromSpec(SceneVisualSpec spec, Scene scene) {
        StringBuilder sb = new StringBuilder();

        appendCameraOpening(sb, spec.camera());
        appendCharacters(sb, spec.characters(), scene);
        appendEnvironment(sb, spec.environment());
        appendLighting(sb, spec.lighting());
        appendComma(sb, spec.cinematicStyle());
        appendComma(sb, scene.getImagePrompt()); // still the LLM's own visual brief - never dropped

        return sb.toString().trim();
    }

    private void appendCameraOpening(StringBuilder sb, SceneVisualSpec.Camera cam) {
        if (cam == null) return;
        StringBuilder shot = new StringBuilder();
        appendCommaLocal(shot, cam.shotType());
        appendCommaLocal(shot, cam.angle() != null ? "from " + cam.angle() : null);
        appendCommaLocal(shot, cam.lens() != null ? cam.lens() + " lens" : cam.focalLength());
        appendCommaLocal(shot, cam.position());
        if (shot.length() > 0) {
            appendComma(sb, shot.toString());
        }
    }

    private void appendCharacters(StringBuilder sb, java.util.List<SceneVisualSpec.CharacterPlacement> chars, Scene scene) {
        if (chars == null || chars.isEmpty()) {
            appendComma(sb, scene.getAction());
            return;
        }
        for (SceneVisualSpec.CharacterPlacement c : chars) {
            StringBuilder part = new StringBuilder();
            if (c.name() != null) part.append(c.name());
            appendCommaLocal(part, c.position() != null ? "positioned " + c.position() : null);
            appendCommaLocal(part, c.pose());
            appendCommaLocal(part, c.action());
            appendCommaLocal(part, c.expression() != null ? "expression: " + c.expression() : null);
            if (part.length() > 0) {
                appendComma(sb, part.toString());
            }
        }
    }

    private void appendEnvironment(StringBuilder sb, SceneVisualSpec.Environment env) {
        if (env == null) return;
        appendComma(sb, env.location());
        appendComma(sb, joinNonNull(env.timeOfDay(), env.weather(), env.season()));
        appendComma(sb, env.foreground() != null ? "foreground: " + env.foreground() : null);
        appendComma(sb, env.midground() != null ? "midground: " + env.midground() : null);
        appendComma(sb, env.background() != null ? "background: " + env.background() : null);
        appendComma(sb, env.particles());
        appendComma(sb, env.colorPalette());
        appendComma(sb, env.mood());
    }

    private void appendLighting(StringBuilder sb, SceneVisualSpec.Lighting light) {
        if (light == null) return;
        appendComma(sb, light.keyLight());
        appendComma(sb, light.lightDirection() != null ? "light from " + light.lightDirection() : null);
        appendComma(sb, light.shadows());
        appendComma(sb, light.reflections());
    }

    private String buildFromFlatFields(Scene scene) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, scene.getImagePrompt());
        appendIfPresent(sb, scene.getAction());
        appendIfPresent(sb, scene.getLocation() != null ? "in " + scene.getLocation() : null);
        appendIfPresent(sb, scene.getEmotion() != null ? "emotion: " + scene.getEmotion() : null);
        appendIfPresent(sb, scene.getCamera());
        appendIfPresent(sb, scene.getLighting());
        return sb.toString().trim();
    }

    private String joinNonNull(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(p.trim());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(value.trim());
        }
    }

    private void appendComma(StringBuilder sb, String value) {
        appendIfPresent(sb, value);
    }

    private void appendCommaLocal(StringBuilder sb, String value) {
        appendIfPresent(sb, value);
    }
}

