package com.aistorystudio.conceptexplainer.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MotionTimingTest {
    @Test void everyStepStartsWithItsSentenceAndTheLastSentenceShowsTheLastStep() {
        // 5 sentences, 3 steps (same rounding as the still-frame export): sentences map to steps 0,1,1,2,2 so step 1 starts with sentence 1 and step 2 with sentence 3
        var r = MotionTiming.reveals(List.of(0.0, 3.0, 6.0, 9.0, 12.0), 3);
        assertEquals(3, r.size());
        assertEquals(0.0, r.get(0), 1e-9);
        assertEquals(2.85, r.get(1), 1e-9);
        assertEquals(8.85, r.get(2), 1e-9);
    }

    @Test void moreStepsThanSentencesStillRevealsEverythingInOrder() {
        var r = MotionTiming.reveals(List.of(0.0, 4.0), 4);
        assertEquals(4, r.size());
        for (int i = 1; i < r.size(); i++) assertTrue(r.get(i) >= r.get(i - 1));
        assertTrue(r.get(3) <= 4.0);
    }

    @Test void noSentenceTimingMeansNoRevealsAndASingleSentenceShowsEverythingAtOnce() {
        assertTrue(MotionTiming.reveals(List.of(), 3).isEmpty());
        assertTrue(MotionTiming.reveals(null, 3).isEmpty());
        assertEquals(List.of(0.0, 0.0, 0.0), MotionTiming.reveals(List.of(0.1), 3));
    }
}
