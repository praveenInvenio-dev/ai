package com.aistorystudio.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local Kokoro (Apache-2.0, 82M parameters) sidecar adapter: a small, fast, clear English voice that runs on CPU and needs no reference clip.
 * Optional: it only runs when the {@code kokoro} compose profile is on and a voice is picked as {@code kokoro:<id>} (for example {@code kokoro:am_michael}).
 */
@Component
public class KokoroTTSProvider implements TextToSpeechProvider {
    private static final Logger log = LoggerFactory.getLogger(KokoroTTSProvider.class);
    private final WebClient webClient;
    private final String defaultVoice;

    public KokoroTTSProvider(
            WebClient.Builder builder,
            @Value("${studio.tts.kokoro.baseUrl:http://tts-kokoro:5006}") String baseUrl,
            @Value("${studio.tts.kokoro.voice:am_michael}") String defaultVoice) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.defaultVoice = defaultVoice;
    }

    @Override
    @Retryable(maxAttempts = 2, backoff = @Backoff(delay = 700, multiplier = 2))
    public TtsResult synthesize(TtsRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", request.text());
        body.put("voice", request.voice() == null || request.voice().isBlank() ? defaultVoice : request.voice());
        body.put("language", request.language());
        body.put("speed", request.speed() > 0 ? request.speed() : 1.0);
        byte[] wav = webClient.post().uri("/api/tts").bodyValue(body).retrieve().bodyToMono(byte[].class).block(Duration.ofSeconds(120));
        if (wav == null || wav.length == 0) throw new IllegalStateException("Kokoro returned no audio");
        return new TtsResult(wav, duration(wav), "wav");
    }

    private double duration(byte[] wav) {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            return ais.getFrameLength() / (double) ais.getFormat().getFrameRate();
        } catch (Exception e) {
            log.warn("Could not probe Kokoro audio duration: {}", e.getMessage());
            return -1;
        }
    }

    @Override
    public boolean healthCheck() {
        try {
            webClient.get().uri("/health").retrieve().toBodilessEntity().block(Duration.ofSeconds(5));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String providerName() { return "kokoro:" + defaultVoice; }
}
