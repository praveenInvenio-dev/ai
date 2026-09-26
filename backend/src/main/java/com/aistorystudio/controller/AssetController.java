package com.aistorystudio.controller;

import com.aistorystudio.domain.Asset;
import com.aistorystudio.domain.CharacterReference;
import com.aistorystudio.domain.Scene;
import com.aistorystudio.domain.enums.AssetType;
import com.aistorystudio.repository.AssetRepository;
import com.aistorystudio.repository.CharacterReferenceRepository;
import com.aistorystudio.repository.SceneRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serves generated media bytes directly (image/audio/video/thumbnail) so the
 * UI can preview them inline instead of only being visible via the full zip
 * package download. Every lookup goes through the DB-recorded Asset path
 * rather than trusting client-supplied paths, and files are re-checked for
 * existence before serving.
 */
@RestController
@RequestMapping("/api")
public class AssetController {

    private final AssetRepository assetRepository;
    private final SceneRepository sceneRepository;
    private final CharacterReferenceRepository characterReferenceRepository;

    public AssetController(AssetRepository assetRepository, SceneRepository sceneRepository,
                            CharacterReferenceRepository characterReferenceRepository) {
        this.assetRepository = assetRepository;
        this.sceneRepository = sceneRepository;
        this.characterReferenceRepository = characterReferenceRepository;
    }

    @GetMapping("/scenes/{sceneId}/image")
    public ResponseEntity<FileSystemResource> sceneImage(@PathVariable UUID sceneId) {
        return serveAsset(assetRepository.findFirstBySceneIdAndAssetTypeAndActiveTrueOrderByVersionDesc(sceneId, AssetType.IMAGE),
                MediaType.IMAGE_PNG);
    }

    @GetMapping("/scenes/{sceneId}/audio")
    public ResponseEntity<FileSystemResource> sceneAudio(@PathVariable UUID sceneId) {
        return serveAsset(assetRepository.findFirstBySceneIdAndAssetTypeAndActiveTrueOrderByVersionDesc(sceneId, AssetType.AUDIO_NARRATION),
                MediaType.parseMediaType("audio/wav"));
    }

    @GetMapping("/episodes/{episodeId}/video")
    public ResponseEntity<FileSystemResource> episodeVideo(@PathVariable UUID episodeId) {
        return serveAsset(latestForEpisode(episodeId, AssetType.VIDEO), MediaType.parseMediaType("video/mp4"));
    }

    @GetMapping("/episodes/{episodeId}/thumbnail")
    public ResponseEntity<FileSystemResource> episodeThumbnail(@PathVariable UUID episodeId) {
        return serveAsset(latestForEpisode(episodeId, AssetType.THUMBNAIL), MediaType.IMAGE_PNG);
    }

    @GetMapping("/character-references/{referenceId}/image")
    public ResponseEntity<FileSystemResource> characterReferenceImage(@PathVariable UUID referenceId) {
        Optional<CharacterReference> ref = characterReferenceRepository.findById(referenceId);
        if (ref.isEmpty()) {
            return ResponseEntity.<FileSystemResource>notFound().build();
        }
        File file = new File(ref.get().getImagePath());
        if (!file.exists()) {
            return ResponseEntity.<FileSystemResource>notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(new FileSystemResource(file));
    }

    /** All scene image URLs for an episode in one call, so the UI doesn't need N lookups. */
    @GetMapping("/episodes/{episodeId}/scene-image-map")
    public java.util.Map<UUID, String> sceneImageMap(@PathVariable UUID episodeId) {
        List<Scene> scenes = sceneRepository.findByEpisodeIdOrderByOrderIndexAsc(episodeId);
        java.util.Map<UUID, String> map = new java.util.LinkedHashMap<>();
        for (Scene s : scenes) {
            boolean hasImage = assetRepository
                    .findFirstBySceneIdAndAssetTypeAndActiveTrueOrderByVersionDesc(s.getId(), AssetType.IMAGE)
                    .isPresent();
            if (hasImage) {
                map.put(s.getId(), "/api/scenes/" + s.getId() + "/image");
            }
        }
        return map;
    }

    private Optional<Asset> latestForEpisode(UUID episodeId, AssetType type) {
        return assetRepository.findByEpisodeIdAndAssetType(episodeId, type).stream()
                .max(Comparator.comparingInt(Asset::getVersion));
    }

    private ResponseEntity<FileSystemResource> serveAsset(Optional<Asset> assetOpt, MediaType mediaType) {
        if (assetOpt.isEmpty()) {
            return ResponseEntity.<FileSystemResource>notFound().build();
        }
        File file = new File(assetOpt.get().getFilePath());
        if (!file.exists()) {
            return ResponseEntity.<FileSystemResource>notFound().build();
        }
        return ResponseEntity.ok().contentType(mediaType).body(new FileSystemResource(file));
    }
}
