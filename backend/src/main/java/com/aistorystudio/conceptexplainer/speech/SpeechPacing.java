package com.aistorystudio.conceptexplainer.speech;

import java.util.Locale;

/**
 * Per-sentence pace, pitch and pause for a narration, so a lesson is not delivered at one fixed speed with one fixed gap.
 *
 * <p>A person slows down on a short punchy line, takes a breath before a reveal ("Here's the thing:"), lets a question hang,
 * flows through a long explanatory sentence, and lands the last line of a scene a little lower and slower. A fixed
 * speed/pitch/gap is the single clearest sign of a synthetic reader. Every number here is bounded and deterministic, so the
 * same script always sounds the same and the scene length stays predictable.</p>
 */
public final class SpeechPacing {

    private SpeechPacing() { }

    /**
     * @param speedMul multiplier for the base speed (about 0.93 to 1.05)
     * @param pitchMul multiplier for the base pitch (about 0.99 to 1.015)
     * @param gapAdd   extra seconds of silence after this sentence on top of the base gap (never negative)
     */
    public record Plan(double speedMul, double pitchMul, double gapAdd) {
        /** Same plan with every deviation from "neutral" scaled (0 = flat, 1 = full), for calmer audiences such as toddlers. */
        public Plan scaled(double factor) {
            return new Plan(1 + (speedMul - 1) * factor, 1 + (pitchMul - 1) * factor, gapAdd * factor);
        }
    }

    public static final Plan NEUTRAL = new Plan(1.0, 1.0, 0.0);

    public static Plan plan(String sentence, int index, int count) {
        if (sentence == null || sentence.isBlank()) return NEUTRAL;
        String s = sentence.trim();
        String bare = s.replaceAll("\\[[^\\]]*]", "").trim();
        int words = bare.isEmpty() ? 0 : bare.split("\\s+").length;
        boolean question = bare.endsWith("?") || bare.endsWith("\uFF1F");
        boolean exclaim = bare.endsWith("!");
        boolean leadIn = bare.endsWith(":") || bare.matches("(?i).*(here'?s the thing|here'?s the catch|think about it|but here'?s|so what happens|now watch|watch this)[.!]?$");
        long commas = bare.chars().filter(c -> c == ',').count();

        double speed = 1.0, pitch = 1.0, gap = 0.0;
        if (words > 0 && words <= 5 && !question) { speed *= 0.95; gap += 0.16; }        // short, punchy: let it land
        if (words >= 19) { speed *= 1.02; gap += 0.06; }                                  // long: flow through, then breathe
        if (commas >= 3) speed *= 1.01;
        if (leadIn) { speed *= 0.96; gap += 0.22; }                                       // breath before the reveal
        if (question) { speed *= 0.97; pitch *= 1.010; gap += 0.20; }                     // lift, then give the listener a moment
        if (exclaim) { speed *= 1.015; pitch *= 1.006; }
        if (index == 0 && count > 1) { pitch *= 1.005; }                                  // the hook is a touch brighter
        if (index == count - 1 && count > 1) { speed *= 0.97; pitch *= 0.996; gap += 0.10; } // land the last line
        else if (index % 3 == 1) speed *= 0.99;                                           // tiny rhythm change so no two runs of lines match

        speed = clamp(speed, 0.93, 1.05);
        pitch = clamp(pitch, 0.99, 1.015);
        return new Plan(round(speed), round(pitch), round(Math.min(gap, 0.45)));
    }

    /** Plain-text summary used in tests and in the lesson warnings ("pace 0.95x, pause +0.16 s"). */
    public static String describe(Plan p) {
        return String.format(Locale.ROOT, "pace %.2fx, pitch %.3fx, pause +%.2f s", p.speedMul(), p.pitchMul(), p.gapAdd());
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
