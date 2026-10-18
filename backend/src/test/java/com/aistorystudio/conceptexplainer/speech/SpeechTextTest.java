package com.aistorystudio.conceptexplainer.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Written-to-spoken conversion, script humanizing and natural pacing (all pure Java). */
class SpeechTextTest {

    private static String say(String s) { return SpokenText.forSpeech(s, "English"); }

    // ---------------------------------------------------------------- SpokenText
    @Test void codeSymbolsBecomeWords() {
        assertEquals("Two threads run count plus plus at the same time, and the final value is wrong.",
                say("Two threads run count++ at the same time, and the final value is wrong."));
        assertEquals("Sorting with a nested loop is big O of n squared, but a hash lookup is big O of one.",
                say("Sorting with a nested loop is O(n^2), but a hash lookup is O(1)."));
        assertEquals("Call get user name and read user id from the hash map.", say("Call getUserName() and read user_id from the HashMap."));
        assertEquals("Thread dot start then file dot txt then x squared plus y squared equals r squared.",
                say("Thread.start() then file.txt then x\u00B2 + y\u00B2 = r\u00B2"));
    }

    @Test void numbersUnitsAndAbbreviationsAreSpoken() {
        assertEquals("The C P U, central processing unit, executes roughly 3.5 gigahertz of instructions per second.",
                say("The CPU (central processing unit) executes roughly 3.5GHz of instructions per second."));
        assertEquals("Costs rose 45 percent to 2.5 million dollars, that is 10 times more than 2020 to 2024, for example in Q3.",
                say("Costs rose 45% to $2.5M, i.e. 10x more than 2020-2024, e.g. in Q3."));
        assertEquals("Water boils at 100 degrees Celsius and moves at 60 kilometers per hour.", say("Water boils at 100\u00B0C and moves at 60 km/h."));
        assertEquals("It reaches 7.8 kilometers per second.", say("It reaches 7.8km/s."));
        assertEquals("Mix one half cup sugar with three quarters cup flour, about 250 grams.", say("Mix 1/2 cup sugar with 3/4 cup flour (about 250g)."));
    }

    @Test void acronymsAreSpelledOnlyWhenTheyAreAcronyms() {
        assertEquals("Never trust shared state, right?", say("NEVER trust shared state, right?"));
        assertTrue(say("Meet the GPU and a REST API over HTTPS.").contains("G P U"));
        assertTrue(say("Meet the GPU and a REST API over HTTPS.").contains("rest A P I"));
        assertTrue(say("It returns JSON.").contains("jay son"));
    }

    @Test void urlsKeepOnlyTheHostAndEmojiAreDropped() {
        assertEquals("A request to api dot example dot com returns jay son in about 120 milliseconds.",
                say("A request to https://api.example.com/v1/users returns JSON in about 120ms."));
        assertEquals("Great job you did it!", say("Great job \uD83C\uDF89\uD83D\uDE42 you did it!"));
    }

    @Test void engineCuesSurviveAndEveryLineIsTerminated() {
        assertTrue(say("That was funny [chuckle] right?").contains("[chuckle]"));
        assertEquals("No full stop here.", say("No full stop here"));
    }

    @Test void otherLanguagesGetOnlyTheSafeCleanUp() {
        String out = SpokenText.forSpeech("Load balancer (API) **setup**", "Hindi");
        assertTrue(out.contains("API"));
        assertFalse(out.contains("A P I"));
        assertFalse(out.contains("*"));
    }

    // ---------------------------------------------------------------- SpeechHumanizer
    @Test void contractionsAndPlainConnectors() {
        assertEquals("Also, it's important to note that we don't use threads in this program.",
                SpeechHumanizer.humanize("Furthermore, it is important to note that we do not utilize threads in this program."));
        assertEquals("It's working, and you're going to love how simple it is.",
                SpeechHumanizer.humanize("It is working, and you are going to love how simple it is."));
    }

    @Test void humanizerLeavesWrongOrEmphasisCasesAlone() {
        assertEquals("Yes, it is.", SpeechHumanizer.humanize("Yes, it is."));
        assertEquals("Please do NOT touch the shared counter.", SpeechHumanizer.humanize("Please do NOT touch the shared counter."));
        assertEquals("Yeh ek load balancer hai aur do not hota", SpeechHumanizer.humanize("Yeh ek load balancer hai aur do not hota"));
    }

    @Test void humanizerIsIdempotent() {
        String once = SpeechHumanizer.humanize("However, we do not utilize numerous locks and it is fine.");
        assertEquals(once, SpeechHumanizer.humanize(once));
    }

    // ---------------------------------------------------------------- SpeechPacing
    @Test void shortLinesLandSlowerAndQuestionsLift() {
        var shortLine = SpeechPacing.plan("Each reads it.", 1, 6);
        assertTrue(shortLine.speedMul() < 1.0);
        assertTrue(shortLine.gapAdd() > 0.1);
        var question = SpeechPacing.plan("Sounds harmless, right?", 4, 6);
        assertTrue(question.pitchMul() > 1.0);
        assertTrue(question.gapAdd() >= 0.2);
    }

    @Test void lastLineSettlesAndBoundsHold() {
        var last = SpeechPacing.plan("So always lock shared state.", 5, 6);
        assertTrue(last.speedMul() < 1.0);
        assertTrue(last.pitchMul() < 1.0);
        for (String s : new String[] {"Here's the thing:", "Wow!", "Why?", "A very long sentence that keeps going and going with many words to say in a single breath so the flow rule applies here today."}) {
            var p = SpeechPacing.plan(s, 2, 6);
            assertTrue(p.speedMul() >= 0.93 && p.speedMul() <= 1.05, s);
            assertTrue(p.pitchMul() >= 0.99 && p.pitchMul() <= 1.015, s);
            assertTrue(p.gapAdd() >= 0 && p.gapAdd() <= 0.45, s);
        }
    }

    @Test void pacingIsDeterministicAndScalable() {
        assertEquals(SpeechPacing.plan("Not quite.", 2, 5), SpeechPacing.plan("Not quite.", 2, 5));
        var flat = SpeechPacing.plan("Not quite.", 2, 5).scaled(0);
        assertEquals(1.0, flat.speedMul(), 1e-9);
        assertEquals(0.0, flat.gapAdd(), 1e-9);
    }
}
