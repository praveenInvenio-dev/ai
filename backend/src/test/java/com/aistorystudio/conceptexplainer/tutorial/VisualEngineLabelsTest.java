package com.aistorystudio.conceptexplainer.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The 3D scene shows the lesson's own words, not placeholders. */
class VisualEngineLabelsTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test void layersComeFromTheLayersDevice() throws Exception {
        var plan = M.readTree("{\"elements\":[{\"type\":\"photo\",\"text\":\"chai stall\"},{\"type\":\"layers\",\"lines\":[\"Your app|what you write\",\"JVM|runs the threads\",\"Operating system|schedules them\",\"CPU cores|do the work\"]}]}");
        assertEquals(List.of("Your app", "JVM", "Operating system", "CPU cores"), VisualEngineRenderClient.labelsFor(plan, "layers"));
    }

    @Test void lanesUseTheStepsAndSeparatorsAreDropped() throws Exception {
        var plan = M.readTree("{\"elements\":[{\"type\":\"steps\",\"lines\":[\"Take order|\",\"Brew tea|slow\",\"Payment\"]}]}");
        assertEquals(List.of("Take order", "Brew tea", "Payment"), VisualEngineRenderClient.labelsFor(plan, "lanes"));
    }

    @Test void oldPlansAndEmptyPlansStillWork() throws Exception {
        assertEquals(List.of("A", "B", "C"), VisualEngineRenderClient.labelsFor(M.readTree("{\"items\":[\"A\",\"B\",\"C\"]}"), "orbit"));
        assertTrue(VisualEngineRenderClient.labelsFor(M.readTree("{}"), "axes").isEmpty());
    }

    @Test void longNamesAreShortenedAndMarkupIsRemoved() throws Exception {
        var plan = M.readTree("{\"elements\":[{\"type\":\"steps\",\"lines\":[\"<b>An extremely long step name that would never fit on a label</b>\",\"B\",\"C\"]}]}");
        var labels = VisualEngineRenderClient.labelsFor(plan, "lanes");
        assertTrue(labels.get(0).length() <= 22);
        assertTrue(!labels.get(0).contains("<"));
    }
}
