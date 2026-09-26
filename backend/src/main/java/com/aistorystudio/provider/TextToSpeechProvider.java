package com.aistorystudio.provider;

public interface TextToSpeechProvider {

    TtsResult synthesize(TtsRequest request);

    boolean healthCheck();

    String providerName();

    record TtsRequest(String text, String voice, String language, double speed, double pitch) {}

    record TtsResult(byte[] audioBytes, double durationSeconds, String format) {}
}
