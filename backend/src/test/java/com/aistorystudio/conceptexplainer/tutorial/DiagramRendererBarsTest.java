package com.aistorystudio.conceptexplainer.tutorial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/** A bar row must always yield label, number and unit, whichever way the planner wrote it (the reel showed empty bars). */
class DiagramRendererBarsTest {
    @Test void theDocumentedFormatIsKept() {
        assertArrayEquals(new String[] {"Single", "30", "s"}, DiagramRenderer.barRow("Single|30|s"));
    }

    @Test void aNumberInsideTheLabelIsFound() {
        assertArrayEquals(new String[] {"Single", "30", "s"}, DiagramRenderer.barRow("Single: 30|s"));
        assertArrayEquals(new String[] {"Multi", "12", "s"}, DiagramRenderer.barRow("Multi 12 s"));
        assertArrayEquals(new String[] {"Memory", "600", "MB"}, DiagramRenderer.barRow("Memory = 600 | MB"));
    }

    @Test void aRowWithoutANumberIsLeftAlone() {
        assertArrayEquals(new String[] {"Just a label"}, DiagramRenderer.barRow("Just a label"));
    }
}
