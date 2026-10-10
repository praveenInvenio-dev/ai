package com.aistorystudio.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Map;

/**
 * Generic HTTP client for a local/self-hosted TTS engine (e.g. Piper's HTTP wrapper,
 * Coqui TTS server, or any engine exposing a simple "text in -> wav out" endpoint).
 * The engine itself is swappable via studio.tts.provider / studio.tts.baseUrl.
 */
@Component
public class LocalTTSProvider implements TextToSpeechProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalTTSProvider.class);

    private final WebClient webClient;
    private final String defaultVoice;

    public LocalTTSProvider(
            org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder,
            @Value("${studio.tts.baseUrl}") String baseUrl,
            @Value("${studio.tts.voice}") String defaultVoice) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.defaultVoice = defaultVoice;
    }

    @Override
    @Retryable(maxAttempts = 2, backoff = @Backoff(delay = 500, multiplier = 2))
    public TtsResult synthesize(TtsRequest request) {
        String voice = request.voice() != null ? request.voice() : defaultVoice;

        // IndicF5 (flow matching) is far slower than Piper/Edge, and its first call may still be
        // loading the model; 60 s made every slow Indic line look like "synthesis failed".
        Duration timeout = voice != null && com.aistorystudio.h3.IndicSpeech.isIndicEngineVoice(voice) ? Duration.ofMinutes(10) : Duration.ofSeconds(90);
        byte[] wav;
        try {
            wav = webClient.post()
                .uri("/api/tts")
                .bodyValue(Map.of(
                        "text", request.text(),
                        "voice", voice,
                        "speed", request.speed() > 0 ? request.speed() : 1.0,
                        "language", request.language() == null ? "en" : request.language(),
                        "pitch", request.pitch() > 0 ? request.pitch() : 1.0
                ))
                .retrieve()
                .bodyToMono(byte[].class)
                .block(timeout);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            // keep the sidecar's own reason (e.g. "IndicF5: HF_TOKEN is not set ...")
            throw new IllegalStateException("TTS " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
        }

        if (wav == null || wav.length == 0) {
            throw new IllegalStateException("Local TTS engine returned no audio");
        }
        double duration = probeDurationSeconds(wav);
        return new TtsResult(wav, duration, "wav");
    }

    private double probeDurationSeconds(byte[] wav) {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            long frames = ais.getFrameLength();
            float frameRate = ais.getFormat().getFrameRate();
            return frames / (double) frameRate;
        } catch (Exception e) {
            log.warn("Could not probe TTS audio duration: {}", e.getMessage());
            return -1;
        }
    }

    @Override
    public boolean healthCheck() {
        try {
            webClient.get().uri("/health").retrieve().toBodilessEntity().block(Duration.ofSeconds(5));
            return true;
        } catch (Exception e) {
            log.warn("Local TTS health check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String providerName() {
        return "local-tts:" + defaultVoice;
    }
}
