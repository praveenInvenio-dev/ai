package com.aistorystudio.h3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class IndicEngineVoicesTest {

    private static IndicSpeech.Language lang(String declared) {
        return IndicSpeech.resolve(declared, "");
    }

    @Test
    void indicSpeakOffersAFemaleAndAMaleVoiceForEveryProductionIndianLanguage() {
        for (String l : List.of("Hindi", "Kannada", "Tamil", "Telugu", "Malayalam", "Marathi", "Bengali", "Gujarati", "Punjabi", "Odia")) {
            List<String> v = IndicSpeech.indicSpeakVoices(lang(l));
            assertEquals(2, v.size(), l);
            assertTrue(v.stream().allMatch(x -> x.startsWith("speak:")), l);
        }
    }

    @Test
    void englishIsSpokenByIndicSpeakButNotByIndicF5() {
        assertFalse(IndicSpeech.indicSpeakVoices(lang("English")).isEmpty());
        assertTrue(IndicSpeech.indicVoices(lang("English"), "indicspeak").get(0).startsWith("speak:en-"));
    }

    @Test
    void theEngineSettingSelectsWhichVoicesAreUsed() {
        assertTrue(IndicSpeech.indicVoices(lang("Kannada"), "indicspeak").get(0).startsWith("speak:kn-"));
        assertTrue(IndicSpeech.indicVoices(lang("Kannada"), "indicf5").get(0).startsWith("indic:kn-"));
        assertTrue(IndicSpeech.indicVoices(lang("Kannada"), null).get(0).startsWith("indic:kn-"), "default stays IndicF5");
        assertTrue(IndicSpeech.indicVoices(lang("Kannada"), "  INDICSPEAK ").get(0).startsWith("speak:"), "case and spaces are tolerated");
    }

    @Test
    void voiceIdsAreRecognisedAsIndicEngineVoices() {
        assertTrue(IndicSpeech.isIndicEngineVoice("indic:kn-in-sapna"));
        assertTrue(IndicSpeech.isIndicEngineVoice("speak:kn-Deepika"));
        assertFalse(IndicSpeech.isIndicEngineVoice("edge:kn-IN-SapnaNeural"));
        assertFalse(IndicSpeech.isIndicEngineVoice("narrator-male"));
        assertFalse(IndicSpeech.isIndicEngineVoice(null));
    }

    @Test
    void autoVoiceNeverGuessesForEnglishOrUnknownLanguages() {
        assertEquals("speak:kn-Deepika", IndicSpeech.autoVoice("Kannada", "indicspeak"));
        assertEquals("indic:kn-in-sapna", IndicSpeech.autoVoice("kn-IN", "indicf5"));
        assertEquals("speak:hi-Kavya", IndicSpeech.autoVoice("Hinglish", "indicspeak"));
        assertEquals(null, IndicSpeech.autoVoice("English", "indicspeak"));
        assertEquals(null, IndicSpeech.autoVoice("Santali", "indicspeak"), "unknown language resolves to English: no voice");
        assertEquals(null, IndicSpeech.autoVoice("", "indicspeak"));
        assertEquals(null, IndicSpeech.autoVoice(null, "indicspeak"));
    }

    @Test
    void nullLanguageHasNoVoices() {
        assertTrue(IndicSpeech.indicSpeakVoices(null).isEmpty());
    }
}
