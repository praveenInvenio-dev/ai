package com.aistorystudio.controller;

import com.aistorystudio.sequence.SequenceModels.VideoSequence;
import com.aistorystudio.sequence.VideoSequenceService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Multi-scene "Scene sequence" page: N scenes with locked characters -> N consistent clips,
 * one by one -> one merged long video. Long GPU jobs run on the videoGenerationExecutor;
 * the page polls GET /{id}. See VideoSequenceService for the flow.
 */
@RestController
@RequestMapping("/api/video-sequences")
public class VideoSequenceController {

    private final VideoSequenceService service;

    public VideoSequenceController(VideoSequenceService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public VideoSequenceService.Status status() {
        return service.status();
    }

    @GetMapping
    public List<VideoSequenceService.SequenceView> list() {
        return service.list();
    }

    @PostMapping("/from-episode/{episodeId}")
    public ResponseEntity<VideoSequenceService.SequenceView> createFromEpisode(@PathVariable UUID episodeId,
            @RequestParam(defaultValue = "MINIMAX_H3") String engine,
            @RequestParam(required = false) Double secondsPerScene,
            @RequestParam(defaultValue = "vertical") String orientation,
            @RequestParam(required = false) Double crossfadeSeconds,
            @RequestParam(defaultValue = "KEYFRAMES") String continuity,
            @RequestParam(defaultValue = "false") Boolean reviewKeyframes) {
        VideoSequence s = service.createFromEpisode(episodeId, engine, secondsPerScene, orientation, crossfadeSeconds, continuity, reviewKeyframes);
        return ResponseEntity.accepted().body(service.view(s.id));
    }

    @PostMapping
    public ResponseEntity<VideoSequenceService.SequenceView> create(@RequestBody VideoSequenceService.CreateRequest req) {
        VideoSequence s = service.create(req);
        // Fired from this bean (not from inside the service) so @Async really intercepts it.
        service.runKeyframesAsync(s.id);
        return ResponseEntity.accepted().body(service.view(s.id));
    }

    @GetMapping("/{id}")
    public VideoSequenceService.SequenceView get(@PathVariable UUID id) {
        return service.view(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** After the keyframe review (or to retry failed scenes): makes every missing clip, then merges. */
    @PostMapping("/{id}/start-videos")
    public ResponseEntity<Map<String, String>> startVideos(@PathVariable UUID id) {
        service.requireIdle(id);
        service.clearCancel(id);
        service.startVideosAsync(id);
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }

    @PostMapping("/{id}/merge")
    public ResponseEntity<Map<String, String>> merge(@PathVariable UUID id) {
        service.requireIdle(id);
        service.mergeAsync(id);
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, String>> cancel(@PathVariable UUID id) {
        service.cancel(id);
        return ResponseEntity.accepted().body(Map.of("status", "cancelling"));
    }

    @PutMapping("/{id}/scenes/{index}")
    public ResponseEntity<Void> updateScene(@PathVariable UUID id, @PathVariable int index,
                                            @RequestBody Map<String, String> body) {
        service.updateScene(id, index, body.get("visual"), body.get("motion"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/scenes/{index}/regenerate-keyframe")
    public ResponseEntity<Map<String, String>> regenerateKeyframe(@PathVariable UUID id, @PathVariable int index) {
        service.requireIdle(id);
        service.regenerateKeyframeAsync(id, index);
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }

    @PostMapping(value = "/{id}/scenes/{index}/keyframe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> replaceKeyframe(@PathVariable UUID id, @PathVariable int index,
                                                @RequestParam("image") MultipartFile image) {
        service.replaceKeyframe(id, index, image);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/scenes/{index}/regenerate-video")
    public ResponseEntity<Map<String, String>> regenerateVideo(@PathVariable UUID id, @PathVariable int index) {
        service.requireIdle(id);
        service.regenerateClipAsync(id, index);
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }

    @GetMapping("/{id}/scenes/{index}/keyframe")
    public ResponseEntity<FileSystemResource> keyframe(@PathVariable UUID id, @PathVariable int index) {
        Path p = service.keyframePath(id, index);
        String name = p.getFileName().toString().toLowerCase();
        MediaType type = name.endsWith(".jpg") || name.endsWith(".jpeg") ? MediaType.IMAGE_JPEG
                : name.endsWith(".webp") ? MediaType.parseMediaType("image/webp") : MediaType.IMAGE_PNG;
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(p));
    }

    @GetMapping("/{id}/scenes/{index}/video")
    public ResponseEntity<FileSystemResource> clip(@PathVariable UUID id, @PathVariable int index) {
        return video(service.clipPath(id, index));
    }

    @GetMapping("/{id}/video")
    public ResponseEntity<FileSystemResource> merged(@PathVariable UUID id) {
        return video(service.mergedPath(id));
    }

    private static ResponseEntity<FileSystemResource> video(Path p) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4")).body(new FileSystemResource(p));
    }
}
