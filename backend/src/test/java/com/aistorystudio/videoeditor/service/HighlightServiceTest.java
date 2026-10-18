package com.aistorystudio.videoeditor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aistorystudio.videoeditor.service.HighlightService.Moment;
import com.aistorystudio.videoeditor.service.HighlightService.Seg;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HighlightServiceTest {

    /** n sentences, each 6 s long, back to back. */
    private static List<Seg> talk(int n, String... special) {
        List<Seg> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String text = i < special.length && special[i] != null ? special[i] : "This is a plain sentence number " + i + " about the topic.";
            out.add(new Seg(text, i * 6.0, i * 6.0 + 5.8));
        }
        return out;
    }

    @Test
    void snapRangeExtendsAShortRangeToTheMinimumLength() {
        List<Seg> s = talk(20);
        double[] r = HighlightService.snapRange(s, 4, 4, 120);
        assertNotNull(r);
        assertTrue(r[1] - r[0] >= 15 && r[1] - r[0] <= 25, "extended over following sentences: " + (r[1] - r[0]));
        assertEquals(24.0 - 0.15, r[0], 0.001, "starts at the sentence start (minus a breath)");
    }

    @Test
    void snapRangeTrimsAnOverlongRangeAtASentenceEnd() {
        List<Seg> s = talk(30);
        double[] r = HighlightService.snapRange(s, 0, 25, 200);
        assertNotNull(r);
        assertTrue(r[1] - r[0] <= 63.5, "never longer than the cap: " + (r[1] - r[0]));
        double endsOnSentence = (r[1] - 0.25) % 6.0;
        assertTrue(Math.abs(endsOnSentence - 5.8) < 0.01, "ends exactly on a sentence end: " + endsOnSentence);
    }

    @Test
    void snapRangeRejectsMomentsThatCannotReachTheMinimumLength() {
        List<Seg> s = talk(3);                                  // whole recording is 17.8 s but ask from the last sentence
        assertNull(HighlightService.snapRange(s, 2, 2, 17.8));
    }

    @Test
    void snapRangeClampsBadIndices() {
        List<Seg> s = talk(20);
        assertNotNull(HighlightService.snapRange(s, -5, 99, 120));
        assertNull(HighlightService.snapRange(new ArrayList<>(), 0, 1, 10));
    }

    @Test
    void heuristicPrefersAHookAndNeverStartsMidThought() {
        List<Seg> s = talk(40);
        s.set(10, new Seg("Why do most beginners make this one huge mistake?", 60, 65.8));
        s.set(11, new Seg("Nobody tells you that the secret is 3 simple rules.", 66, 71.8));
        s.set(20, new Seg("and then we moved to the next part of the story", 120, 125.8));
        List<Moment> m = HighlightService.heuristic(UUID.randomUUID(), "talk.mp4", s, 240, 3);
        assertFalse(m.isEmpty());
        assertTrue(m.stream().anyMatch(x -> Math.abs(x.start() - (60 - 0.15)) < 0.01), "the question-hook window is picked");
        assertTrue(m.stream().noneMatch(x -> Math.abs(x.start() - (120 - 0.15)) < 0.01), "a window starting with 'and then' is skipped");
    }

    @Test
    void momentsStayNonOverlappingAndInsideTheRecording() {
        List<Seg> s = talk(60);
        for (int i = 0; i < 60; i += 5) s.set(i, new Seg("Why does this matter so much " + i + "?", i * 6.0, i * 6.0 + 5.8));
        List<Moment> m = HighlightService.heuristic(UUID.randomUUID(), "talk.mp4", s, 360, 5);
        double prevEnd = -1;
        for (Moment x : m) {
            assertTrue(x.start() >= 0 && x.end() <= 360 && x.end() - x.start() >= 15 && x.end() - x.start() <= 63.5);
            assertTrue(x.start() >= prevEnd - 0.25 * (x.end() - x.start()), "no heavy overlap");
            prevEnd = x.end();
        }
        assertTrue(m.size() >= 3);
    }

    @Test
    void topNonOverlappingKeepsTheBestOfTwoOverlappingMoments() {
        UUID clip = UUID.randomUUID();
        List<Moment> in = List.of(
                new Moment(clip, "a", 10, 40, "low", "", 4),
                new Moment(clip, "a", 15, 45, "high", "", 9),
                new Moment(clip, "a", 100, 130, "other", "", 6));
        List<Moment> out = HighlightService.topNonOverlapping(in, 5);
        assertEquals(2, out.size());
        assertEquals("high", out.get(0).title());
        assertEquals("other", out.get(1).title());
    }

    @Test
    void momentsFromDifferentClipsNeverClash() {
        List<Moment> in = List.of(new Moment(UUID.randomUUID(), "a", 10, 40, "x", "", 5), new Moment(UUID.randomUUID(), "b", 10, 40, "y", "", 5));
        assertEquals(2, HighlightService.topNonOverlapping(in, 5).size());
    }

    @Test
    void shortRecordingsProduceNoHeuristicMoments() {
        assertTrue(HighlightService.heuristic(UUID.randomUUID(), "a", talk(3), 18, 3).isEmpty());
    }
}
