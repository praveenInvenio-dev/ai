package com.aistorystudio.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aistorystudio.service.VoiceProfileService;
import com.aistorystudio.provider.TextToSpeechProvider;
import com.aistorystudio.repository.VoiceProfileRepository;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Voice library and narration preview.
 *
 * The browser cannot call the TTS container directly - it is on the compose
 * network, not the host - so these endpoints proxy to it. Preview returns raw
 * WAV bytes so the frontend can hand the response straight to an &lt;audio&gt;
 * element without any decoding step.
 */
@RestController
@RequestMapping("/api/tts")
@CrossOrigin
public class TtsController {

    /** Long enough for a first-use voice download (~110 MB) plus synthesis. */
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(6);
    private static final Duration SPEAK_TIMEOUT = Duration.ofMinutes(3);

    /** Preview text is capped so the page cannot be used to synthesise a novel. */
    private static final int MAX_PREVIEW_CHARS = 600;

    private final WebClient tts;
    private final VoiceProfileRepository voiceProfiles;
    private final VoiceProfileService voiceProfileService;

    public TtsController(WebClient.Builder builder,
                         @Value("${studio.tts.baseUrl}") String baseUrl,
                         VoiceProfileRepository voiceProfiles,
                         VoiceProfileService voiceProfileService) {
        this.tts = builder.baseUrl(baseUrl).build();
        this.voiceProfiles = voiceProfiles;
        this.voiceProfileService = voiceProfileService;
    }

    @GetMapping("/voices")
    public ResponseEntity<JsonNode> voices() {
        JsonNode body = tts.get().uri("/api/voices")
                .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(20));
        if (body != null && body.isObject()) {
            ObjectNode root = (ObjectNode) body;
            ArrayNode list = root.withArray("voices");
            voiceProfiles.findAllByOrderByNameAsc().forEach(profile -> {
                ObjectNode v = list.addObject();
                v.put("id", "profile:" + profile.getId());
                v.put("label", profile.getName());
                v.put("accent", profile.getLanguage() == null ? "custom" : profile.getLanguage());
                v.put("gender", "custom");
                v.put("quality", "cloned");
                v.put("notes", profile.getPersonality() == null ? "Saved Voice Library profile" : profile.getPersonality());
                v.put("installed", true);
                v.put("engine", profile.getProvider());
                v.put("isDefault", false);
            });
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/voices/{voice}")
    public ResponseEntity<JsonNode> install(@PathVariable String voice) {
        JsonNode body = tts.post().uri("/api/voices/{voice}", voice)
                .retrieve().bodyToMono(JsonNode.class).block(INSTALL_TIMEOUT);
        return ResponseEntity.ok(body);
    }

    public record PreviewRequest(String text, String voice, Double speed, Double pitch) {}

    @PostMapping(value = "/preview", produces = "audio/wav")
    public ResponseEntity<byte[]> preview(@RequestBody PreviewRequest request) {
        String text = request.text() == null ? "" : request.text().trim();
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (text.length() > MAX_PREVIEW_CHARS) {
            text = text.substring(0, MAX_PREVIEW_CHARS);
        }

        String voice = request.voice() == null ? "" : request.voice();
        if (voice.startsWith("profile:")) {
            try {
                java.util.UUID id = java.util.UUID.fromString(voice.substring("profile:".length()));
                TextToSpeechProvider.TtsResult result = voiceProfileService.generateTest(id, text,
                        request.speed() == null || request.speed() <= 0 ? 1.0 : request.speed(),
                        request.pitch() == null || request.pitch() <= 0 ? 1.0 : request.pitch());
                return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=voice-profile-preview.wav")
                        .body(result.audioBytes());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }

        byte[] wav = tts.post().uri("/api/tts")
                .bodyValue(Map.of(
                        "text", text,
                        "voice", voice,
                        "speed", request.speed() == null || request.speed() <= 0 ? 1.0 : request.speed(),
                        // Pitch is a post-process (ffmpeg), not a Piper setting - it is how
                        // child voices are made without also speeding the narration up.
                        "pitch", request.pitch() == null || request.pitch() <= 0 ? 1.0 : request.pitch()))
                .retrieve().bodyToMono(byte[].class).block(SPEAK_TIMEOUT);

        if (wav == null || wav.length == 0) {
            return ResponseEntity.status(502).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                // inline so the browser's <audio> element plays it rather than
                // triggering a download prompt.
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.wav\"")
                .body(wav);
    }
}
