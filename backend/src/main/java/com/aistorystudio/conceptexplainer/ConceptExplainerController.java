package com.aistorystudio.conceptexplainer;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/concept-explainer")
public class ConceptExplainerController {

    private final ConceptExplainerService service;
    private final ConceptExplainerJobStore jobs;

    public ConceptExplainerController(ConceptExplainerService service, ConceptExplainerJobStore jobs) {
        this.service = service;
        this.jobs = jobs;
    }

    /** motion: REVEAL (elements appear with the narration, default) or STATIC. animationMode kept for old clients. */
    public record CreateRequest(String topic, String instructions, String language, String duration,
                                String difficulty, String motion, String animationMode, String model,
                                String track, String subject, Boolean examFocus) {}

    @PostMapping("/jobs")
    public ResponseEntity<Map<String, UUID>> create(@RequestBody CreateRequest r) {
        ConceptExplainerJob job = service.create(r.topic(), r.instructions(), r.language(), r.duration(), r.difficulty(),
                r.motion() != null ? r.motion() : r.animationMode(), r.model(), r.track(), r.subject(), Boolean.TRUE.equals(r.examFocus()));
        service.generateAsync(job.getId());
        return ResponseEntity.accepted().body(Map.of("jobId", job.getId()));
    }

    /** "My lessons": everything still in memory, newest first. */
    @GetMapping("/jobs")
    public List<JobSummary> list() {
        return jobs.list().stream().map(j -> new JobSummary(j.getId(), j.getTitle() == null ? j.getTopic() : j.getTitle(),
                j.getDuration(), j.getLanguage(), j.getStatus(), j.getTotalDurationSeconds(), j.getCreatedAt().toString())).toList();
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<JobView> status(@PathVariable UUID id) {
        ConceptExplainerJob job = jobs.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toView(job));
    }

    @PostMapping("/jobs/{id}/retry")
    public ResponseEntity<Map<String, UUID>> retry(@PathVariable UUID id) {
        ConceptExplainerJob job = service.retry(id);
        service.generateAsync(job.getId());
        return ResponseEntity.accepted().body(Map.of("jobId", job.getId()));
    }

    /** kind: image (new illustration / slide), audio (new narration) or both. */
    @PostMapping("/jobs/{id}/scenes/{scene}/regenerate")
    public ResponseEntity<Map<String, String>> regenerate(@PathVariable UUID id, @PathVariable int scene,
                                                          @RequestParam(defaultValue = "image") String kind) {
        String k = kind == null ? "image" : kind.toLowerCase(java.util.Locale.ROOT);
        service.regenerateScene(id, scene, k);
        service.regenerateSceneAsync(id, scene, k);
        return ResponseEntity.accepted().body(Map.of("status", "queued"));
    }

    @GetMapping("/jobs/{id}/scenes/{scene}/image")
    public ResponseEntity<FileSystemResource> image(@PathVariable UUID id, @PathVariable int scene) {
        ConceptExplainerJob.Scene s = scene(id, scene);
        return serve(s == null ? null : s.getImagePath(), MediaType.IMAGE_PNG);
    }

    @GetMapping("/jobs/{id}/scenes/{scene}/audio")
    public ResponseEntity<FileSystemResource> audio(@PathVariable UUID id, @PathVariable int scene) {
        ConceptExplainerJob.Scene s = scene(id, scene);
        return serve(s == null ? null : s.getAudioPath(), MediaType.parseMediaType("audio/wav"));
    }

    @GetMapping("/jobs/{id}/video")
    public ResponseEntity<FileSystemResource> video(@PathVariable UUID id) {
        ConceptExplainerJob job = jobs.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        return serve(job.getVideoPath(), MediaType.parseMediaType("video/mp4"));
    }

    private ConceptExplainerJob.Scene scene(UUID id, int scene) {
        ConceptExplainerJob job = jobs.get(id);
        if (job == null || scene < 1 || scene > job.getScenes().size()) return null;
        return job.getScenes().get(scene - 1);
    }

    private ResponseEntity<FileSystemResource> serve(String path, MediaType type) {
        if (path == null || path.isBlank()) return ResponseEntity.notFound().build();
        File file = Path.of(path).toFile();
        if (!file.exists() || !file.isFile()) return ResponseEntity.notFound().build();
        // URLs carry a version (?v=), so the browser may cache each version forever
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1)))
                .body(new FileSystemResource(file));
    }

    public record JobSummary(UUID id, String title, String duration, String language, ConceptExplainerJob.Status status,
                             double totalDurationSeconds, String createdAt) {}

    public record JobView(UUID id, String topic, String title, String summary, String language, String duration,
                          String difficulty, String motion, String track, String subject, boolean examFocus, ConceptExplainerJob.Status status, String stage,
                          String errorMessage, double totalDurationSeconds, int wordCount, String videoUrl,
                          List<String> warnings, int estimatedMinutes, List<SceneView> scenes) {}

    public record SceneView(int sceneNumber, String template, String title, String narration, String code,
                            double durationSeconds, int steps, String imageUrl, String audioUrl, boolean canRedraw) {}

    private JobView toView(ConceptExplainerJob job) {
        String base = "/api/concept-explainer/jobs/" + job.getId();
        var scenes = job.getScenes().stream().map(s -> new SceneView(
                s.getSceneNumber(), s.getTemplate(), s.getTitle(), s.getNarration(), s.getCode(), s.getDurationSeconds(),
                s.getStepPaths().size(),
                // only hand out a URL once the file exists; the version makes the browser fetch it exactly once
                s.getImagePath() == null ? null : base + "/scenes/" + s.getSceneNumber() + "/image?v=" + s.getImageVersion(),
                s.getAudioPath() == null ? null : base + "/scenes/" + s.getSceneNumber() + "/audio?v=" + s.getAudioVersion(),
                "analogy".equals(s.getTemplate()) || "analogy_code".equals(s.getTemplate())
        )).toList();
        int estimate = job.isDeepDive() ? 15 : 6;
        return new JobView(job.getId(), job.getTopic(), job.getTitle(), job.getSummary(), job.getLanguage(), job.getDuration(),
                job.getDifficulty(), job.getMotion(), job.getTrack(), job.getSubject(), job.isExamFocus(), job.getStatus(), job.getStage(), job.getErrorMessage(),
                job.getTotalDurationSeconds(), job.getWordCount(),
                job.getVideoPath() == null ? null : base + "/video?v=" + job.getVideoVersion(),
                List.copyOf(job.getWarnings()), estimate, scenes);
    }
}
