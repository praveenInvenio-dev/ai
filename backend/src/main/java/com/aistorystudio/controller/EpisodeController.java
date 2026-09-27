package com.aistorystudio.controller;

import com.aistorystudio.domain.Episode;
import com.aistorystudio.domain.GenerationJob;
import com.aistorystudio.domain.Scene;
import com.aistorystudio.dto.SceneDto;
import com.aistorystudio.dto.SceneDto.VoiceSegmentDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aistorystudio.dto.JobStatusResponse;
import com.aistorystudio.repository.EpisodeRepository;
import com.aistorystudio.repository.SceneRepository;
import com.aistorystudio.service.JobService;
import com.aistorystudio.service.PackagingService;
import com.aistorystudio.service.ProductionPipelineService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/episodes")
public class EpisodeController {

    private final EpisodeRepository episodeRepository;
    private final SceneRepository sceneRepository;
    private final ProductionPipelineService productionPipelineService;
    private final PackagingService packagingService;
    private final JobService jobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EpisodeController(EpisodeRepository episodeRepository, SceneRepository sceneRepository,
                              ProductionPipelineService productionPipelineService, PackagingService packagingService,
                              JobService jobService) {
        this.episodeRepository = episodeRepository;
        this.sceneRepository = sceneRepository;
        this.productionPipelineService = productionPipelineService;
        this.packagingService = packagingService;
        this.jobService = jobService;
    }

    @GetMapping
    public List<Episode> listByProject(@RequestParam UUID projectId) {
        return episodeRepository.findByProjectId(projectId);
    }

    @GetMapping("/{id}")
    public Episode get(@PathVariable UUID id) {
        return episodeRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Episode not found: " + id));
    }

    @GetMapping("/{id}/scenes")
    public List<SceneDto> scenes(@PathVariable UUID id) {
        return sceneRepository.findByEpisodeIdOrderByOrderIndexAsc(id).stream().map(this::toDto).toList();
    }

    @PutMapping("/{id}/scenes/{sceneId}/voice-segments")
    public SceneDto updateVoiceSegments(@PathVariable UUID id, @PathVariable UUID sceneId,
                                        @RequestBody List<VoiceSegmentDto> segments) {
        Scene scene = sceneRepository.findById(sceneId)
                .filter(s -> id.equals(s.getEpisodeId()))
                .orElseThrow(() -> new IllegalArgumentException("Scene not found"));
        try {
            scene.setVoiceSegmentsJson(objectMapper.writeValueAsString(segments == null ? List.of() : segments));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid voice segments", e);
        }
        return toDto(sceneRepository.save(scene));
    }

    private SceneDto toDto(Scene s) {
        List<VoiceSegmentDto> segments = new java.util.ArrayList<>();
        List<String> names = new java.util.ArrayList<>();
        try {
            if (s.getVoiceSegmentsJson() != null) {
                segments = objectMapper.readValue(s.getVoiceSegmentsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, VoiceSegmentDto.class));
            }
            if (s.getCharactersJson() != null) {
                var arr = objectMapper.readTree(s.getCharactersJson());
                if (arr.isArray()) for (var n : arr) {
                    String name = n.isTextual() ? n.asText() : n.path("name").asText("");
                    if (!name.isBlank()) names.add(name);
                }
            }
        } catch (Exception ignored) {}
        if (segments.isEmpty()) segments = List.of(new VoiceSegmentDto("Narrator", s.getNarration(), "", 1.0, 1.0, s.getEmotion(), 0, 0,
                null, null, List.of(), null, null, null));
        return new SceneDto(s.getId(), s.getSceneNumber(), s.getPurpose(), s.getNarration(), s.getLocation(), s.getAction(),
                s.getEmotion(), s.getCamera(), s.getLighting(), s.getImagePrompt(), s.getNegativePrompt(),
                s.getMotionPrompt(), s.getMotionNegativePrompt(), s.getVisualSpecJson(),
                s.getNarrationSeconds(), s.getImageDurationSeconds(), s.getCameraMovement(), s.getImportance(),
                s.isLocked(), s.isNarrationLocked(), s.getAnimationMode(), segments, names);
    }

    @PostMapping("/{id}/generate-images")
    public GenerationJob generateImages(@PathVariable UUID id) {
        return productionPipelineService.generateImagesOnly(id);
    }

    /** Toggles a scene's lock. A locked scene's image is skipped by bulk
     *  generation (already true for any existing image) and, more
     *  importantly, refused by the force-regenerate endpoint below - the
     *  only way anything overwrites a locked image is unlocking it first. */
    @PostMapping("/{id}/scenes/{sceneId}/lock")
    public SceneDto setLock(@PathVariable UUID id, @PathVariable UUID sceneId, @RequestParam boolean locked) {
        Scene scene = sceneRepository.findById(sceneId)
                .filter(s -> s.getEpisodeId().equals(id))
                .orElseThrow(() -> new IllegalArgumentException("Scene not found for this episode."));
        scene.setLocked(locked);
        sceneRepository.save(scene);
        return toDto(scene);
    }

    /**
     * Force-regenerates exactly one scene's image, bypassing the
     * skip-if-already-generated check that {@code generate-images} uses for
     * bulk/retry runs. This is the only path that can replace an existing
     * image - refused outright if the scene is locked.
     */
    @PostMapping("/{id}/scenes/{sceneId}/regenerate-image")
    public SceneDto regenerateSceneImage(@PathVariable UUID id, @PathVariable UUID sceneId) {
        return toDto(productionPipelineService.regenerateSingleSceneImage(id, sceneId));
    }

    /** Narration counterpart to {@link #setLock}: toggles the independent
     *  narration-only lock so an image can be kept while the voice line is
     *  redone, or vice versa. */
    @PostMapping("/{id}/scenes/{sceneId}/lock-narration")
    public SceneDto setNarrationLock(@PathVariable UUID id, @PathVariable UUID sceneId, @RequestParam boolean locked) {
        Scene scene = sceneRepository.findById(sceneId)
                .filter(s -> s.getEpisodeId().equals(id))
                .orElseThrow(() -> new IllegalArgumentException("Scene not found for this episode."));
        scene.setNarrationLocked(locked);
        sceneRepository.save(scene);
        return toDto(scene);
    }

    /** Narration counterpart to {@link #regenerateSceneImage}. */
    @PostMapping("/{id}/scenes/{sceneId}/regenerate-narration")
    public SceneDto regenerateSceneNarration(@PathVariable UUID id, @PathVariable UUID sceneId) {
        return toDto(productionPipelineService.regenerateSingleSceneNarration(id, sceneId));
    }

    /** Sets a scene's animation mode override (spec section 5/46): AUTO
     *  (default, AnimationDecisionService picks), STATIC (no camera motion),
     *  or TWO_POINT_FIVE_D (force the full camera+parallax treatment). */
    @PostMapping("/{id}/scenes/{sceneId}/animation-mode")
    public SceneDto setAnimationMode(@PathVariable UUID id, @PathVariable UUID sceneId, @RequestParam String mode) {
        Scene scene = sceneRepository.findById(sceneId)
                .filter(s -> s.getEpisodeId().equals(id))
                .orElseThrow(() -> new IllegalArgumentException("Scene not found for this episode."));
        String normalized = mode == null ? "AUTO" : mode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("AUTO", "STATIC", "TWO_POINT_FIVE_D", "TALKING_CHARACTER", "CHARACTER_MOTION").contains(normalized)) {
            throw new IllegalArgumentException("Unknown animation mode: " + mode);
        }
        scene.setAnimationMode(normalized);
        sceneRepository.save(scene);
        return toDto(scene);
    }

    /** Sets (or clears, with a blank/omitted value) the episode's background
     *  music mood preset. A user-uploaded music file, if one exists, always
     *  takes priority over this at render time - see
     *  ProductionPipelineService.resolveMusicPath. */
    @PostMapping("/{id}/music-preset")
    public Episode setMusicPreset(@PathVariable UUID id, @RequestParam(required = false) String preset) {
        Episode episode = episodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + id));
        if (episode.isMusicLocked()) {
            throw new IllegalStateException("Music is locked for this episode. Unlock it before changing the preset.");
        }
        episode.setMusicPreset(preset == null || preset.isBlank() ? null : preset);
        return episodeRepository.save(episode);
    }

    private static final java.util.Set<String> VALID_QUALITY_PROFILES = java.util.Set.of("FAST", "BALANCED", "QUALITY");

    /** FAST/BALANCED/QUALITY (Phase 4) - only affects generation calls made
     *  AFTER this is set, not anything already generated. See
     *  ProductionPipelineService.qualityImageSteps() for what each tier
     *  actually changes. */
    @PostMapping("/{id}/quality-profile")
    public Episode setQualityProfile(@PathVariable UUID id, @RequestParam String profile) {
        String upper = profile == null ? "" : profile.trim().toUpperCase(java.util.Locale.ROOT);
        if (!VALID_QUALITY_PROFILES.contains(upper)) {
            throw new IllegalArgumentException("Quality profile must be one of FAST, BALANCED, QUALITY - got: " + profile);
        }
        Episode episode = episodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + id));
        episode.setQualityProfile(upper);
        return episodeRepository.save(episode);
    }

    /** Toggles the episode's music lock - same idea as {@link #setLock} for
     *  an image, independently, for the background-music selection. */
    @PostMapping("/{id}/music-lock")
    public Episode setMusicLock(@PathVariable UUID id, @RequestParam boolean locked) {
        Episode episode = episodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + id));
        episode.setMusicLocked(locked);
        return episodeRepository.save(episode);
    }

    /** Final approval starts narration/video only after images exist and voices are selected. */
    @PostMapping("/{id}/approve")
    public GenerationJob approve(@PathVariable UUID id) {
        return productionPipelineService.approveAndStartProduction(id);
    }

    /**
     * Lets the Production Dashboard recover its job status when opened directly
     * (bookmark, refresh, navigated from the projects list) instead of only right
     * after clicking Approve, when the job id is already in hand.
     */
    @GetMapping("/{id}/latest-job")
    public ResponseEntity<JobStatusResponse> latestJob(@PathVariable UUID id) {
        return jobService.getLatestForEpisode(id)
                .map(view -> ResponseEntity.<JobStatusResponse>ok(view))
                .orElseGet(() -> ResponseEntity.<JobStatusResponse>notFound().build());
    }

    @GetMapping("/{id}/package")
    public ResponseEntity<ByteArrayResource> downloadPackage(@PathVariable UUID id) {
        byte[] zip = packagingService.buildZip(id);
        ByteArrayResource resource = new ByteArrayResource(zip);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("episode-" + id + ".zip").build().toString())
                .body(resource);
    }
}
