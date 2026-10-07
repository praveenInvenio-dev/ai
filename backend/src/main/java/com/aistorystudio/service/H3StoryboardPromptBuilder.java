package com.aistorystudio.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds MiniMax H3 prompts for storyboard/video flows and for the Classic Story
 * audio-only soundtrack flow. The I2V method keeps the storyboard image as the
 * visual source of truth; buildAudioOnly() keeps the Story Engine text as the
 * source of truth for speech while asking H3 for the soundtrack.
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

        // This method is the visual storyboard/H3-video prompt. Classic Story
        // Production does not call this method for its soundtrack; it calls
        // buildAudioOnly() instead.
        if (language != null && !language.isBlank()) {
            p.append("\n\nLANGUAGE / AUDIO OWNERSHIP: ").append(lang(language))
                    .append(". Do not generate spoken dialogue, narration, subtitles or captions in the video. ")
                    .append("The final multilingual narration/dialogue is supplied as a separate synchronized TTS audio track. ");
        }
        p.append("\n\nSPEECH: Visual-only storyboard generation. Never invent spoken words; preserve natural facial expression and action.");

        AudioSpec audio = readAudioSpec(audioSpecJson);
        p.append("\noverall_soundscape:\n");
        p.append("Natural ambience: ");
        if (!audio.ambience.isEmpty()) p.append(String.join(", ", audio.ambience));
        else if (location != null && !location.isBlank()) p.append(clean(location)).append(" ambience");
        else p.append("subtle environmental room or outdoor ambience");
        p.append(". Visual SFX continuity cues: ");
        if (!audio.sfx.isEmpty()) {
            for (int i = 0; i < audio.sfx.size(); i++) {
                if (i > 0) p.append("; ");
                p.append(audio.sfx.get(i));
            }
        } else {
            p.append("realistic sounds caused by the described on-screen actions only");
        }
        p.append(". Keep visible environmental effects and background motion natural, subtle and spatially consistent with the same location; final audible ambience/SFX is mixed by the production renderer under the external narration.");

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

    /** H3 sound-design-only prompt. Speech is intentionally excluded because Rumik OSS-1
     * owns all narration/dialogue in the Indian-language production path. */
    public String buildSoundscapeOnly(String sceneDescription, String audioSpecJson,
                                      String language, String emotion, double durationSeconds) {
        AudioSpec audio = readAudioSpec(audioSpecJson);
        StringBuilder p = new StringBuilder(1400);
        p.append("integrated_multimodal_description:\n");
        p.append("Sound-design-only story scene for approximately ").append(format(durationSeconds)).append(" seconds. ");
        p.append("There is a separate externally generated narration track. Do not generate or imitate any human speech.\n");
        if (sceneDescription != null && !sceneDescription.isBlank()) p.append("Scene context: ").append(clean(sceneDescription)).append("\n");
        p.append("No dialogue, narration, singing, lyrics, announcements, or vocal performance.\n\n");
        p.append("overall_soundscape:\n");
        p.append("Generate only non-verbal environmental ambience and synchronized foley/SFX. ");
        if (!audio.ambience.isEmpty()) p.append("Ambience: ").append(String.join(", ", audio.ambience)).append(". ");
        else p.append("Ambience: natural environment appropriate to the scene. ");
        if (!audio.sfx.isEmpty()) p.append("SFX: ").append(String.join("; ", audio.sfx)).append(". ");
        else p.append("SFX: only realistic sounds caused by on-screen actions. ");
        p.append("Absolutely no spoken words or human voices.\n\n");
        p.append("non_diegetic_music:\n");
        if (audio.musicMood != null && !audio.musicMood.isBlank()) {
            p.append("Instrumental background score with mood ").append(clean(audio.musicMood));
            if (audio.musicIntensity != null) p.append(", intensity ").append(String.format(Locale.ROOT, "%.2f", audio.musicIntensity));
            p.append(". No vocals.");
        } else {
            p.append(musicFor(emotion)).append(" No vocals.");
        }
        return p.toString();
    }

    /** Builds the audio-only H3 prompt used by Classic Story Production. The story remains
     * the source of truth for the spoken words; H3 supplies the voice performance plus the
     * requested ambience, SFX and music. */
    public String buildAudioOnly(String narration, String voiceSegmentsJson, String audioSpecJson,
                                 String language, String emotion, double durationSeconds) {
        List<VoiceLine> lines = readVoiceLines(narration, voiceSegmentsJson);
        AudioSpec audio = readAudioSpec(audioSpecJson);
        StringBuilder p = new StringBuilder(1600);
        p.append("integrated_multimodal_description:\n");
        p.append("Audio-only story scene. Generate the complete synchronized soundtrack for approximately ")
                .append(format(durationSeconds)).append(" seconds. Do not create a meaningful visual scene; the visual latent is disposable.\n\n");
        p.append("spoken_dialogue:\n");
        if (lines.isEmpty()) {
            p.append("No spoken dialogue.\n");
        } else {
            for (VoiceLine line : lines) {
                String speaker = line.character == null || line.character.isBlank() ? "Narrator" : clean(line.character);
                String emotionText = line.emotion == null || line.emotion.isBlank() ? (emotion == null ? "natural" : clean(emotion)) : clean(line.emotion);
                p.append("Speaker: ").append(speaker).append("; delivery: ").append(emotionText).append(". ");
                p.append("Say exactly: <d>[" ).append(lang(language)).append("] ").append(cleanDialogue(line.text)).append("</d>\n");
            }
        }
        p.append("Do not add words, narration, dialogue, lyrics, announcements, or additional speakers beyond the lines above.\n\n");
        p.append("overall_soundscape:\n");
        p.append("Ambience: ");
        if (!audio.ambience.isEmpty()) p.append(String.join(", ", audio.ambience));
        else p.append("natural ambience appropriate to the scene and location");
        p.append(".\nSound effects: ");
        if (!audio.sfx.isEmpty()) p.append(String.join("; ", audio.sfx));
        else p.append("only subtle sounds naturally caused by the described scene");
        p.append(". Keep speech clearly intelligible and foregrounded.\n\n");
        p.append("non_diegetic_music:\n");
        if (audio.musicMood != null && !audio.musicMood.isBlank()) {
            p.append("A background score with mood ").append(clean(audio.musicMood));
            if (audio.musicIntensity != null) p.append(", intensity ").append(String.format(Locale.ROOT, "%.2f", audio.musicIntensity));
            p.append(", balanced underneath the speech.");
        } else {
            p.append(musicFor(emotion));
        }
        p.append("\n\nAUDIO MIX: voices are the priority. Background ambience, SFX and music must support the story without masking speech. Keep timing coherent and avoid abrupt unrelated sounds.");
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
