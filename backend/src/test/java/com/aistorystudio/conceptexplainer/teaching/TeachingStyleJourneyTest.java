package com.aistorystudio.conceptexplainer.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TeachingStyleJourneyTest {
    @Test void theJourneyStyleCarriesTheReferencePattern() {
        TeachingStyle s = TeachingStyle.parse("JOURNEY_EXPLAINER");
        assertEquals(TeachingStyle.JOURNEY_EXPLAINER, s);
        String f = s.promptFragment();
        for (String must : new String[] {"COLD OPEN", "ONE TRACKED OBJECT", "SLOW-MOTION CLOCK", "SHOW FIRST, NAME LATER", "THE BREAK", "RECAP AS A STACK", "Never invent a number"})
            assertTrue(f.contains(must), must);
    }
}
