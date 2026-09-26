package com.aistorystudio.controller;

import com.aistorystudio.domain.Asset;
import com.aistorystudio.domain.Episode;
import com.aistorystudio.domain.Scene;
import com.aistorystudio.service.StoryboardService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Storyboard mode: build a video from images the user supplies.
 *
 * Covers both flows the UI offers - typing a story scene by scene, and
 * uploading images against an episode the LLM already drafted. Both end at
 * {@code POST /assemble}, which narrates and renders.
 */
@RestController
@RequestMapping("/api/storyboard")
@CrossOrigin
public class StoryboardController {

    private final StoryboardService service;

    public StoryboardController(StoryboardService service) {
        this.service = service;
    }

    // ---- episodes ----------------------------------------------------------

    @PostMapping("/episodes")
    public ResponseEntity<EpisodeView> create(@RequestBody StoryboardService.CreateStoryboardRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(EpisodeView.of(service.createStoryboard(request)));
    }

    // ---- scenes ------------------------------------------------------------

    @PostMapping("/episodes/{episodeId}/scenes")
    public SceneView addScene(@PathVariable UUID episodeId,
                              @RequestBody StoryboardService.SceneInput input) {
        return SceneView.of(service.addScene(episodeId, input), false);
    }

    @PutMapping("/episodes/{episodeId}/scenes/{sceneId}")
    public SceneView updateScene(@PathVariable UUID episodeId, @PathVariable UUID sceneId,
                                 @RequestBody StoryboardService.SceneInput input) {
        return SceneView.of(service.updateScene(episodeId, sceneId, input), false);
    }

    @DeleteMapping("/episodes/{episodeId}/scenes/{sceneId}")
    public ResponseEntity<Void> deleteScene(@PathVariable UUID episodeId, @PathVariable UUID sceneId) {
        service.deleteScene(episodeId, sceneId);
        return ResponseEntity.noContent().build();
    }

    public record ReorderScenesRequest(List<UUID> sceneIds) {}

    @PostMapping("/episodes/{episodeId}/scenes/reorder")
    public List<SceneView> reorder(@PathVariable UUID episodeId,
                                   @RequestBody ReorderScenesRequest request) {
        var images = service.imagesBySceneId(episodeId);
        return service.reorderScenes(episodeId, request.sceneIds()).stream()
                .map(s -> SceneView.of(s, images.containsKey(s.getId())))
                .toList();
    }

    /** Scenes plus whether each already has an image, which is what both
     *  screens need to render their checklist. */
    @GetMapping("/episodes/{episodeId}/scenes")
    public List<SceneView> scenes(@PathVariable UUID episodeId) {
        var images = service.imagesBySceneId(episodeId);
        return service.scenesOf(episodeId).stream()
                .map(s -> SceneView.of(s, images.containsKey(s.getId())))
                .toList();
    }

    // ---- images ------------------------------------------------------------

    @PostMapping(value = "/episodes/{episodeId}/scenes/{sceneId}/image",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AssetView attachImage(@PathVariable UUID episodeId, @PathVariable UUID sceneId,
                                 @RequestParam("file") MultipartFile file) {
        return AssetView.of(service.attachImage(episodeId, sceneId, file));
    }

    /**
     * Bulk upload. Files are matched to scenes <em>in the order sent</em>, and
     * the count must match exactly - a partial upload silently assigned to the
     * wrong scenes would look like the app scrambling the story.
     */
    @PostMapping(value = "/episodes/{episodeId}/images",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<AssetView> attachImages(@PathVariable UUID episodeId,
                                        @RequestParam("files") List<MultipartFile> files) {
        return service.attachImagesInOrder(episodeId, files).stream().map(AssetView::of).toList();
    }

    // ---- music ---------------------------------------------------------------

    /** Uploads background music for the whole episode. Supersedes any
     *  previously uploaded music, and takes priority over the episode's
     *  preset selection at render time. */
    @PostMapping(value = "/episodes/{episodeId}/music",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AssetView attachMusic(@PathVariable UUID episodeId, @RequestParam("file") MultipartFile file) {
        return AssetView.of(service.attachMusic(episodeId, file));
    }

    // ---- assembly ----------------------------------------------------------

    public record AssembleRequest(String voice) {}

    @PostMapping("/episodes/{episodeId}/assemble")
    public Map<String, String> assemble(@PathVariable UUID episodeId,
                                        @RequestBody(required = false) AssembleRequest request) {
        Path video = service.assemble(episodeId, request == null ? null : request.voice());
        // Only the filename is returned: absolute container paths are an
        // internal detail and are meaningless to the browser anyway.
        return Map.of("status", "COMPLETED", "fileName", video.getFileName().toString());
    }

    // ---- errors ------------------------------------------------------------

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    // ---- views -------------------------------------------------------------

    public record EpisodeView(UUID id, String title, String status, String language,
                              String visualStyle, Integer durationTargetSec) {
        static EpisodeView of(Episode e) {
            return new EpisodeView(e.getId(), e.getTitle(), e.getStatus().name(),
                    e.getLanguage(), e.getVisualStyle(), e.getDurationTargetSec());
        }
    }

    public record VoiceSegmentView(String character, String text, String voice, Double speed,
                                   Double pitch, String emotion, Integer pauseBeforeMs, Integer pauseAfterMs) {}

    public record SceneView(UUID id, int sceneNumber, String narration, String action,
                            String location, String emotion, Double imageDurationSeconds,
                            Double narrationSeconds, boolean hasImage,
                            List<VoiceSegmentView> voiceSegments, List<String> characterNames) {
        static SceneView of(Scene s, boolean hasImage) {
            List<VoiceSegmentView> segments = List.of();
            List<String> characterNames = new java.util.ArrayList<>();
            if (s.getCharactersJson() != null && !s.getCharactersJson().isBlank()) {
                try {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var root = mapper.readTree(s.getCharactersJson());
                    if (root.isArray()) {
                        for (var n : root) {
                            String name = n.isTextual() ? n.asText() : n.path("name").asText("");
                            if (!name.isBlank()) characterNames.add(name);
                        }
                    }
                } catch (Exception ignored) { }
            }
            if (s.getVoiceSegmentsJson() != null && !s.getVoiceSegmentsJson().isBlank()) {
                try {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    segments = mapper.readValue(s.getVoiceSegmentsJson(),
                            new TypeReference<List<StoryboardService.VoiceSegmentInput>>() {}).stream()
                            .map(v -> new VoiceSegmentView(v.character(), v.text(), v.voice(), v.speed(), v.pitch(),
                                    v.emotion(), v.pauseBeforeMs(), v.pauseAfterMs())).toList();
                } catch (Exception ignored) { }
            }
            return new SceneView(s.getId(), s.getSceneNumber(), s.getNarration(), s.getAction(),
                    s.getLocation(), s.getEmotion(), s.getImageDurationSeconds(),
                    s.getNarrationSeconds(), hasImage, segments, characterNames);
        }
    }

    public record AssetView(UUID id, UUID sceneId, String assetType, String provider) {
        static AssetView of(Asset a) {
            return new AssetView(a.getId(), a.getSceneId(), a.getAssetType().name(), a.getProvider());
        }
    }
}
