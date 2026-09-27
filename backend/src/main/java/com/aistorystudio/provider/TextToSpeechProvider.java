package com.aistorystudio.provider;

public interface TextToSpeechProvider {

    TtsResult synthesize(TtsRequest request);

    boolean healthCheck();

    String providerName();

    record TtsRequest(String text, String voice, String language, double speed, double pitch) {}

    record TtsResult(byte[] audioBytes, double durationSeconds, String format,
                      /** Null in the normal case. Set by ProviderGateway when the
                       *  configured/assigned provider failed and this result actually
                       *  came from the mock fallback - "never silently replace the
                       *  user's selected voice without informing them", surfaced up
                       *  through the pipeline to the generation step (see
                       *  ProductionPipelineService) rather than just a backend log line. */
                      String providerWarning) {
        public TtsResult(byte[] audioBytes, double durationSeconds, String format) {
            this(audioBytes, durationSeconds, format, null);
        }
    }
}
