package com.aistorystudio.studio;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** Motion &amp; Effects Studio API. Long jobs run on videoGenerationExecutor; the page polls GET /jobs/{id}. */
@RestController
@RequestMapping("/api/studio")
public class StudioController {

    private final StudioService service;

    public StudioController(StudioService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public StudioService.Status status() { return service.status(); }

    @GetMapping("/camera-presets")
    public List<StudioService.CameraPreset> cameraPresets() { return service.cameraPresets(); }

    @GetMapping("/jobs")
    public List<StudioService.JobView> jobs() { return service.list(); }

    @GetMapping("/jobs/{id}")
    public StudioService.JobView job(@PathVariable UUID id) { return service.view(id); }

    @GetMapping("/jobs/{id}/video")
    public ResponseEntity<FileSystemResource> video(@PathVariable UUID id) {
        String ext = service.resultExtension(id);
        String type = switch (ext) { case "webm" -> "video/webm"; case "png" -> "image/png"; default -> "video/mp4"; };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .header("Content-Disposition", "inline; filename=\"studio-" + id + "." + ext + "\"")
                .body(new FileSystemResource(service.result(id)));
    }

    /** Character image + driving video -> the character performs that movement (Wan 2.2 Animate). */
    @PostMapping(value = "/motion-control", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StudioService.JobView> motionControl(@RequestParam("image") MultipartFile image,
                                                               @RequestParam("video") MultipartFile video,
                                                               @RequestParam(value = "prompt", required = false) String prompt,
                                                               @RequestParam(value = "orientation", defaultValue = "vertical") String orientation,
                                                               @RequestParam(value = "seconds", required = false) Double seconds) {
        StudioJob job = service.create(StudioJob.Tool.MOTION_CONTROL, image, video, null);
        service.motionControlAsync(job.getId(), prompt, orientation, seconds);
        return ResponseEntity.accepted().body(service.view(job.getId()));
    }

    /** resolution 720 | 1080 | 1440 (2K) | 2160 (4K), short side. mode AI (RealESRGAN x2 + lanczos) or FAST (FFmpeg). smooth24 = 24 fps first. */
    @PostMapping(value = "/upscale", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StudioService.JobView> upscale(@RequestParam(value = "video", required = false) MultipartFile video,
                                                         @RequestParam(value = "sourceJobId", required = false) UUID sourceJobId,
                                                         @RequestParam(value = "mode", defaultValue = "AI") String mode,
                                                         @RequestParam(value = "smooth24", defaultValue = "false") boolean smooth24,
                                                         @RequestParam(value = "resolution", defaultValue = "1080") Integer resolution,
                                                         @RequestParam(value = "episodeId", required = false) UUID episodeId,
                                                         @RequestParam(value = "sequenceId", required = false) UUID sequenceId) {
        StudioJob job = service.create(StudioJob.Tool.UPSCALE, null, video, sourceJobId, episodeId, sequenceId);
        service.upscaleAsync(job.getId(), mode, smooth24, resolution);
        return ResponseEntity.accepted().body(service.view(job.getId()));
    }

    /** aspect 9:16 | 16:9 | 1:1 | 4:5; mode BLUR_FILL | CROP | BARS. */
    @PostMapping(value = "/reframe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StudioService.JobView> reframe(@RequestParam(value = "video", required = false) MultipartFile video,
                                                         @RequestParam(value = "sourceJobId", required = false) UUID sourceJobId,
                                                         @RequestParam(value = "aspect", defaultValue = "9:16") String aspect,
                                                         @RequestParam(value = "mode", defaultValue = "BLUR_FILL") String mode,
                                                         @RequestParam(value = "episodeId", required = false) UUID episodeId,
                                                         @RequestParam(value = "sequenceId", required = false) UUID sequenceId) {
        StudioJob job = service.create(StudioJob.Tool.REFRAME, null, video, sourceJobId, episodeId, sequenceId);
        service.reframeAsync(job.getId(), aspect, mode);
        return ResponseEntity.accepted().body(service.view(job.getId()));
    }

    /** Cut out the subject. Image -> PNG; video (<= 15 s) -> transparent WebM or MP4 on colour / blurred background. */
    @PostMapping(value = "/background", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StudioService.JobView> background(@RequestParam(value = "image", required = false) MultipartFile image,
                                                            @RequestParam(value = "video", required = false) MultipartFile video,
                                                            @RequestParam(value = "sourceJobId", required = false) UUID sourceJobId,
                                                            @RequestParam(value = "background", defaultValue = "transparent") String background,
                                                            @RequestParam(value = "color", defaultValue = "#00ff00") String color,
                                                            @RequestParam(value = "quality", defaultValue = "general") String quality) {
        StudioJob job = service.create(StudioJob.Tool.BACKGROUND, image, video, sourceJobId, null, null);
        service.backgroundAsync(job.getId(), background, color, quality);
        return ResponseEntity.accepted().body(service.view(job.getId()));
    }

    /** Re-voice a video in another language. mode REVOICE (keep picture) | REANIMATE (H3 new clip, lips match). */
    @PostMapping(value = "/dub", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StudioService.JobView> dub(@RequestParam(value = "video", required = false) MultipartFile video,
                                                     @RequestParam(value = "sourceJobId", required = false) UUID sourceJobId,
                                                     @RequestParam(value = "episodeId", required = false) UUID episodeId,
                                                     @RequestParam(value = "sequenceId", required = false) UUID sequenceId,
                                                     @RequestParam("script") String script,
                                                     @RequestParam(value = "sourceLanguage", required = false) String sourceLanguage,
                                                     @RequestParam("targetLanguage") String targetLanguage,
                                                     @RequestParam(value = "mode", defaultValue = "REVOICE") String mode,
                                                     @RequestParam(value = "speechEngine", defaultValue = "H3") String speechEngine,
                                                     @RequestParam(value = "originalVolume", defaultValue = "0.15") double originalVolume,
                                                     @RequestParam(value = "voice", required = false) String voice) {
        StudioJob job = service.create(StudioJob.Tool.DUB, null, video, sourceJobId, episodeId, sequenceId);
        service.dubAsync(job.getId(), script, sourceLanguage, targetLanguage, mode, speechEngine, originalVolume, voice);
        return ResponseEntity.accepted().body(service.view(job.getId()));
    }

    /** Hook / pacing / audio / format review with a score and concrete fixes. */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StudioService.JobView> analyze(@RequestParam(value = "video", required = false) MultipartFile video,
                                                         @RequestParam(value = "sourceJobId", required = false) UUID sourceJobId,
                                                         @RequestParam(value = "episodeId", required = false) UUID episodeId,
                                                         @RequestParam(value = "sequenceId", required = false) UUID sequenceId,
                                                         @RequestParam(value = "platform", required = false) String platform) {
        StudioJob job = service.create(StudioJob.Tool.ANALYZE, null, video, sourceJobId, episodeId, sequenceId);
        service.analyzeAsync(job.getId(), platform);
        return ResponseEntity.accepted().body(service.view(job.getId()));
    }
}
