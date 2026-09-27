package com.aistorystudio.pipeline.promptbuilder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Structured cinematic scene specification (Phase 2: Cinematic Story +
 * Visual Intelligence). The LLM populates this per scene, in ADDITION to the
 * existing flat action/location/camera/lighting fields on Scene - this is
 * the richer structured source of truth ScenePromptBuilder renders from when
 * present; the flat fields remain the fallback for an older episode or a
 * response that only partially populated this.
 *
 * Grouped into environment/characters/lighting/camera rather than ~30 flat
 * fields, for the same reason Character/Scene themselves are grouped records
 * rather than a single wide table - it mirrors how a cinematographer actually
 * thinks about a shot (environment, subject, light, lens), and keeps
 * SceneVisualPromptRenderer's logic organized by concern instead of one long
 * if-chain over unrelated fields.
 *
 * Every field is nullable/optional by design - Jackson leaves absent fields
 * null rather than failing, so a scene where the LLM only partially filled
 * this in still renders whatever it did provide.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SceneVisualSpec(
        Environment environment,
        List<CharacterPlacement> characters,
        Lighting lighting,
        Camera camera,
        String cinematicStyle,
        List<String> continuityRequirements
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Environment(
            String location,
            String timeOfDay,
            String weather,
            String season,
            String foreground,
            String midground,
            String background,
            String atmosphere,
            String particles,
            String colorPalette,
            String mood
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CharacterPlacement(
            String name,
            String position,
            String pose,
            String expression,
            String action
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lighting(
            String keyLight,
            String fillLight,
            String rimLight,
            String lightDirection,
            String shadows,
            String reflections
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Camera(
            String position,
            String angle,
            String shotType,
            String lens,
            String focalLength,
            String framing,
            String composition,
            String depthOfField,
            String focusSubject,
            String movement
    ) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static SceneVisualSpec parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, SceneVisualSpec.class);
        } catch (Exception e) {
            // Malformed/partial JSON from the LLM degrades to "no structured
            // spec" (caller falls back to the flat fields) rather than
            // failing scene generation over a rendering enhancement.
            return null;
        }
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            return null;
        }
    }
}
