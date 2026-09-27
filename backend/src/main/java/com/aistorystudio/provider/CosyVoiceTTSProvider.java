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
 * Local CosyVoice 3 sidecar adapter. The sidecar owns the heavy Python/model
 * dependency; the Java application only knows the provider-neutral TTS contract.
 * This keeps CosyVoice optional and prevents it from affecting existing Piper or
 * ChatterBox installations.
 */
@Component
public class CosyVoiceTTSProvider implements TextToSpeechProvider {
    private static final Logger log = LoggerFactory.getLogger(CosyVoiceTTSProvider.class);
    private final WebClient webClient;
    private final String defaultVoice;

    public CosyVoiceTTSProvider(
            WebClient.Builder builder,
            @Value("${studio.tts.cosyvoice.baseUrl:http://tts-cosyvoice:5005}") String baseUrl,
            @Value("${studio.tts.cosyvoice.voice:default}") String defaultVoice) {
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
        body.put("pitch", request.pitch() > 0 ? request.pitch() : 1.0);
        if (request.referenceTranscript() != null) body.put("referenceTranscript", request.referenceTranscript());
        if (request.emotion() != null) body.put("emotion", request.emotion());
        if (request.emotionIntensity() != null) body.put("emotionIntensity", request.emotionIntensity());
        if (request.delivery() != null) body.put("delivery", request.delivery());
        if (request.emphasis() != null && !request.emphasis().isEmpty()) body.put("emphasis", request.emphasis());
        if (Boolean.TRUE.equals(request.breath())) body.put("breath", true);
        if (request.actingDirection() != null) body.put("actingDirection", request.actingDirection());

        byte[] wav = webClient.post().uri("/api/tts").bodyValue(body).retrieve()
                .bodyToMono(byte[].class).block(Duration.ofSeconds(180));
        if (wav == null || wav.length == 0) {
            throw new IllegalStateException("CosyVoice returned no audio");
        }
        return new TtsResult(wav, duration(wav), "wav");
    }

    private double duration(byte[] wav) {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            return ais.getFrameLength() / (double) ais.getFormat().getFrameRate();
        } catch (Exception e) {
            log.warn("Could not probe CosyVoice audio duration: {}", e.getMessage());
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
    public String providerName() { return "cosyvoice:" + defaultVoice; }
}
