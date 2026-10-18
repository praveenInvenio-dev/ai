package com.aistorystudio.videoeditor.editing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aistorystudio.videoeditor.editing.TimelineExporter.Row;
import com.aistorystudio.videoeditor.editing.TimelineExporter.Result;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class TimelineExporterTest {

    private static Row row(String name, double start, double end, double speed, boolean muted, double fps) {
        return new Row(name, name, 60.0, start, end, speed, true, muted, fps, 1920, 1080);
    }

    @Test
    void edlHasCorrectTimecodesAndRecordPositionsAreContiguous() {
        List<Row> rows = List.of(row("a.mp4", 2.0, 5.0, 1.0, false, 30), row("b.mp4", 10.0, 12.5, 1.0, false, 30));
        String edl = TimelineExporter.edl("My Cut", rows).text();
        assertTrue(edl.startsWith("TITLE: My Cut\nFCM: NON-DROP FRAME"));
        assertTrue(edl.contains("001  AX       V     C        00:00:02:00 00:00:05:00 00:00:00:00 00:00:03:00"), edl);
        assertTrue(edl.contains("003  AX       V     C        00:00:10:00 00:00:12:15 00:00:03:00 00:00:05:15"), edl);
        assertTrue(edl.contains("* FROM CLIP NAME: b.mp4"));
    }

    @Test
    void edlCarriesSpeedChangesAndLeavesOutAudioOfMutedShots() {
        String edl = TimelineExporter.edl("t", List.of(row("a.mp4", 0, 4, 2.0, true, 25))).text();
        assertTrue(edl.contains("M2   AX       050.0"), edl);
        assertFalse(edl.contains(" A     C"), "a muted shot has no audio event");
        assertTrue(edl.contains("00:00:00:00 00:00:02:00"), "record length is the shortened 2 s: " + edl);
    }

    @Test
    void edlTitleCannotInjectLines() {
        String edl = TimelineExporter.edl("evil\n001 fake event", List.of(row("a.mp4", 0, 1, 1, false, 30))).text();
        assertEquals(1, edl.split("TITLE:", -1).length - 1);
        assertFalse(edl.contains("\n001 fake"));
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void fcpxmlIsWellFormedAndOffsetsAreContiguous() throws Exception {
        List<Row> rows = new ArrayList<>(List.of(row("a & b.mp4", 2.0, 5.0, 1.0, false, 30), row("c.mp4", 10.0, 12.5, 1.0, false, 30), row("a & b.mp4", 20, 21, 1.0, false, 30)));
        Result r = TimelineExporter.fcpxml("Trip <2026>", rows, 1080, 1920);
        Document d = parse(r.text());
        Element root = d.getDocumentElement();
        assertEquals("fcpxml", root.getTagName());
        assertEquals("1.9", root.getAttribute("version"));
        assertEquals(2, d.getElementsByTagName("asset").getLength(), "two distinct source files, one asset each");
        NodeList clips = d.getElementsByTagName("asset-clip");
        assertEquals(3, clips.getLength());
        assertEquals("0s", ((Element) clips.item(0)).getAttribute("offset"));
        assertEquals("90/30s", ((Element) clips.item(1)).getAttribute("offset"), "3 s = 90 frames at 30 fps");
        assertEquals("165/30s", ((Element) clips.item(2)).getAttribute("offset"), "3 s + 2.5 s = 165 frames");
        assertEquals("60/30s", ((Element) clips.item(0)).getAttribute("start"));
        assertEquals("195/30s", ((Element) d.getElementsByTagName("sequence").item(0)).getAttribute("duration"));
        assertEquals("Trip <2026>", ((Element) d.getElementsByTagName("project").item(0)).getAttribute("name"));
        assertEquals("a & b.mp4", ((Element) clips.item(0)).getAttribute("name"));
    }

    @Test
    void fcpxmlUsesExactRationalsForNtscRates() throws Exception {
        Result r = TimelineExporter.fcpxml("t", List.of(row("a.mp4", 0, 2.0, 1.0, false, 29.97)), 1920, 1080);
        Document d = parse(r.text());
        assertEquals("1001/30000s", ((Element) d.getElementsByTagName("format").item(0)).getAttribute("frameDuration"));
        long frames = Math.round(2.0 * 30000.0 / 1001.0);   // 60
        assertEquals((frames * 1001) + "/30000s", ((Element) d.getElementsByTagName("asset-clip").item(0)).getAttribute("duration"));
    }

    @Test
    void retimedShotsAreFlaggedAndMutedShotsGetSilenced() throws Exception {
        Result r = TimelineExporter.fcpxml("t", List.of(row("a.mp4", 0, 4, 2.0, true, 30)), 1080, 1920);
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("Speed"));
        assertEquals(1, parse(r.text()).getElementsByTagName("adjust-volume").getLength());
    }

    @Test
    void nearestRatePicksTheStandardRate() {
        assertEquals(30000, TimelineExporter.nearestRate(29.97).num());
        assertEquals(24000, TimelineExporter.nearestRate(23.976).num());
        assertEquals(25, TimelineExporter.nearestRate(25.0).num());
        assertEquals(60, TimelineExporter.nearestRate(60).num());
        assertEquals(30, TimelineExporter.nearestRate(0).num());
    }

    @Test
    void emptyTimelineStillProducesValidFiles() throws Exception {
        parse(TimelineExporter.fcpxml("empty", List.of(), 1920, 1080).text());
        assertTrue(TimelineExporter.edl("empty", List.of()).text().startsWith("TITLE: empty"));
    }
}
