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
import java.util.Map;

/**
 * ChatterBox Turbo (tts-chatterbox sidecar) - expressive English narration
 * with native paralinguistic tags ([laugh], [chuckle], etc.) and voice
 * cloning. Selected via TTS_PROVIDER=chatterbox in .env; not the default,
 * since it needs a reference clip per voice and is English-only (see
 * tts-chatterbox/voices/README.md).
 *
 * Deliberately its own class rather than a second instance of
 * LocalTTSProvider pointed at a different URL: this project already
 * dedicates one concrete class per engine (LocalTTSProvider for Piper,
 * SarvamTTSProvider for Sarvam) rather than one generic parametrised class,
 * and this follows that same shape rather than introducing a new pattern for
 * just one more engine.
 *
 * Text arrives and leaves this class exactly as ProductionPipelineService/
 * StoryEngineService wrote it - any [laugh]/[pause:...]-style tags are
 * whatever the narration prompt already put in the text, not something this
 * class inserts. See the sidecar's own server.py comment for why that
 * automatic-tag-insertion piece is deliberately out of scope for this pass.
 */
@Component
public class ChatterboxTTSProvider implements TextToSpeechProvider {

    private static final Logger log = LoggerFactory.getLogger(ChatterboxTTSProvider.class);

    private final WebClient webClient;
    private final String defaultVoice;

    public ChatterboxTTSProvider(
            WebClient.Builder webClientBuilder,
            @Value("${studio.tts.chatterbox.baseUrl:http://tts-chatterbox:5004}") String baseUrl,
            @Value("${studio.tts.chatterbox.voice:narrator}") String defaultVoice) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.defaultVoice = defaultVoice;
    }

    @Override
    @Retryable(maxAttempts = 2, backoff = @Backoff(delay = 500, multiplier = 2))
    public TtsResult synthesize(TtsRequest request) {
        String voice = request.voice() != null ? request.voice() : defaultVoice;

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("text", request.text());
        body.put("voice", voice);
        body.put("speed", request.speed() > 0 ? request.speed() : 1.0);
        body.put("pitch", request.pitch() > 0 ? request.pitch() : 1.0);
        if (request.emotion() != null) body.put("emotion", request.emotion());
        if (request.emotionIntensity() != null) body.put("emotionIntensity", request.emotionIntensity());
        if (request.delivery() != null) body.put("delivery", request.delivery());
        if (request.emphasis() != null && !request.emphasis().isEmpty()) body.put("emphasis", request.emphasis());
        if (Boolean.TRUE.equals(request.breath())) body.put("breath", true);
        if (request.paralinguisticEvent() != null && !request.paralinguisticEvent().isBlank()) body.put("paralinguisticEvent", request.paralinguisticEvent());
        if (request.actingDirection() != null && !request.actingDirection().isBlank()) body.put("actingDirection", request.actingDirection());

        byte[] wav = webClient.post()
                .uri("/api/tts")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(byte[].class)
                // Loading the model on first request (350M params) can take a
                // while on CPU - a longer timeout than LocalTTSProvider's 60s
                // is deliberate for exactly that cold-start case.
                .block(Duration.ofSeconds(300));

        if (wav == null || wav.length == 0) {
            throw new IllegalStateException("ChatterBox TTS returned no audio for voice '" + voice + "'");
        }
        return new TtsResult(wav, probeDurationSeconds(wav), "wav");
    }

    private double probeDurationSeconds(byte[] wav) {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            long frames = ais.getFrameLength();
            float frameRate = ais.getFormat().getFrameRate();
            return frames / (double) frameRate;
        } catch (Exception e) {
            log.warn("Could not probe ChatterBox audio duration: {}", e.getMessage());
            return -1;
        }
    }

    @Override
    public boolean healthCheck() {
        try {
            webClient.get().uri("/health").retrieve().toBodilessEntity().block(Duration.ofSeconds(5));
            return true;
        } catch (Exception e) {
            log.warn("ChatterBox TTS health check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String providerName() {
        return "chatterbox:" + defaultVoice;
    }
}
