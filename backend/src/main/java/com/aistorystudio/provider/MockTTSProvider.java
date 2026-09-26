package com.aistorystudio.provider;

import org.springframework.stereotype.Component;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Generates silent WAV files sized to the estimated narration duration
 * (word count / average speaking rate). Used in DEMO_MODE and as a TTS fallback
 * so the full timing/video-assembly pipeline is exercisable without a real voice engine.
 */
@Component
public class MockTTSProvider implements TextToSpeechProvider {

    private static final double WORDS_PER_MINUTE = 150.0;
    private static final float SAMPLE_RATE = 22050f;

    @Override
    public TtsResult synthesize(TtsRequest request) {
        double seconds = estimateSeconds(request.text());
        byte[] wav = buildSilentWav(seconds);
        return new TtsResult(wav, seconds, "wav");
    }

    static double estimateSeconds(String text) {
        if (text == null || text.isBlank()) return 1.0;
        int words = text.trim().split("\\s+").length;
        double seconds = (words / WORDS_PER_MINUTE) * 60.0;
        return Math.max(1.0, seconds);
    }

    private byte[] buildSilentWav(double seconds) {
        int numFrames = (int) Math.ceil(seconds * SAMPLE_RATE);
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        byte[] data = new byte[numFrames * 2];
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), format, numFrames)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to synthesize mock silent audio", e);
        }
    }

    @Override
    public boolean healthCheck() {
        return true;
    }

    @Override
    public String providerName() {
        return "mock-tts-provider";
    }
}
