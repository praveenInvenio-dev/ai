package com.aistorystudio.videoeditor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aistorystudio.videoeditor.service.TranscriptSearchService.Hit;
import com.aistorystudio.videoeditor.service.TranscriptSearchService.Line;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TranscriptSearchTest {

    private static final UUID C = UUID.randomUUID();

    private static List<Line> lines() {
        return List.of(
                new Line(C, "talk.mp4", 5, 11, "Welcome back to the channel, today we talk about load balancers."),
                new Line(C, "talk.mp4", 11, 19, "A load balancer shares the traffic between several servers."),
                new Line(C, "talk.mp4", 40, 47, "Let me show you the new dashboard and how filters work."),
                new Line(C, "talk.mp4", 60, 66, "Café owners love this trick for managing queues."));
    }

    @Test
    void findsTheMomentBySpokenWords() {
        List<Hit> h = TranscriptSearchService.search(lines(), "new dashboard", 5);
        assertEquals(1, h.size());
        assertEquals(40, h.get(0).start(), 0.001);
    }

    @Test
    void allWordsMustMatchAndTheBestLineComesFirst() {
        List<Hit> h = TranscriptSearchService.search(lines(), "load balancer traffic", 5);
        assertEquals(11, h.get(0).start(), 0.001, "the line with all three words wins");
    }

    @Test
    void wordStartsMatchSoPluralsAndStemsWork() {
        assertEquals(2, TranscriptSearchService.search(lines(), "balanc", 5).size());
        assertEquals(1, TranscriptSearchService.search(lines(), "queue", 5).size());
    }

    @Test
    void accentsAreFoldedForLatinLetters() {
        assertEquals(1, TranscriptSearchService.search(lines(), "cafe", 5).size());
        assertEquals(1, TranscriptSearchService.search(lines(), "CAFÉ owners", 5).size());
    }

    @Test
    void indianScriptTextIsNeverDamagedByFolding() {
        String kn = "ಇಂದು ನಾವು ಲೋಡ್ ಬ್ಯಾಲೆನ್ಸರ್ ಬಗ್ಗೆ ಕಲಿಯೋಣ";
        assertEquals(kn.toLowerCase(), TranscriptSearchService.fold(kn));
        List<Line> l = List.of(new Line(C, "a.mp4", 1, 5, kn));
        assertEquals(1, TranscriptSearchService.search(l, "ಬ್ಯಾಲೆನ್ಸರ್", 3).size());
        assertEquals(1, TranscriptSearchService.search(l, "ಕಲಿಯೋಣ", 3).size());
    }

    @Test
    void fallsBackToPartialMatchesOnlyWhenNothingMatchesFully() {
        List<Hit> h = TranscriptSearchService.search(lines(), "dashboard servers", 5);
        assertTrue(h.size() >= 2, "no line has both words, so lines with one of the two are offered");
        assertTrue(TranscriptSearchService.search(lines(), "dashboard filters", 5).get(0).start() == 40);
    }

    @Test
    void emptyQueriesAndNoMatchesReturnNothing() {
        assertTrue(TranscriptSearchService.search(lines(), "   ", 5).isEmpty());
        assertTrue(TranscriptSearchService.search(lines(), "?!", 5).isEmpty());
        assertTrue(TranscriptSearchService.search(lines(), "submarine", 5).isEmpty());
    }

    @Test
    void limitIsRespected() {
        assertEquals(1, TranscriptSearchService.search(lines(), "the", 1).size());
    }
}
