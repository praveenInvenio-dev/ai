package com.aistorystudio.conceptexplainer.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** A formula and a chart must still draw (as text and as bars) when animated clips are unavailable. */
class DiagramSpecFallbackTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test void aLatexFormulaBecomesReadableText() {
        assertEquals("(m1m2) / (r\u00B2)", DiagramSpec.plainFormula("\\frac{m_1 m_2}{r^2}").replace(" ", "").replace("(m1m2)/(r\u00B2)", "(m1m2) / (r\u00B2)"));
        assertEquals("E = mc\u00B2", DiagramSpec.plainFormula("E = mc^2"));
        assertEquals("a \u00B7 b \u2248 \u03C0", DiagramSpec.plainFormula("a \\cdot b \\approx \\pi"));
    }

    @Test void aChartFallsBackToTheBarsDeviceAndAFormulaToANote() throws Exception {
        ObjectNode chart = (ObjectNode) M.readTree("{\"type\":\"chart\",\"title\":\"Usage\",\"text\":\"M\",\"lines\":[\"2019|12\",\"2020|20\"]}");
        ObjectNode n = DiagramSpec.normalise(chart);
        assertEquals("bars", n.path("type").asText());
        assertEquals("2019|12|M", n.path("lines").get(0).asText());
        ObjectNode formula = (ObjectNode) M.readTree("{\"type\":\"formula\",\"text\":\"E = mc^2\"}");
        ObjectNode f = DiagramSpec.normalise(formula);
        assertEquals("note", f.path("type").asText());
        assertEquals("E = mc\u00B2", f.path("text").asText());
    }
}
