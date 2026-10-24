package com.aistorystudio.conceptexplainer.tutorial;

import java.util.ArrayList;
import java.util.List;

/**
 * When each step of an animated step flow should appear, from the real start time of every narration sentence.
 *
 * <p>A scene has one frame / beat per sentence but the flow has its own number of steps (3 to 5). Step i appears when the sentence that first needs it starts:
 * the sentences are spread over the steps so the LAST sentence shows the last step. Same mapping the still-frame export uses, so a fallback to stills
 * looks like the same scene.</p>
 */
public final class MotionTiming {
    private MotionTiming() { }

    /** Seconds before the sentence starts that the step begins to appear, so the picture is already moving when the voice names it. */
    public static final double LEAD_SECONDS = 0.15;

    /** One reveal time (seconds) per step; empty when there is no sentence timing (the clip then spaces the steps evenly). */
    public static List<Double> reveals(List<Double> sentenceStarts, int steps) {
        List<Double> out = new ArrayList<>();
        if (sentenceStarts == null || sentenceStarts.isEmpty() || steps < 1) return out;
        int count = sentenceStarts.size();
        for (int i = 0; i < steps; i++) {
            int k = 0;
            if (count > 1) {
                k = count - 1;
                for (int j = 0; j < count; j++) {
                    long step = Math.round(j * (steps - 1) / (double) (count - 1));
                    if (step >= i) { k = j; break; }
                }
            }
            out.add(Math.max(0, sentenceStarts.get(k) - LEAD_SECONDS));
        }
        return out;
    }
}
