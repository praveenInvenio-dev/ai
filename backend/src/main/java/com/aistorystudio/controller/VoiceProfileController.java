package com.aistorystudio.controller;

import com.aistorystudio.domain.VoiceProfile;
import com.aistorystudio.provider.TextToSpeechProvider;
import com.aistorystudio.service.VoiceProfileService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Voice Library (Phase 1 of the platform's Voice Director build-out - see
 * project chat history for Phases 2-4). Built-in Piper voices and cloned
 * ChatterBox voices both surface here as the same VoiceProfile shape, so a
 * story's voice picker doesn't need to know which kind it's looking at.
 */
@RestController
@RequestMapping("/api/voice-profiles")
public class VoiceProfileController {

    private final VoiceProfileService service;

    public VoiceProfileController(VoiceProfileService service) {
        this.service = service;
    }

    @GetMapping
    public List<VoiceProfile> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public VoiceProfile get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** "Built-in Voices": names an existing engine voice (a Piper voice id
     *  today), no audio upload involved. */
    @PostMapping("/built-in")
    public VoiceProfile createBuiltIn(@RequestBody Map<String, String> body) {
        return service.createBuiltIn(
                body.get("name"), body.get("language"), body.get("voiceName"), body.get("personality"));
    }

    /** "Record Your Voice" / "Upload Voice Sample": both land here as a
     *  multipart file - a recorded clip from the browser's MediaRecorder and
     *  an uploaded .wav/.mp3 arrive as the same kind of multipart part. */
    @PostMapping(value = "/cloned", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VoiceProfile createCloned(
            @RequestParam("name") String name,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "provider", defaultValue = "chatterbox") String provider,
            @RequestParam(value = "personality", required = false) String personality,
            @RequestParam("audio") MultipartFile audio) {
        return service.createCloned(name, language, provider, personality, audio);
    }

    /** Validate-before-save: lets the "Record Your Voice" flow show duration/
     *  silence/clipping warnings at the Preview step, before the user
     *  commits the clip as a saved VoiceProfile. */
    @PostMapping(value = "/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VoiceProfileService.ValidationResult validate(@RequestParam("audio") MultipartFile audio) {
        return service.validateOnly(audio);
    }

    @PutMapping("/{id}/rename")
    public VoiceProfile rename(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return service.rename(id, body.get("name"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** "Test Voice": generates a short real sample through this profile's
     *  actual provider - the same code path a real episode narration line
     *  would use, not a separate preview mechanism. */
    @PostMapping(value = "/{id}/test", produces = "audio/wav")
    public ResponseEntity<byte[]> test(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        String sampleText = body != null ? body.get("text") : null;
        TextToSpeechProvider.TtsResult result = service.generateTest(id, sampleText);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .body(result.audioBytes());
    }
}
