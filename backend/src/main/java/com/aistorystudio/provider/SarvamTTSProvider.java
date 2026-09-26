package com.aistorystudio.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sarvam AI (Bulbul) text-to-speech.
 *
 * This exists because Piper - the default, fully-offline engine - has no
 * Kannada voice, no Indian-accented English, and no child voices in any
 * language. Sarvam covers all three. The trade is that it is a hosted API:
 * it costs money per character and it breaks the project's offline-only
 * property, so it is opt-in via studio.tts.provider=sarvam and never the
 * default.
 *
 * Two behaviours worth knowing about:
 *
 * <ul>
 *   <li><b>Responses are base64 JSON, not audio bytes.</b> Sarvam returns
 *       {@code {"audios": ["<base64>", ...]}} rather than a binary body, so the
 *       chunks are decoded and concatenated here to match the
 *       "bytes in, bytes out" contract the rest of the pipeline expects.</li>
 *   <li><b>Long narration is split.</b> Bulbul v3 caps a request at 2500
 *       characters and returns 422 above it. Narration for a long scene can
 *       exceed that, so text is chunked on sentence boundaries and the resulting
 *       WAVs are joined - splitting mid-sentence would put an audible seam in
 *       the middle of a line.</li>
 * </ul>
 */
@Component
public class SarvamTTSProvider implements TextToSpeechProvider {

    private static final Logger log = LoggerFactory.getLogger(SarvamTTSProvider.class);

    /** Bulbul v3's documented per-request ceiling. Kept slightly under it. */
    private static final int MAX_CHARS_PER_REQUEST = 2400;

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final String defaultSpeaker;
    private final String defaultLanguage;

    public SarvamTTSProvider(
            WebClient.Builder builder,
            @Value("${studio.tts.sarvam.baseUrl:https://api.sarvam.ai}") String baseUrl,
            @Value("${studio.tts.sarvam.apiKey:}") String apiKey,
            @Value("${studio.tts.sarvam.model:bulbul:v3}") String model,
            @Value("${studio.tts.sarvam.speaker:anushka}") String defaultSpeaker,
            @Value("${studio.tts.sarvam.language:en-IN}") String defaultLanguage) {
        this.webClient = builder.baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
        this.apiKey = apiKey;
        this.model = model;
        this.defaultSpeaker = defaultSpeaker;
        this.defaultLanguage = defaultLanguage;
    }

    @Override
    public TtsResult synthesize(TtsRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "SARVAM_API_KEY is not set. Get a key from https://dashboard.sarvam.ai "
                            + "or switch back to the offline engine with TTS_PROVIDER=piper.");
        }

        String speaker = request.voice() != null && !request.voice().isBlank()
                ? request.voice() : defaultSpeaker;
        String language = request.language() != null && !request.language().isBlank()
                ? request.language() : defaultLanguage;

        List<byte[]> parts = new ArrayList<>();
        for (String chunk : splitForRequestLimit(request.text())) {
            parts.add(synthesizeChunk(chunk, speaker, language, request.speed(), request.pitch()));
        }

        byte[] wav = parts.size() == 1 ? parts.get(0) : concatWav(parts);
        return new TtsResult(wav, probeDurationSeconds(wav), "wav");
    }

    private byte[] synthesizeChunk(String text, String speaker, String language,
                                    double speed, double pitch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("target_language_code", language);
        body.put("speaker", speaker);
        body.put("model", model);
        body.put("pace", speed > 0 ? speed : 1.0);
        // Sarvam's pitch is an offset around 0, not a multiplier like the local
        // engine's ffmpeg shift - so 1.0 ("unchanged" locally) maps to 0 here.
        body.put("pitch", pitch > 0 ? pitch - 1.0 : 0.0);
        body.put("output_audio_codec", "wav");

        JsonNode response;
        try {
            response = webClient.post()
                    .uri("/text-to-speech")
                    .header("api-subscription-key", apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(120));
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            // 403 means the key was rejected; 422 usually means the speaker name
            // is not valid for this language. Both are worth surfacing verbatim
            // rather than as a bare status code.
            throw new IllegalStateException("Sarvam TTS rejected the request ("
                    + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
        }

        if (response == null || !response.path("audios").isArray() || response.path("audios").isEmpty()) {
            throw new IllegalStateException("Sarvam TTS returned no audio: " + response);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (JsonNode audio : response.path("audios")) {
            out.writeBytes(Base64.getDecoder().decode(audio.asText()));
        }
        return out.toByteArray();
    }

    /**
     * Splits text into request-sized chunks on sentence boundaries. A hard cut at
     * 2400 characters would land mid-word and produce an audible break; sentence
     * ends are where a natural pause already exists.
     */
    static List<String> splitForRequestLimit(String text) {
        List<String> chunks = new ArrayList<>();
        String remaining = text == null ? "" : text.trim();
        if (remaining.isEmpty()) {
            return List.of(" ");
        }
        while (remaining.length() > MAX_CHARS_PER_REQUEST) {
            String window = remaining.substring(0, MAX_CHARS_PER_REQUEST);
            int cut = Math.max(window.lastIndexOf(". "),
                      Math.max(window.lastIndexOf("? "), window.lastIndexOf("! ")));
            if (cut < MAX_CHARS_PER_REQUEST / 2) {
                // No sentence end in a sensible place - fall back to a word break
                // rather than slicing through a word.
                cut = window.lastIndexOf(' ');
            }
            if (cut <= 0) {
                cut = MAX_CHARS_PER_REQUEST - 1;
            }
            chunks.add(remaining.substring(0, cut + 1).trim());
            remaining = remaining.substring(cut + 1).trim();
        }
        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }
        return chunks;
    }

    /** Joins WAV chunks into one stream using the first chunk's audio format. */
    private byte[] concatWav(List<byte[]> parts) {
        try {
            AudioInputStream combined = AudioSystem.getAudioInputStream(new ByteArrayInputStream(parts.get(0)));
            for (int i = 1; i < parts.size(); i++) {
                AudioInputStream next = AudioSystem.getAudioInputStream(new ByteArrayInputStream(parts.get(i)));
                combined = new AudioInputStream(
                        new java.io.SequenceInputStream(combined, next),
                        combined.getFormat(),
                        combined.getFrameLength() + next.getFrameLength());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(combined, javax.sound.sampled.AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("Could not join {} audio chunks, returning the first only: {}",
                    parts.size(), e.getMessage());
            return parts.get(0);
        }
    }

    private double probeDurationSeconds(byte[] wav) {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            return ais.getFrameLength() / (double) ais.getFormat().getFrameRate();
        } catch (Exception e) {
            log.warn("Could not probe Sarvam audio duration: {}", e.getMessage());
            return -1;
        }
    }

    @Override
    public boolean healthCheck() {
        // No unauthenticated ping endpoint, and a real call costs money, so the
        // only free signal is whether a key is configured at all.
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String providerName() {
        return "sarvam";
    }
}
