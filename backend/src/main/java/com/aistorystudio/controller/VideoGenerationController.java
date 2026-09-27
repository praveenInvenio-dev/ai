package com.aistorystudio.controller;

import com.aistorystudio.videogen.VideoGenJob;
import com.aistorystudio.videogen.VideoGenerationService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Standalone "Video generation" page (nav: AI video editor's sibling, not
 * part of the story pipeline): animate one uploaded image from a text
 * prompt, independent of any project/episode/scene. Same underlying
 * Wan/ComfyUI provider the per-scene pipeline uses (see ProviderGateway),
 * exposed directly for trying prompts/settings or one-off clips.
 */
@RestController
@RequestMapping("/api/video-generation")
public class VideoGenerationController {

    private final VideoGenerationService service;

    public VideoGenerationController(VideoGenerationService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public VideoGenerationService.StatusView status() {
        return service.status();
    }

    @PostMapping("/jobs")
    public ResponseEntity<Map<String, UUID>> createJob(
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "negativePrompt", required = false) String negativePrompt,
            @RequestParam(value = "durationSeconds", required = false, defaultValue = "4.0") double durationSeconds,
            @RequestParam(value = "seed", required = false) Long seed) {
        VideoGenJob job = service.createJob(image, prompt);
        // Fired from this controller bean (not a self-invocation inside the
        // service), which is what lets @Async actually intercept the call -
        // see the note on generateAsync().
        service.generateAsync(job.getId(), prompt, negativePrompt, durationSeconds, seed);
        return ResponseEntity.accepted().body(Map.of("jobId", job.getId()));
    }

    @GetMapping("/jobs/{jobId}")
    public VideoGenerationService.JobView jobStatus(@PathVariable UUID jobId) {
        return service.getStatus(jobId);
    }

    @GetMapping("/jobs/{jobId}/video")
    public ResponseEntity<FileSystemResource> jobVideo(@PathVariable UUID jobId) {
        Path path = service.getResultPath(jobId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(new FileSystemResource(path));
    }
}
