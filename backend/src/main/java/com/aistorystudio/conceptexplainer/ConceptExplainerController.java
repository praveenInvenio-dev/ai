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
                                String track, String subject, Boolean examFocus, String voice, String style,
                                String teachingStyle, String destination, String format,
                                String audience, String outcome, String tone, String concept, Boolean reviewScript,
                                String visualVariance, String motionIntensity, String density, Boolean useManim) {}

    @PostMapping("/jobs")
    public ResponseEntity<Map<String, UUID>> create(@RequestBody CreateRequest r) {
        ConceptExplainerJob job = service.create(r.topic(), r.instructions(), r.language(), r.duration(), r.difficulty(),
                r.motion() != null ? r.motion() : r.animationMode(), r.model(), r.track(), r.subject(), Boolean.TRUE.equals(r.examFocus()), r.voice());
        job.setStyle(r.style());
        job.setFormat(r.format());                 // TUTORIAL (default, reference-video teaching) | CLASSIC
        job.setTeachingStyle(r.teachingStyle());   // ENGAGING_TECH_TUTOR (default) | STORYTELLING_TEACHER | PROFESSIONAL_INSTRUCTOR | SIMPLE_BEGINNER
        job.setDestination(r.destination());       // SOCIAL (default, subscribe closing) | CLASSROOM
        // pre-production: the brief, the chosen concept, the creative dials and the "review the script first" gate
        job.setAudience(r.audience());
        job.setOutcome(r.outcome());
        job.setTone(r.tone());
        job.setConcept(r.concept());
        job.setReviewScript(Boolean.TRUE.equals(r.reviewScript()));
        job.setDials(r.visualVariance(), r.motionIntensity(), r.density());
        job.setUseManim(r.useManim() == null || r.useManim());
        service.generateAsync(job.getId());
        return ResponseEntity.accepted().body(Map.of("jobId", job.getId()));
    }

    /** Whether animated maths / physics scenes are possible right now (the Manim service answers). */
    @GetMapping("/manim/status")
    public Map<String, Object> manimStatus() {
        return Map.of("available", service.manimAvailable(), "templates", com.aistorystudio.manim.ManimClient.TEMPLATES);
    }

    // ---- pre-production

    public record ConceptsRequest(String topic, String language, String duration, String difficulty, String audience,
                                  String outcome, String tone, String track, String model) {}

    /** Concepts first: three genuinely different ways to make this video. Nothing is stored or generated. */
    @PostMapping("/concepts")
    public ResponseEntity<?> concepts(@RequestBody ConceptsRequest r) {
        try {
            return ResponseEntity.ok(Map.of("concepts", service.proposeConcepts(r.topic(), r.language(), r.duration(), r.difficulty(),
                    r.audience(), r.outcome(), r.tone(), r.track(), r.model())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "Could not get concepts: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
        }
    }

    /** The script review is done: draw, record and build the video. */
    @PostMapping("/jobs/{id}/continue")
    public ResponseEntity<?> continueAfterReview(@PathVariable UUID id) {
        try {
            service.continueAfterReview(id);
            service.continueAsync(id);
            return ResponseEntity.accepted().body(Map.of("status", "queued"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Ask the model to rewrite only the flagged scenes (same sentence count, same facts). */
    @PostMapping("/jobs/{id}/doctor/fix")
    public ResponseEntity<?> fixScript(@PathVariable UUID id) {
        try {
            service.startScriptFix(id);
            service.fixScriptAsync(id);
            return ResponseEntity.accepted().body(Map.of("status", "queued"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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

    // ---- kids picture-book flow: review every picture, then make the video

    public record NarrationEdit(List<String> sentences) {}
    public record PictureRedo(String description) {}
    public record MakeVideo(boolean animated) {}

    /** Change what a scene says (one sentence per entry) while the lesson waits at the storyboard. */
    @PutMapping("/jobs/{id}/scenes/{scene}/narration")
    public ResponseEntity<Map<String, String>> editNarration(@PathVariable UUID id, @PathVariable int scene, @RequestBody NarrationEdit body) throws Exception {
        service.updateNarration(id, scene, body == null ? null : body.sentences());
        return ResponseEntity.ok(Map.of("status", "saved"));
    }

    /** Draw a new picture for a scene, optionally from a new description; same characters (cast reference). */
    @PostMapping("/jobs/{id}/scenes/{scene}/picture")
    public ResponseEntity<Map<String, String>> redoPicture(@PathVariable UUID id, @PathVariable int scene, @RequestBody(required = false) PictureRedo body) throws Exception {
        service.redoPicture(id, scene, body == null ? null : body.description());
        service.redoPictureAsync(id, scene);
        return ResponseEntity.accepted().body(Map.of("status", "queued"));
    }

    /** Turn the reviewed pictures into the video: animated = Wan animates every picture (cartoon), otherwise camera moves. */
    @PostMapping("/jobs/{id}/video")
    public ResponseEntity<Map<String, String>> makeVideo(@PathVariable UUID id, @RequestBody(required = false) MakeVideo body) {
        service.startVideo(id, body != null && body.animated());
        service.makeVideoAsync(id);
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

    @GetMapping("/jobs/{id}/script")
    public ResponseEntity<String> script(@PathVariable UUID id) {
        ConceptExplainerJob job = jobs.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        StringBuilder out = new StringBuilder();
        out.append(job.getTitle() == null ? job.getTopic() : job.getTitle()).append("\n\n");
        for (ConceptExplainerJob.Scene s : job.getScenes()) {
            out.append("SCENE ").append(s.getSceneNumber()).append(" — ").append(s.getTitle()).append("\n");
            for (String line : s.getSentences()) out.append(line).append("\n");
            out.append("\n");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(out.toString().trim());
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
                          List<String> warnings, int estimatedMinutes, String narratorVoice, String scriptUrl, String teachingStyle, String destination,
                          List<SceneView> scenes, List<IssueView> scriptIssues, int scriptWords, int scriptSeconds) {}

    public record IssueView(int scene, String severity, String code, String message, boolean fixable) {}

    public record SceneView(int sceneNumber, String template, String title, String narration, String code,
                            double durationSeconds, int steps, String imageUrl, String audioUrl, boolean canRedraw,
                            String sectionType, String learningObjective, String visualDescription, String voiceEmotion, String voicePace, String voiceDelivery,
                            List<String> sentences, String pictureDescription) {}

    private static String pictureDescription(ConceptExplainerJob.Scene s) {
        try {
            for (var e : new com.fasterxml.jackson.databind.ObjectMapper().readTree(s.getPlanJson() == null ? "{}" : s.getPlanJson()).path("elements"))
                if ("photo".equals(e.path("type").asText())) return e.path("text").asText(null);
        } catch (Exception ignored) { }
        return null;
    }

    private JobView toView(ConceptExplainerJob job) {
        String base = "/api/concept-explainer/jobs/" + job.getId();
        var scenes = job.getScenes().stream().map(s -> new SceneView(
                s.getSceneNumber(), s.getTemplate(), s.getTitle(), s.getNarration(), s.getCode(), s.getDurationSeconds(),
                s.getStepPaths().size(),
                // only hand out a URL once the file exists; the version makes the browser fetch it exactly once
                s.getImagePath() == null ? null : base + "/scenes/" + s.getSceneNumber() + "/image?v=" + s.getImageVersion(),
                s.getAudioPath() == null ? null : base + "/scenes/" + s.getSceneNumber() + "/audio?v=" + s.getAudioVersion(),
                "analogy".equals(s.getTemplate()) || "analogy_code".equals(s.getTemplate()),
                s.getSectionType(), s.getLearningObjective(), s.getVisualDescription(), s.getVoiceEmotion(), s.getVoicePace(), s.getVoiceDelivery(),
                List.copyOf(s.getSentences()), pictureDescription(s)
        )).toList();
        int estimate = job.isDeepDive() ? 15 : 6;
        return new JobView(job.getId(), job.getTopic(), job.getTitle(), job.getSummary(), job.getLanguage(), job.getDuration(),
                job.getDifficulty(), job.getMotion(), job.getTrack(), job.getSubject(), job.isExamFocus(), job.getStatus(), job.getStage(), job.getErrorMessage(),
                job.getTotalDurationSeconds(), job.getWordCount(),
                job.getVideoPath() == null ? null : base + "/video?v=" + job.getVideoVersion(),
                List.copyOf(job.getWarnings()), estimate, job.getVoice(), base + "/script", job.getTeachingStyle(), job.getDestination(), scenes,
                job.getScriptReport() == null ? List.of() : job.getScriptReport().issues().stream()
                        .map(i -> new IssueView(i.scene(), i.severity().name(), i.code(), i.message(), i.fixable())).toList(),
                job.getScriptReport() == null ? 0 : job.getScriptReport().words(), job.getScriptReport() == null ? 0 : job.getScriptReport().seconds());
    }
}
