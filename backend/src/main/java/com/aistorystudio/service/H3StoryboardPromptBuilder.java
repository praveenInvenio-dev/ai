package com.aistorystudio.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the audiovisual script sent to MiniMax H3 for storyboard I2V.
 * The uploaded/generated storyboard image is the visual source of truth;
 * this prompt therefore focuses on motion, camera direction and native audio.
 */
public final class H3StoryboardPromptBuilder {

    private final ObjectMapper mapper;

    public H3StoryboardPromptBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String build(String action, String location, String emotion,
                        String narration, String voiceSegmentsJson, String audioSpecJson, String language,
                        String visualStyle, double durationSeconds, List<String> characterNames, boolean hasCharacterReference) {
        StringBuilder p = new StringBuilder(1200);
        p.append("For the target video, at 0.00 seconds into the target video, <Picture 1> (from [Shot 1]) is fully referenced.\n\n");
        p.append("integrated_multimodal_description:\n");
        p.append("[Shot 1, 0.00-").append(format(durationSeconds)).append("s] ");
        p.append("<Picture 1> is the selected storyboard frame and is the primary composition, pose, environment and scene reference. Animate it faithfully. Preserve the exact identity, face, age, clothing, colors, proportions, environment and composition from <Picture 1>. ");
        if (hasCharacterReference) {
            p.append("<Picture 2> is the uploaded master character identity reference. Use <Picture 2> as the PRIMARY identity source for the named character(s): ")
                    .append(characterNames == null || characterNames.isEmpty() ? "the on-screen character" : String.join(", ", characterNames))
                    .append(". Preserve the same facial structure, eyes, nose, mouth, hair, skin tone, age, body proportions, clothing details and defining features. Do not redesign, beautify, age, de-age or replace the character. Keep <Picture 1> in control of the scene composition and action. ");
        }
        if (action != null && !action.isBlank()) {
            p.append("Motion direction: ").append(clean(action)).append(". ");
        } else {
            p.append("Use subtle natural continuous movement appropriate to the scene. ");
        }
        if (location != null && !location.isBlank()) {
            p.append("Environment: ").append(clean(location)).append(". ");
        }
        if (emotion != null && !emotion.isBlank()) {
            p.append("Emotional tone: ").append(clean(emotion)).append(". ");
        }
        if (visualStyle != null && !visualStyle.isBlank()) {
            p.append("Visual style: ").append(clean(visualStyle)).append(". ");
        }
        p.append("Use cinematic, physically plausible motion and a controlled camera move; do not redesign the subjects or add unrelated objects.");

        List<VoiceLine> lines = readVoiceLines(narration, voiceSegmentsJson);
        java.util.Map<String, Integer> speakerIds = new java.util.LinkedHashMap<>();
        int[] nextSpeaker = {1};
        for (VoiceLine line : lines) {
            String character = line.character == null || line.character.isBlank() ? "speaker" : clean(line.character);
            String key = character.toLowerCase(Locale.ROOT);
            int speaker = speakerIds.computeIfAbsent(key, k -> nextSpeaker[0]++);
            String delivery = line.emotion == null || line.emotion.isBlank() ? "natural" : clean(line.emotion) + " delivery";
            if ("narrator".equalsIgnoreCase(character) || "voiceover".equalsIgnoreCase(character)) {
                p.append("\n(S").append(speaker).append("), the narrator, speaks in an off-screen voiceover with ")
                        .append(delivery).append(":\n<d>[").append(lang(language)).append("] ")
                        .append(cleanDialogue(line.text)).append("</d>\n")
                        .append("The on-screen character's lips remain closed while the narration is heard.");
            } else {
                p.append("\n(S").append(speaker).append("), ").append(character)
                        .append(", speaks with ").append(delivery).append(":\n<d>[")
                        .append(lang(language)).append("] ").append(cleanDialogue(line.text)).append("</d>");
            }
        }

        AudioSpec audio = readAudioSpec(audioSpecJson);
        p.append("\noverall_soundscape:\n");
        p.append("Natural ambience: ");
        if (!audio.ambience.isEmpty()) p.append(String.join(", ", audio.ambience));
        else if (location != null && !location.isBlank()) p.append(clean(location)).append(" ambience");
        else p.append("subtle environmental room or outdoor ambience");
        p.append(". Physical sound effects: ");
        if (!audio.sfx.isEmpty()) {
            for (int i = 0; i < audio.sfx.size(); i++) {
                if (i > 0) p.append("; ");
                p.append(audio.sfx.get(i));
            }
        } else {
            p.append("realistic sounds caused by the described on-screen actions only");
        }
        p.append(". Keep ambience and effects natural and below speech; synchronize effects to the visible action.");

        p.append("\n\nnon_diegetic_music:\n");
        if (audio.musicMood != null && !audio.musicMood.isBlank()) {
            p.append("A cinematic score with mood ").append(clean(audio.musicMood));
            if (audio.musicIntensity != null) p.append(", intensity ").append(String.format(Locale.ROOT, "%.2f", audio.musicIntensity));
            p.append(", subtle enough to remain underneath narration/dialogue.");
        } else {
            p.append(musicFor(emotion));
        }
        return p.toString();
    }

    private List<VoiceLine> readVoiceLines(String narration, String json) {
        List<VoiceLine> out = new ArrayList<>();
        if (json != null && !json.isBlank()) {
            try {
                List<VoiceLine> parsed = mapper.readValue(json, new TypeReference<List<VoiceLine>>() {});
                for (VoiceLine line : parsed) {
                    if (line != null && line.text != null && !line.text.isBlank()) out.add(line);
                }
            } catch (Exception ignored) { }
        }
        if (out.isEmpty() && narration != null && !narration.isBlank()) {
            out.add(new VoiceLine("Narrator", narration, null));
        }
        return out;
    }

    private AudioSpec readAudioSpec(String json) {
        AudioSpec out = new AudioSpec();
        if (json == null || json.isBlank()) return out;
        try {
            var root = mapper.readTree(json);
            if (root.path("ambience").isArray()) for (var n : root.path("ambience")) if (!n.asText().isBlank()) out.ambience.add(clean(n.asText()));
            if (root.path("sfx").isArray()) for (var n : root.path("sfx")) {
                if (n.isTextual()) out.sfx.add(clean(n.asText()));
                else if (n.isObject() && !n.path("event").asText("").isBlank()) {
                    String x = clean(n.path("event").asText());
                    if (!n.path("timing").asText("").isBlank()) x += " (" + clean(n.path("timing").asText()) + ")";
                    out.sfx.add(x);
                }
            }
            var music = root.path("music");
            if (music.isObject()) {
                out.musicMood = music.path("mood").asText("");
                if (music.has("intensity") && music.path("intensity").isNumber()) out.musicIntensity = music.path("intensity").asDouble();
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static final class AudioSpec {
        final List<String> ambience = new ArrayList<>();
        final List<String> sfx = new ArrayList<>();
        String musicMood;
        Double musicIntensity;
    }

    private String musicFor(String emotion) {
        String e = emotion == null ? "" : emotion.toLowerCase(Locale.ROOT);
        if (e.contains("sad") || e.contains("melancholy")) return "A soft, restrained emotional score with warm sustained tones, low intensity, under the narration.";
        if (e.contains("excited") || e.contains("thrilled") || e.contains("adventure")) return "A light cinematic adventure score with gentle rhythmic momentum, rising subtly without overpowering the narration.";
        if (e.contains("myst") || e.contains("curious")) return "A subtle mysterious cinematic score with airy textures and restrained tension, underneath the narration.";
        if (e.contains("happy") || e.contains("joy") || e.contains("cheer")) return "A warm uplifting cinematic score with light playful movement, kept softly underneath the narration.";
        if (e.contains("scared") || e.contains("fear") || e.contains("tense")) return "A restrained suspense score with low atmospheric tension, never overpowering the narration.";
        return "A subtle cinematic score matching the scene's emotional tone, low intensity and always underneath the narration.";
    }

    private static String clean(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String cleanDialogue(String value) {
        return clean(value).replace("<d>", "").replace("</d>", "");
    }

    private static String lang(String language) {
        if (language == null || language.isBlank()) return "English";
        return clean(language);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    public static final class VoiceLine {
        public String character;
        public String text;
        public String emotion;

        public VoiceLine() {}

        public VoiceLine(String character, String text, String emotion) {
            this.character = character;
            this.text = text;
            this.emotion = emotion;
        }
    }
}
