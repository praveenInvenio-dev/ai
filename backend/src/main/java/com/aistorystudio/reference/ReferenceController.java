package com.aistorystudio.reference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * "Make mine feel like this one": upload a reference video, get what was measured (cut pace, loudness, silence, colour, shape) and the
 * editor / lesson settings it suggests. The upload is analysed from a temporary file and deleted at once; nothing is stored or sent anywhere.
 */
@RestController
@RequestMapping("/api/reference")
public class ReferenceController {

    private final String ffmpeg;
    private final String ffprobe;
    private final Semaphore slots = new Semaphore(2);     // each analysis keeps several FFmpeg processes busy

    public ReferenceController(@Value("${studio.ffmpeg.binary:ffmpeg}") String ffmpeg) {
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffmpeg.endsWith("ffmpeg") ? ffmpeg.substring(0, ffmpeg.length() - "ffmpeg".length()) + "ffprobe" : "ffprobe";
    }

    @PostMapping("/analyse")
    public ResponseEntity<?> analyse(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Choose a video file first."));
        if (!slots.tryAcquire()) return ResponseEntity.status(429).body(Map.of("error", "Two reference videos are being analysed already - try again in a minute."));
        Path tmp = null;
        try {
            tmp = Files.createTempFile("reference-", extensionOf(file.getOriginalFilename()));
            file.transferTo(tmp);
            ReferenceAnalyzer.Profile profile = ReferenceAnalyzer.analyse(ffmpeg, ffprobe, tmp);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("profile", profile);
            body.put("suggestions", ReferenceAnalyzer.suggest(profile));
            return ResponseEntity.ok(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).body(Map.of("error", "The analysis was interrupted."));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "That file could not be analysed." : e.getMessage()));
        } finally {
            slots.release();
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    /** ".mp4" from the original name when it is plain letters / digits, otherwise ".bin" (FFmpeg sniffs the real format anyway). */
    static String extensionOf(String name) {
        int i = name == null ? -1 : name.lastIndexOf('.');
        String ext = i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,5}") ? "." + ext : ".bin";
    }
}
