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

    @Test void labelsCanBeDerivedFromTheScenesOwnSentences() {
        var labels = VisualEngineRenderClient.deriveLabels("Let's apply this to AI. Faster AI should save us time. ScribeAI makes blog posts much cheaper. Do we just write less now? Wrong.", 4);
        assertEquals(List.of("Apply this to AI", "Faster AI should save", "ScribeAI makes blog"), labels);
    }

    @Test void derivedLabelsSkipQuestionsAndStayShort() {
        var labels = VisualEngineRenderClient.deriveLabels("Why? It's the entry barrier. Expensive AI was for critical tasks. Now, it works for everything. Old tasks are now cheap.", 4);
        assertTrue(labels.size() >= 3);
        assertTrue(labels.stream().allMatch(l -> l.length() <= 24 && !l.endsWith("?")));
        assertTrue(VisualEngineRenderClient.deriveLabels(null, 4).isEmpty());
    }

    @Test void sceneLabelsPreferThePlanThenTheNarration() {
        assertEquals(List.of("Take order", "Brew tea", "Payment"),
                VisualEngineRenderClient.labelsForScene("{\"elements\":[{\"type\":\"steps\",\"lines\":[\"Take order|\",\"Brew tea|slow\",\"Payment\"]}]}", "flow", "Anything."));
        assertEquals(List.of("Apply this to AI", "Faster AI should save", "ScribeAI makes blog"),
                VisualEngineRenderClient.labelsForScene("not json", "flow", "Let's apply this to AI. Faster AI should save us time. ScribeAI makes blog posts much cheaper. Do we just write less now? Wrong."));
    }

    @Test void theKickerIsThePlansOwnLabel() {
        assertEquals("02 \u00B7 1865", VisualEngineRenderClient.kickerOf("{\"label\":\"02 \u00B7 1865\",\"elements\":[]}"));
        assertEquals("", VisualEngineRenderClient.kickerOf("not json"));
        assertEquals("", VisualEngineRenderClient.kickerOf(null));
    }

    @Test void hindiNarrationNeverBecomesOnScreenLabels() {
        assertTrue(VisualEngineRenderClient.deriveLabels("\u090F\u0915 \u092A\u0939\u0947\u0932\u0940 \u0938\u0941\u0928\u093F\u090F\u0964 \u090F\u0906\u0908 \u0938\u0938\u094D\u0924\u093E \u0939\u094B \u0930\u0939\u093E \u0939\u0948\u0964 \u0939\u092E \u0909\u0938\u0947 \u091C\u093C\u094D\u092F\u093E\u0926\u093E \u0907\u0938\u094D\u0924\u0947\u092E\u093E\u0932 \u0915\u0930\u0924\u0947 \u0939\u0948\u0902\u0964", 4).isEmpty());
    }

    @Test void theCodeDeviceGivesFileNameAndCode() {
        var c = VisualEngineRenderClient.codeOf("{\"elements\":[{\"type\":\"code\",\"title\":\"Worker.java\",\"content\":\"class A {}\\nnew A();\"}]}");
        assertEquals("Worker.java", c[0]);
        assertTrue(c[1].contains("new A();"));
        assertEquals("", VisualEngineRenderClient.codeOf("{}")[1]);
    }

    @Test void onlyDrawableDiagramElementsAreSentToTheRenderer() {
        var els = VisualEngineRenderClient.diagramElements("{\"elements\":[{\"type\":\"tile\",\"id\":\"a\"},{\"type\":\"arrow\",\"from\":\"a\",\"to\":\"b\"},{\"type\":\"terminal\",\"text\":\"ls\"},{\"type\":\"photo\"}]}");
        assertEquals(2, els.size());
        assertEquals("arrow", els.get(1).path("type").asText());
        assertEquals(0, VisualEngineRenderClient.diagramElements("not json").size());
    }

    @Test void chartDataAndFormulasAreReadFromThePlan() {
        var chart = VisualEngineRenderClient.chartOf("{\"elements\":[{\"type\":\"chart\",\"title\":\"Usage\",\"style\":\"bar\",\"text\":\"M\",\"lines\":[\"2019|12\",\"2020|20 million\",\"bad line\"]}]}");
        assertEquals("bar", chart.path("style").asText());
        assertEquals(2, chart.path("data").size());
        assertEquals(20.0, chart.path("data").get(1).path("value").asDouble(), 1e-9);
        assertEquals("Usage", chart.path("title").asText());
        assertEquals(0, VisualEngineRenderClient.chartOf("{}").path("data").size());
        var fs = VisualEngineRenderClient.formulaList("{\"elements\":[{\"type\":\"formula\",\"title\":\"Energy\",\"text\":\"E = mc^2\",\"lines\":[\"mass holds energy\"],\"at\":1}]}");
        assertEquals("E = mc^2", fs.get(0).path("tex").asText());
        assertEquals("mass holds energy", fs.get(0).path("caption").asText());
        assertEquals(1, fs.get(0).path("at").asInt());
    }

    @Test void aFormulaLegendAndStepDetailsAreKeptForTheAnimatedScenes() {
        var f = VisualEngineRenderClient.formulaList("{\"elements\":[{\"type\":\"formula\",\"text\":\"F = m \\u00D7 a\",\"lines\":[\"F|net force (N)\",\"m|mass (kg)\",\"a|acceleration\"]}]}");
        assertEquals("F = net force (N)   \u00B7   m = mass (kg)   \u00B7   a = acceleration", f.get(0).path("caption").asText());
        var plan = "{\"elements\":[{\"type\":\"steps\",\"lines\":[\"Net force|10 N (weight and normal cancel)\",\"Mass|2 kg\",\"a = F / m|10 / 2 = 5 m/s2\"]}]}";
        var labels = VisualEngineRenderClient.withDetails(java.util.List.of("Net force", "Mass", "a = F / m"), plan);
        assertEquals("Net force|10 N (weight and normal cancel)", labels.get(0));
        assertEquals("a = F / m|10 / 2 = 5 m/s2", labels.get(2));
        assertEquals(java.util.List.of("A", "B", "C"), VisualEngineRenderClient.withDetails(java.util.List.of("A", "B", "C"), "{}"));
    }

    @Test void theMascotAndHisLinesAreReadFromThePlan() {
        var m = VisualEngineRenderClient.mascotOf("{\"elements\":[{\"type\":\"mascot\",\"text\":\"Ollie\",\"style\":\"wow\"},{\"type\":\"speech\",\"text\":\"Why does the sun go?\",\"at\":1}]}");
        assertEquals("Ollie", m.path("name").asText()); assertEquals("wow", m.path("mood").asText());
        assertEquals(1, m.path("speeches").size()); assertEquals(1, m.path("speeches").get(0).path("at").asInt());
        assertEquals("happy", VisualEngineRenderClient.mascotOf("{\"elements\":[{\"type\":\"mascot\",\"style\":\"angry\"}]}").path("mood").asText());
    }
}
