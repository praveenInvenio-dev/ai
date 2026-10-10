package com.aistorystudio.conceptexplainer.teaching;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Voice-direction metadata for a scene: emotion, pace and delivery. It is NEVER read aloud - it is
 * translated into the parameters the active TTS engine really supports:
 * <ul>
 *   <li>Chatterbox: emotion name + intensity (-> exaggeration), speed, pitch, acting direction text</li>
 *   <li>Edge / IndicF5 / Piper: speed and pitch only (no emotion model) - emotion is expressed through pace and pitch</li>
 * </ul>
 */
public final class VoiceDirection {

    public static final Set<String> EMOTIONS = Set.of("energetic", "curious", "calm", "playful", "dramatic", "serious", "encouraging", "warm", "neutral");
    public static final Set<String> PACES = Set.of("slow", "medium", "fast");
    public static final Set<String> DELIVERIES = Set.of("conversational", "storytelling", "explanatory", "emphatic", "comedic", "reflective");

    private static final Map<String, String> EMOTION_ALIASES = Map.ofEntries(
            Map.entry("excited", "energetic"), Map.entry("enthusiastic", "energetic"), Map.entry("lively", "energetic"), Map.entry("upbeat", "energetic"),
            Map.entry("happy", "warm"), Map.entry("friendly", "warm"), Map.entry("kind", "warm"),
            Map.entry("funny", "playful"), Map.entry("humorous", "playful"), Map.entry("comedic", "playful"), Map.entry("witty", "playful"),
            Map.entry("thoughtful", "calm"), Map.entry("relaxed", "calm"), Map.entry("gentle", "calm"), Map.entry("soothing", "calm"),
            Map.entry("suspense", "dramatic"), Map.entry("surprised", "dramatic"), Map.entry("shocked", "dramatic"),
            Map.entry("mentor", "encouraging"), Map.entry("supportive", "encouraging"), Map.entry("motivating", "encouraging"),
            Map.entry("curiosity", "curious"), Map.entry("inquisitive", "curious"), Map.entry("grave", "serious"), Map.entry("firm", "serious"));
    private static final Map<String, String> PACE_ALIASES = Map.of("normal", "medium", "moderate", "medium", "quick", "fast", "brisk", "fast", "relaxed", "slow", "measured", "slow");
    private static final Map<String, String> DELIVERY_ALIASES = Map.of("casual", "conversational", "narrative", "storytelling", "story", "storytelling",
            "technical", "explanatory", "instructional", "explanatory", "emphasis", "emphatic", "funny", "comedic", "humorous", "comedic", "thoughtful", "reflective");

    private final String emotion, pace, delivery;
    private final boolean hadUnsupported;

    private VoiceDirection(String emotion, String pace, String delivery, boolean hadUnsupported) {
        this.emotion = emotion;
        this.pace = pace;
        this.delivery = delivery;
        this.hadUnsupported = hadUnsupported;
    }

    public String emotion() { return emotion; }
    public String pace() { return pace; }
    public String delivery() { return delivery; }
    /** True when the planner wrote a value we had to map to the nearest supported one. */
    public boolean hadUnsupported() { return hadUnsupported; }

    /** Normalises whatever the LLM wrote. Blank fields fall back to {@code defaultEmotion} / medium / conversational. */
    public static VoiceDirection of(String emotion, String pace, String delivery, String defaultEmotion) {
        boolean bad = false;
        String e = clean(emotion), p = clean(pace), d = clean(delivery);
        if (e.isEmpty()) e = clean(defaultEmotion);
        if (!EMOTIONS.contains(e)) {
            String alias = EMOTION_ALIASES.get(e);
            if (alias != null) { e = alias; bad = true; } else if (!e.isEmpty()) { e = "neutral"; bad = true; } else e = "neutral";
        }
        if (p.isEmpty()) p = "medium";
        if (!PACES.contains(p)) { p = PACE_ALIASES.getOrDefault(p, "medium"); bad = true; }
        if (d.isEmpty()) d = "conversational";
        if (!DELIVERIES.contains(d)) { d = DELIVERY_ALIASES.getOrDefault(d, "conversational"); bad = true; }
        return new VoiceDirection(e, p, d, bad);
    }

    private static String clean(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }

    /** What is actually sent to the TTS engine. */
    public record TtsParams(double speed, double pitch, String emotion, double intensity, String acting, boolean engineUnderstandsEmotion) { }

    /**
     * @param voice        configured voice id ("narrator-male", "edge:...", "indic:...", "profile:...")
     * @param baseSpeed    per-sentence speed already chosen by the pacing logic (questions slower etc.)
     * @param basePitch    per-sentence pitch
     * @param question     the sentence is a question
     * @param exclamation  the sentence ends with ! or contains a laugh cue
     */
    public TtsParams toTts(String voice, double baseSpeed, double basePitch, boolean question, boolean exclamation) {
        String v = voice == null ? "" : voice;
        boolean emotional = v.isEmpty() || v.startsWith("narrator-") || v.startsWith("tutor-") || v.startsWith("chatterbox:") || v.startsWith("profile:");
        double paceMul = switch (pace) { case "slow" -> 0.93; case "fast" -> 1.07; default -> 1.0; };
        double pitchMul = switch (emotion) {
            case "energetic" -> 1.012; case "playful" -> 1.010; case "curious" -> 1.008; case "encouraging" -> 1.004;
            case "calm" -> 0.994; case "serious" -> 0.990; case "dramatic" -> 0.992; default -> 1.0;
        };
        // dramatic reveals and serious emphasis land better a little slower, comedic beats a touch quicker
        if (emotion.equals("dramatic") || delivery.equals("emphatic")) paceMul *= 0.97;
        if (delivery.equals("comedic")) paceMul *= 1.02;
        String ttsEmotion;
        double intensity;
        switch (emotion) {
            case "energetic" -> { ttsEmotion = "excited"; intensity = 0.70; }
            case "playful" -> { ttsEmotion = "happy"; intensity = 0.66; }
            case "dramatic" -> { ttsEmotion = "dramatic"; intensity = 0.76; }
            case "curious" -> { ttsEmotion = "curious"; intensity = 0.58; }
            case "encouraging" -> { ttsEmotion = "happy"; intensity = 0.56; }
            case "warm" -> { ttsEmotion = "happy"; intensity = 0.50; }
            case "serious" -> { ttsEmotion = "neutral"; intensity = 0.46; }
            case "calm" -> { ttsEmotion = "neutral"; intensity = 0.38; }
            default -> { ttsEmotion = "neutral"; intensity = 0.50; }
        }
        if (question) { ttsEmotion = "curious"; intensity = Math.max(intensity, 0.58); }
        else if (exclamation && !emotion.equals("serious") && !emotion.equals("calm")) intensity = Math.min(0.85, intensity + 0.08);
        String acting = switch (emotion) {
            case "energetic" -> "Bright, upbeat tutor; smile in the voice, quick and clear.";
            case "playful" -> "Light, playful teacher; a hint of comic timing, never mocking.";
            case "dramatic" -> "Build a short beat of suspense, then deliver the reveal clearly.";
            case "curious" -> "Curious, thinking-aloud teacher; lift the question, pause before the answer.";
            case "encouraging" -> "Warm mentor; reassuring and confident.";
            case "serious" -> "Clear and precise; steady emphasis on the key point, no jokes.";
            case "calm" -> "Calm, unhurried technical explanation with natural thinking pauses.";
            case "warm" -> "Friendly, conversational tutor.";
            default -> "Natural conversational tutor.";
        };
        return new TtsParams(round(baseSpeed * paceMul), round(basePitch * pitchMul), ttsEmotion, intensity, acting, emotional);
    }

    private static double round(double d) { return Math.round(d * 1000.0) / 1000.0; }
}
