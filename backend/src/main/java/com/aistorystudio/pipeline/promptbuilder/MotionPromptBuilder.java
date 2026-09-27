package com.aistorystudio.pipeline.promptbuilder;

import com.aistorystudio.domain.Scene;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a Wan/ComfyUI-style motion prompt + negative prompt per scene,
 * during story generation, from fields the scene already has (action,
 * emotion, charactersJson, lighting) - not a new LLM call. Deterministic
 * on purpose, matching how FFmpegProcessor.chooseMovement/
 * chooseEnvironmentEffect already pick motion/ambience from scene text via
 * keyword matching rather than another model call.
 *
 * This is a v1 heuristic, explicitly not the fuller "LLM writes the actual
 * delivery" approach discussed for expressive narration - same category of
 * improvement, deliberately smaller scope for a first pass. Good enough to
 * pre-fill the Video Generation page's prompt fields so a user isn't staring
 * at a blank textarea; not a substitute for hand-tuning a prompt for a scene
 * that actually matters to get right.
 */
public class MotionPromptBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BASE_NEGATIVE =
            "morphing, warping, distorted face, extra limbs, extra fingers, flickering, "
            + "blurry, deformed paws, unnatural movement, text, watermark";

    private static final String MULTI_CHARACTER_NEGATIVE =
            ", character blending into each other, characters merging, limb confusion between characters";

    public String buildMotionPrompt(Scene scene) {
        SceneVisualSpec spec = SceneVisualSpec.parse(scene.getVisualSpecJson());
        if (spec != null) {
            String fromSpec = buildFromSpec(spec, scene);
            if (fromSpec != null && !fromSpec.isBlank()) {
                return fromSpec;
            }
        }
        return buildFromFlatFields(scene);
    }

    /** Same "what must remain unchanged" idea the brief calls for is already
     *  handled structurally elsewhere - the starting image IS the unchanged
     *  anchor (Wan22ImageToVideoLatent conditions on it directly), and
     *  buildMotionNegativePrompt() already keeps identity/consistency out of
     *  scope for morphing. This method only adds WHAT SHOULD move, pulled
     *  from the same spec that drove the still image, so video motion is
     *  actually describing the same shot rather than reinventing it. */
    private String buildFromSpec(SceneVisualSpec spec, Scene scene) {
        List<String> characterNames = parseCharacterNames(scene.getCharactersJson());
        StringBuilder sb = new StringBuilder();

        if (spec.characters() != null && !spec.characters().isEmpty()) {
            for (SceneVisualSpec.CharacterPlacement c : spec.characters()) {
                String subject = c.name() != null ? "the " + c.name().toLowerCase(Locale.ROOT) : "the character";
                String action = c.action() != null ? c.action() : "subtle breathing and natural micro-movement";
                appendComma(sb, subject + " " + action);
            }
        } else {
            appendCharacterAction(sb, characterNames, scene.getAction());
        }

        if (spec.environment() != null) {
            SceneVisualSpec.Environment env = spec.environment();
            if (env.particles() != null) { appendComma(sb, env.particles() + " drifting through the scene"); }
            appendAmbientMotion(sb, env.atmosphere(), env.location());
        } else {
            appendAmbientMotion(sb, scene.getLighting(), scene.getLocation());
        }

        if (spec.camera() != null && spec.camera().movement() != null) {
            appendComma(sb, spec.camera().movement());
        } else {
            appendCameraClause(sb, scene.getCamera());
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    private String buildFromFlatFields(Scene scene) {
        List<String> characterNames = parseCharacterNames(scene.getCharactersJson());
        StringBuilder sb = new StringBuilder();

        appendCharacterAction(sb, characterNames, scene.getAction());
        appendEmotionCue(sb, characterNames, scene.getEmotion());
        appendAmbientMotion(sb, scene.getLighting(), scene.getLocation());
        appendCameraClause(sb, scene.getCamera());

        return sb.length() > 0 ? sb.toString() : "gentle idle motion, subtle breathing, camera holds steady";
    }

    public String buildMotionNegativePrompt(Scene scene) {
        List<String> characterNames = parseCharacterNames(scene.getCharactersJson());
        return characterNames.size() > 1 ? BASE_NEGATIVE + MULTI_CHARACTER_NEGATIVE : BASE_NEGATIVE;
    }

    private void appendCharacterAction(StringBuilder sb, List<String> names, String action) {
        if (action == null || action.isBlank()) {
            return;
        }
        String subject = names.isEmpty() ? "the character" : "the " + names.get(0).toLowerCase(Locale.ROOT);
        // Reuse the scene's own action text (already natural-language, written
        // for the image prompt) rather than re-deriving it - it already
        // describes what's happening, just needs a motion-oriented framing.
        sb.append(subject).append(" ").append(action.trim());
    }

    private void appendEmotionCue(StringBuilder sb, List<String> names, String emotion) {
        if (emotion == null || emotion.isBlank()) {
            return;
        }
        String secondSubject = names.size() > 1 ? "the " + names.get(1).toLowerCase(Locale.ROOT) : "the character";
        String cue = emotionCue(emotion.toLowerCase(Locale.ROOT));
        appendComma(sb, secondSubject + " " + cue);
    }

    /** Small, deliberately generic set - covers the common emotion words this
     *  project's own scene generation already produces (see StoryEngineService's
     *  emotion field), not an exhaustive taxonomy. Falls back to a neutral cue
     *  for anything unmatched rather than guessing. */
    private String emotionCue(String emotion) {
        if (emotion.contains("worried") || emotion.contains("scared") || emotion.contains("nervous")) {
            return "eyes widen slightly, ears twitching nervously";
        }
        if (emotion.contains("happy") || emotion.contains("joy") || emotion.contains("excited")) {
            return "tail wagging gently, bouncing with excitement";
        }
        if (emotion.contains("curious")) {
            return "tilts head curiously, blinking";
        }
        if (emotion.contains("sad")) {
            return "shoulders drooping slightly, a soft sigh";
        }
        if (emotion.contains("surprised")) {
            return "eyes widen, a small startled flinch";
        }
        return "listens and blinks naturally";
    }

    /** Same keyword-matching spirit as FFmpegProcessor.chooseEnvironmentEffect -
     *  a small ambient-motion phrase from the scene's own location/lighting
     *  text, not a new field to generate. */
    private void appendAmbientMotion(StringBuilder sb, String lighting, String location) {
        String haystack = ((lighting == null ? "" : lighting) + " " + (location == null ? "" : location))
                .toLowerCase(Locale.ROOT);
        String ambient;
        if (haystack.contains("forest") || haystack.contains("tree") || haystack.contains("wood")) {
            ambient = "leaves rustling softly in the background";
        } else if (haystack.contains("water") || haystack.contains("river") || haystack.contains("pond") || haystack.contains("sea")) {
            ambient = "water gently rippling in the background";
        } else if (haystack.contains("night") || haystack.contains("stars") || haystack.contains("moon")) {
            ambient = "soft twinkling light, gentle night breeze";
        } else if (haystack.contains("sun") || haystack.contains("bright") || haystack.contains("day")) {
            ambient = "warm sunlight flickering through the scene";
        } else {
            ambient = "subtle ambient motion in the background";
        }
        appendComma(sb, ambient);
    }

    private void appendCameraClause(StringBuilder sb, String camera) {
        String haystack = camera == null ? "" : camera.toLowerCase(Locale.ROOT);
        String clause;
        if (haystack.contains("push") || haystack.contains("zoom in")) {
            clause = "slow cinematic camera push-in";
        } else if (haystack.contains("pull") || haystack.contains("zoom out")) {
            clause = "slow camera pull-back";
        } else if (haystack.contains("pan")) {
            clause = "gentle camera pan";
        } else {
            clause = "camera holds steady";
        }
        appendComma(sb, clause);
    }

    private void appendComma(StringBuilder sb, String value) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(value);
    }

    private List<String> parseCharacterNames(String charactersJson) {
        List<String> names = new ArrayList<>();
        if (charactersJson == null || charactersJson.isBlank()) {
            return names;
        }
        try {
            JsonNode arr = MAPPER.readTree(charactersJson);
            for (JsonNode n : arr) {
                String name = n.isTextual() ? n.asText() : n.path("name").asText(null);
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        } catch (Exception ignored) {
            // Malformed/empty characters JSON degrades to a generic "the
            // character" subject rather than failing scene generation over a
            // cosmetic prompt-building detail.
        }
        return names;
    }
}
