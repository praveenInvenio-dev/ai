package com.aistorystudio.h3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Prompt text for the three kinds of H3 shot a scene is made of.
 *
 * <ul>
 *   <li><b>Dialogue shot</b> (one I2V pass): an on-screen character speaks; H3 generates the
 *       voice and the matching lip movement together.</li>
 *   <li><b>Voice-over shot</b> (two passes): the narrator's words go ONLY to an audio-only H3
 *       pass. The video pass never sees the words and is told nobody talks. This is the fix
 *       for "the character mouths the narrator's line": a video model that is given spoken text
 *       will animate a mouth for it, whatever the prompt says about "off-screen". Removing the
 *       text from the visual pass removes the cause.</li>
 *   <li><b>Silent shot</b>: no speech at all (manual sequences, scenes without text).</li>
 * </ul>
 *
 * Pure Java (no Spring / Jackson) so it can be tested on its own.
 */
public final class H3ScenePrompts {

    /** One spoken piece, already cleaned by {@link IndicSpeech#prepare}. */
    public record Spoken(String speaker, String text, String delivery, boolean voiceOver, String languageTag) {
        String tag(SceneContext c) {
            return languageTag == null || languageTag.isBlank() ? c.language().tag() : languageTag;
        }
    }

    /** Parsed scene audio plan (ambience / SFX / music) plus free-text direction from the UI. */
    public record AudioCues(List<String> ambience, List<String> sfx, String musicMood, Double musicIntensity,
                            String freeText) {
        public static AudioCues empty() {
            return new AudioCues(List.of(), List.of(), null, null, null);
        }
    }

    /** Everything about the scene that is the same for every shot in it. */
    public record SceneContext(String visualDirection, String location, String emotion, String visualStyle,
                               IndicSpeech.Language language, AudioCues audio, double musicIntensityCap,
                               String narratorVoice, boolean anyDigits, boolean anyLatinWords) {
    }

    private H3ScenePrompts() {
    }

    // ------------------------------------------------------------ shot prompts

    public static String dialogueShot(SceneContext c, List<Spoken> lines, double seconds, int shotIndex,
                                      boolean hasStartImage) {
        StringBuilder p = new StringBuilder(2200);
        visualHeader(p, c, seconds, shotIndex, hasStartImage);
        List<String> speakers = new ArrayList<>();
        for (Spoken s : lines) if (!speakers.contains(s.speaker())) speakers.add(s.speaker());
        p.append("\nON-SCREEN SPEECH: ").append(String.join(", ", speakers))
                .append(speakers.size() == 1 ? " is" : " are")
                .append(" visible and speak the lines below with accurate lip sync. Only the character who is speaking at that moment moves their lips; every other character keeps their mouth closed and listens. There is no narrator and no off-screen voice in this shot.\n");
        p.append("\nspoken_dialogue:\n");
        for (Spoken s : lines) {
            p.append(clean(s.speaker())).append(" (on-screen, lip-synced");
            if (s.delivery() != null && !s.delivery().isBlank()) p.append("; delivery: ").append(clean(s.delivery()));
            p.append("): <d>[").append(s.tag(c)).append("] ").append(s.text()).append("</d>\n");
        }
        p.append("Speak every line completely, exactly as written, in order. Do not add, repeat, translate, or skip words. Finish the last word at least half a second before the shot ends.\n");
        pronunciation(p, c);
        soundscape(p, c);
        music(p, c);
        p.append("\n\nAUDIO MIX: the voices are clear, close and in front. Ambience, SFX and music stay low underneath and never mask a word.");
        return p.toString();
    }

    /** Video pass of a voice-over shot. Deliberately contains NO spoken words. */
    public static String voiceOverVisualShot(SceneContext c, double seconds, int shotIndex, boolean hasStartImage) {
        StringBuilder p = new StringBuilder(1600);
        visualHeader(p, c, seconds, shotIndex, hasStartImage);
        p.append("\nNO ON-SCREEN SPEECH: nobody in this shot talks. Every character keeps their lips closed or relaxed and still - no talking, no mouthing words, no lip movement, no singing. Show the story only through silent action, body language, gestures, eye-lines and facial emotion (smiles, surprise, curiosity) with the mouth closed.\n");
        p.append("\nspoken_dialogue:\nNone. No speech and no voices of any kind.\n");
        soundscape(p, c);
        music(p, c);
        return p.toString();
    }

    /** Audio-only pass of a voice-over shot: the narrator plus the scene's sound design. */
    public static String voiceOverAudio(SceneContext c, List<Spoken> lines, double seconds) {
        StringBuilder p = new StringBuilder(1800);
        p.append("integrated_multimodal_description:\n");
        p.append("Audio-only off-screen story narration, about ").append(fmt(seconds))
                .append(" seconds. The visual latent is disposable; only the audio matters.\n");
        p.append("Narrator: ").append(clean(c.narratorVoice()))
                .append(". Off-screen storyteller voice-over, recorded close to the microphone in a quiet room. The narrator is not a character in the scene. One single narrator voice for the whole clip.\n");
        p.append("\nspoken_dialogue:\n");
        for (Spoken s : lines) {
            p.append("Narrator (off-screen voice-over");
            String delivery = s.delivery() == null || s.delivery().isBlank() ? c.emotion() : s.delivery();
            if (delivery != null && !delivery.isBlank()) p.append("; delivery: ").append(clean(delivery));
            p.append("): <d>[").append(s.tag(c)).append("] ").append(s.text()).append("</d>\n");
        }
        p.append("Say every line completely, exactly as written. No other speakers, no added words, no singing. Finish speaking at least half a second before the clip ends, then let the ambience breathe.\n");
        pronunciation(p, c);
        soundscape(p, c);
        music(p, c);
        p.append("\n\nAUDIO MIX: narration is clear, warm and in front. Ambience, SFX and music sit well below the voice and never mask a word.");
        return p.toString();
    }

    /**
     * Video pass of a dialogue shot when the voice comes from Indic TTS: the TTS clip is
     * attached as the reference audio and H3 animates the speaker's lips to it.
     */
    public static String dialogueShotForTts(SceneContext c, List<Spoken> lines, double seconds, int shotIndex,
                                            boolean hasStartImage) {
        StringBuilder p = new StringBuilder(1800);
        visualHeader(p, c, seconds, shotIndex, hasStartImage);
        List<String> speakers = new ArrayList<>();
        for (Spoken s : lines) if (!speakers.contains(s.speaker())) speakers.add(s.speaker());
        p.append("\nON-SCREEN SPEECH: ").append(String.join(", ", speakers))
                .append(" speak on camera. The supplied reference audio is the exact recorded dialogue: match lip, jaw and face movement to it syllable by syllable. Only the speaking character moves their lips; others listen with closed mouths. Do not invent other speech.\n");
        p.append("\nspoken_dialogue:\n");
        for (Spoken s : lines) {
            p.append(clean(s.speaker())).append(" (on-screen, lip-synced to the reference audio): <d>[")
                    .append(s.tag(c)).append("] ").append(s.text()).append("</d>\n");
        }
        soundscape(p, c);
        music(p, c);
        return p.toString();
    }

    /** Audio-only pass: ambience + SFX + music bed, no voices (used under a TTS voice). */
    public static String soundscapeOnly(SceneContext c, double seconds) {
        StringBuilder p = new StringBuilder(1000);
        p.append("integrated_multimodal_description:\n");
        p.append("Audio-only sound design bed, about ").append(fmt(seconds))
                .append(" seconds. The visual latent is disposable. A separately recorded voice will be mixed on top.\n");
        if (c.visualDirection() != null && !c.visualDirection().isBlank()) p.append("Scene: ").append(clean(c.visualDirection())).append(".\n");
        p.append("\nspoken_dialogue:\nNone. Absolutely no speech, voices, singing, humming or vocal sounds.\n");
        soundscape(p, c);
        music(p, c);
        return p.toString();
    }

    public static String silentShot(SceneContext c, double seconds, int shotIndex, boolean hasStartImage) {
        StringBuilder p = new StringBuilder(1400);
        visualHeader(p, c, seconds, shotIndex, hasStartImage);
        p.append("\nspoken_dialogue:\nNone. No speech, no voices, no lip movement.\n");
        soundscape(p, c);
        music(p, c);
        return p.toString();
    }

    // ------------------------------------------------------------ sections

    private static void visualHeader(StringBuilder p, SceneContext c, double seconds, int shotIndex, boolean hasStartImage) {
        if (hasStartImage) {
            p.append("For the target video, at 0.00 seconds into the target video, <Picture 1> (from [Shot 1]) is fully referenced.\n\n");
        }
        p.append("integrated_multimodal_description:\n");
        p.append("[Shot 1, 0.00-").append(fmt(seconds)).append("s] ");
        if (hasStartImage) {
            p.append(shotIndex == 0
                    ? "<Picture 1> is the approved scene frame. Animate it faithfully and preserve the exact identity, face, age, clothing, colours, proportions, environment and composition. "
                    : "<Picture 1> is the last frame of the previous shot. Continue from it with no cut, no reset and no change of location, lighting, wardrobe, props or character identity. ");
        }
        if (c.visualDirection() != null && !c.visualDirection().isBlank()) {
            p.append("Action and camera: ").append(clean(c.visualDirection())).append(". ");
        } else {
            p.append("Subtle natural continuous movement with a slow controlled camera. ");
        }
        if (c.location() != null && !c.location().isBlank()) p.append("Environment: ").append(clean(c.location())).append(". ");
        if (c.emotion() != null && !c.emotion().isBlank()) p.append("Emotional tone: ").append(clean(c.emotion())).append(". ");
        if (c.visualStyle() != null && !c.visualStyle().isBlank()) p.append("Visual style: ").append(clean(c.visualStyle())).append(". ");
        p.append("Physically plausible motion; do not redesign the subjects or add unrelated objects, text, subtitles or captions.\n");
    }

    private static void pronunciation(StringBuilder p, SceneContext c) {
        IndicSpeech.Language l = c.language();
        p.append("\nPRONUNCIATION: ");
        if (l.indic() && !l.romanized()) {
            p.append("native ").append(l.name()).append(" speaker with a natural ").append(l.region())
                    .append(" accent. Read the ").append(l.script())
                    .append(" text exactly as written: every syllable articulated, correct short and long vowels, aspirated vs unaspirated consonants, retroflex vs dental consonants, doubled consonants held. No English or foreign accent, no anglicised vowels, do not translate or transliterate. Calm, unhurried storytelling pace with natural pauses at commas and full stops.");
            if (c.anyLatinWords()) p.append(" English words inside a line are said the way a native ").append(l.name()).append(" speaker naturally says them.");
            if (c.anyDigits()) p.append(" Read every number aloud as ").append(l.name()).append(" words.");
        } else if (l.indic()) {
            p.append(l.name()).append(" written in Latin letters: speak it as natural ").append(l.name())
                    .append(" (as in ").append(l.region()).append("), not as English. Calm, clear pace with natural pauses.");
        } else {
            p.append("clear, natural Indian English, calm storytelling pace with natural pauses.");
        }
        p.append('\n');
    }

    private static void soundscape(StringBuilder p, SceneContext c) {
        AudioCues a = c.audio() == null ? AudioCues.empty() : c.audio();
        p.append("\noverall_soundscape:\nAmbience: ");
        if (!a.ambience().isEmpty()) p.append(String.join(", ", a.ambience()));
        else if (c.location() != null && !c.location().isBlank()) p.append("quiet natural ambience of ").append(clean(c.location()));
        else p.append("quiet natural ambience appropriate to the scene");
        p.append(".\nSound effects: ");
        if (!a.sfx().isEmpty()) p.append(String.join("; ", a.sfx()));
        else p.append("only subtle sounds caused by the visible action");
        p.append('.');
        if (a.freeText() != null && !a.freeText().isBlank()) {
            p.append("\nDirection: ").append(clean(stripMusicIntensity(a.freeText(), c.musicIntensityCap())));
        }
        p.append('\n');
    }

    private static void music(StringBuilder p, SceneContext c) {
        AudioCues a = c.audio() == null ? AudioCues.empty() : c.audio();
        double cap = c.musicIntensityCap() > 0 ? c.musicIntensityCap() : 0.35;
        double intensity = a.musicIntensity() == null ? Math.min(0.25, cap) : Math.min(a.musicIntensity(), cap);
        p.append("\nnon_diegetic_music:\n");
        String mood = a.musicMood() != null && !a.musicMood().isBlank() ? clean(a.musicMood()) : moodFor(c.emotion());
        p.append("Instrumental ").append(mood).append(" background score, intensity ")
                .append(String.format(Locale.ROOT, "%.2f", intensity))
                .append(", soft and steady underneath any speech. No vocals, no singing, no humming.");
    }

    /** UI free text may say "Music: upbeat, intensity 0.7" - clamp that number too. */
    static String stripMusicIntensity(String text, double cap) {
        double c = cap > 0 ? cap : 0.35;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("intensity\\s*([0-9]*\\.?[0-9]+)").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            double v;
            try { v = Double.parseDouble(m.group(1)); } catch (NumberFormatException e) { v = c; }
            m.appendReplacement(sb, "intensity " + String.format(Locale.ROOT, "%.2f", Math.min(v, c)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String moodFor(String emotion) {
        String e = emotion == null ? "" : emotion.toLowerCase(Locale.ROOT);
        if (e.contains("sad") || e.contains("melanch")) return "soft, warm, restrained";
        if (e.contains("myst") || e.contains("curious")) return "light, airy, curious";
        if (e.contains("fear") || e.contains("scared") || e.contains("tense")) return "low, gentle suspense";
        if (e.contains("happy") || e.contains("joy") || e.contains("playful") || e.contains("fun")) return "light, playful";
        return "gentle cinematic";
    }

    private static String clean(String v) {
        return v == null ? "" : v.replaceAll("\\s+", " ").trim();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
