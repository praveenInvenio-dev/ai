package com.aistorystudio.videoeditor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aistorystudio.videoeditor.service.SpeechCleanupService.Options;
import com.aistorystudio.videoeditor.service.SpeechCleanupService.Result;
import com.aistorystudio.videoeditor.service.SpeechCleanupService.Seg;
import com.aistorystudio.videoeditor.service.SpeechCleanupService.Word;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Speech cleanup decisions on hand-built transcripts (no media needed). */
class SpeechCleanupTest {

    /** Builds words from "text@start-end" items. */
    private static List<Word> words(String... items) {
        List<Word> out = new ArrayList<>();
        for (String it : items) {
            String[] a = it.split("@"), t = a[1].split("-");
            out.add(new Word(a[0], Double.parseDouble(t[0]), Double.parseDouble(t[1])));
        }
        return out;
    }

    private static double kept(Result r) {
        double s = 0;
        for (double[] k : r.keep()) s += k[1] - k[0];
        return s;
    }

    private static boolean covers(Result r, double t) {
        for (double[] k : r.keep()) if (t >= k[0] && t <= k[1]) return true;
        return false;
    }

    @Test
    void fillerWordsAreCutOut() {
        List<Word> w = words("So@0.5-0.8", "um@0.9-1.3", "this@1.4-1.7", "is@1.75-1.9", "uh@2.0-2.5", "the@2.6-2.8", "dashboard@2.85-3.4");
        Result r = SpeechCleanupService.compute(w, List.of(new Seg("So um this is uh the dashboard", 0.5, 3.4)), 4.0, new Options());
        assertEquals(2, r.fillers());
        assertFalse(covers(r, 1.1), "the 'um' must be removed");
        assertFalse(covers(r, 2.25), "the 'uh' must be removed");
        assertTrue(covers(r, 0.6) && covers(r, 1.5) && covers(r, 3.0));
        assertEquals(3, r.keep().size());
    }

    @Test
    void longPausesAreShortenedButShortPausesAreKept() {
        List<Word> w = words("Hello@0.2-0.6", "there@0.7-1.0", "welcome@3.5-4.0", "back@4.1-4.4");
        Result r = SpeechCleanupService.compute(w, List.of(new Seg("Hello there", 0.2, 1.0), new Seg("welcome back", 3.5, 4.4)), 5.0, new Options());
        assertEquals(2, r.keep().size());
        assertFalse(covers(r, 2.2), "the middle of the 2.5 s silence is gone");
        assertTrue(covers(r, 0.65), "a normal 0.1 s gap between words stays");
        double removedSilence = r.keep().get(1)[0] - r.keep().get(0)[1];
        assertTrue(removedSilence > 2.0 && removedSilence < 2.5, "about 2.25 s of the 2.5 s silence is cut, a short breath stays");
        assertTrue(kept(r) < 3.2);
    }

    @Test
    void leadingAndTrailingSilenceIsTrimmed() {
        List<Word> w = words("Hi@3.0-3.3", "everyone@3.4-3.9");
        Result r = SpeechCleanupService.compute(w, List.of(new Seg("Hi everyone", 3.0, 3.9)), 9.0, new Options());
        assertEquals(1, r.keep().size());
        assertTrue(r.keep().get(0)[0] > 2.7 && r.keep().get(0)[0] <= 2.9, "starts ~0.15 s before the first word");
        assertTrue(r.keep().get(0)[1] < 4.3, "ends shortly after the last word");
    }

    @Test
    void anEarlierTakeIsDroppedWhenTheSentenceIsRepeated() {
        List<Word> w = words("You@0.5-0.7", "can@0.75-0.9", "filter@0.95-1.3", "by@1.35-1.5", "uh@1.6-1.9",
                "You@3.0-3.2", "can@3.25-3.4", "filter@3.45-3.8", "by@3.85-4.0", "team@4.05-4.4");
        List<Seg> s = List.of(new Seg("You can filter by uh", 0.5, 1.9), new Seg("You can filter by team", 3.0, 4.4));
        Result r = SpeechCleanupService.compute(w, s, 5.0, new Options());
        assertEquals(1, r.retakes());
        assertFalse(covers(r, 1.0), "the unfinished first attempt is removed");
        assertTrue(covers(r, 3.5), "the clean second take is kept");
    }

    @Test
    void sorryLetMeRedoThatRemovesBothTheBadTakeAndTheMarker() {
        List<Word> w = words("This@0.5-0.8", "is@0.85-1.0", "the@1.05-1.2", "new@1.25-1.5",
                "sorry@2.5-2.9", "let@2.95-3.1", "me@3.15-3.25", "redo@3.3-3.6", "that@3.65-3.9",
                "This@4.5-4.8", "is@4.85-5.0", "the@5.05-5.2", "new@5.25-5.5", "dashboard@5.55-6.2");
        List<Seg> s = List.of(new Seg("This is the new", 0.5, 1.5), new Seg("sorry let me redo that", 2.5, 3.9), new Seg("This is the new dashboard", 4.5, 6.2));
        Result r = SpeechCleanupService.compute(w, s, 7.0, new Options());
        assertTrue(r.retakes() >= 1);
        assertFalse(covers(r, 1.0));
        assertFalse(covers(r, 3.2), "the 'sorry let me redo that' words are cut");
        assertTrue(covers(r, 5.8));
    }

    @Test
    void differentSentencesAreNeverTreatedAsRetakes() {
        List<Word> w = words("Java@0.5-0.9", "has@0.95-1.1", "variables@1.15-1.8", "Variables@2.2-2.9", "store@3.0-3.3", "values@3.35-3.9");
        List<Seg> s = List.of(new Seg("Java has variables", 0.5, 1.8), new Seg("Variables store values", 2.2, 3.9));
        Result r = SpeechCleanupService.compute(w, s, 4.5, new Options());
        assertEquals(0, r.retakes());
        assertTrue(covers(r, 1.0) && covers(r, 3.0));
    }

    @Test
    void youKnowIsOnlyCutWhenItIsAVerbalTic() {
        // "you know" inside a sentence stays
        List<Word> keepIt = words("Do@0.5-0.7", "you@0.75-0.9", "know@0.95-1.2", "the@1.25-1.4", "answer@1.45-1.9");
        Result a = SpeechCleanupService.compute(keepIt, List.of(new Seg("Do you know the answer", 0.5, 1.9)), 2.5, new Options());
        assertEquals(0, a.fillers());
        // "you know," with a comma is a tic
        List<Word> cut = words("It@0.5-0.7", "is@0.75-0.9", "fast@0.95-1.3", "you@1.4-1.55", "know,@1.6-1.9", "really@2.0-2.4", "fast@2.45-2.9");
        Result b = SpeechCleanupService.compute(cut, List.of(new Seg("It is fast you know, really fast", 0.5, 2.9)), 3.5, new Options());
        assertEquals(1, b.fillers());
        assertFalse(covers(b, 1.7));
    }

    @Test
    void togglesTurnFillerAndRetakeRemovalOff() {
        List<Word> w = words("So@0.5-0.8", "um@0.9-1.3", "yes@1.4-1.8");
        Options o = new Options();
        o.fillers = false;
        Result r = SpeechCleanupService.compute(w, List.of(new Seg("So um yes", 0.5, 1.8)), 2.5, o);
        assertEquals(0, r.fillers());
        assertTrue(covers(r, 1.1));
    }

    @Test
    void noWordsAtAllKeepsNothingToCutAndNeverCrashes() {
        Result r = SpeechCleanupService.compute(new ArrayList<>(), new ArrayList<>(), 5.0, new Options());
        assertTrue(r.keep().isEmpty());
        assertEquals(5.0, r.removedSeconds());
    }

    @Test
    void rangesNeverOverlapAndStayInsideTheClip() {
        List<Word> w = words("A@0.1-0.3", "um@0.4-0.8", "b@0.9-1.1", "c@1.2-1.4", "d@4.0-4.2", "e@4.25-4.5");
        Result r = SpeechCleanupService.compute(w, List.of(new Seg("A um b c", 0.1, 1.4), new Seg("d e", 4.0, 4.5)), 4.6, new Options());
        double prevEnd = -1;
        for (double[] k : r.keep()) {
            assertTrue(k[0] >= 0 && k[1] <= 4.6 && k[1] > k[0]);
            assertTrue(k[0] >= prevEnd);
            prevEnd = k[1];
        }
    }
}
