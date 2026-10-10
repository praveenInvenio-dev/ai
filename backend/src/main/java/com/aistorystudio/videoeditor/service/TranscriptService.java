package com.aistorystudio.videoeditor.service;

import com.aistorystudio.videoeditor.domain.VideoClip;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Word-level transcripts of project clips (faster-whisper in video-worker), cached next to the upload
 * so speech cleanup, highlights and later edits never transcribe the same clip twice.
 */
@Service
public class TranscriptService {

    /** Biases whisper to keep "um/uh" (it normally tidies them away, which would hide them from cleanup). */
    static final String VERBATIM_PROMPT = "Umm, uh, so, like, you know, hmm... okay, here is, uh, what I mean.";

    private final VideoEditorStorageService storage;
    private final String workerBaseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public TranscriptService(VideoEditorStorageService storage,
                             @Value("${studio.video-editor.worker-base-url:http://video-worker:5010}") String workerBaseUrl) {
        this.storage = storage;
        this.workerBaseUrl = workerBaseUrl;
    }

    /** The cached transcript if this clip was already transcribed (never starts a transcription). */
    public java.util.Optional<JsonNode> cached(UUID projectId, VideoClip clip) {
        try {
            Path src = storage.resolveWithin(storage.uploadsDir(projectId), clip.getStoredFilename());
            Path cache = src.resolveSibling(clip.getStoredFilename() + ".transcript.json");
            if (Files.isRegularFile(cache) && Files.size(cache) > 2) {
                return java.util.Optional.of(mapper.readTree(Files.readString(cache, StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) { }
        return java.util.Optional.empty();
    }

    /** {language, words:[{word,start,end}], segments:[{text,start,end}]} */
    public JsonNode get(UUID projectId, VideoClip clip) throws Exception {
        Path src = storage.resolveWithin(storage.uploadsDir(projectId), clip.getStoredFilename());
        Path cache = src.resolveSibling(clip.getStoredFilename() + ".transcript.json");
        if (Files.isRegularFile(cache) && Files.size(cache) > 2) {
            return mapper.readTree(Files.readString(cache, StandardCharsets.UTF_8));
        }
        var body = mapper.createObjectNode().put("source", src.toAbsolutePath().toString());
        body.put("prompt", VERBATIM_PROMPT);
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(workerBaseUrl + "/api/transcribe"))
                .timeout(Duration.ofMinutes(90)).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var resp = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        JsonNode n = mapper.readTree(resp.body());
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("Transcription failed for \"" + clip.getDisplayName() + "\": " + n.path("error").asText(resp.body()));
        }
        Files.writeString(cache, resp.body(), StandardCharsets.UTF_8);
        return n;
    }
}
