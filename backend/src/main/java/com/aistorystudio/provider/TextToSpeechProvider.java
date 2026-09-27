package com.aistorystudio.provider;

import java.util.List;

public interface TextToSpeechProvider {

    TtsResult synthesize(TtsRequest request);

    boolean healthCheck();

    String providerName();

    /**
     * Rich, provider-neutral speech direction. The 5-argument constructor is
     * intentionally retained so every existing caller remains source-compatible.
     */
    record TtsRequest(
            String text, String voice, String language, double speed, double pitch,
            String emotion, Double emotionIntensity, String delivery,
            List<String> emphasis, Boolean breath, String paralinguisticEvent,
            String actingDirection, String referenceTranscript) {

        public TtsRequest(String text, String voice, String language, double speed, double pitch,
                          String emotion, Double emotionIntensity, String delivery, List<String> emphasis,
                          Boolean breath, String paralinguisticEvent, String actingDirection) {
            this(text, voice, language, speed, pitch, emotion, emotionIntensity, delivery, emphasis, breath, paralinguisticEvent, actingDirection, null);
        }

        public TtsRequest(String text, String voice, String language, double speed, double pitch) {
            this(text, voice, language, speed, pitch, null, null, null, List.of(), false, null, null, null);
        }
    }

    record TtsResult(byte[] audioBytes, double durationSeconds, String format,
                      /** Null normally. Set by ProviderGateway when the assigned
                       *  provider failed and this result actually came from the
                       *  mock fallback - surfaced up to the generation step so a
                       *  silent voice swap is visible in the UI, not just logs. */
                      String providerWarning) {
        public TtsResult(byte[] audioBytes, double durationSeconds, String format) {
            this(audioBytes, durationSeconds, format, null);
        }
    }
}
