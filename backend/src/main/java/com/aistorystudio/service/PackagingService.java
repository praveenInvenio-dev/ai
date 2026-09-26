package com.aistorystudio.service;

import com.aistorystudio.domain.Asset;
import com.aistorystudio.domain.Episode;
import com.aistorystudio.domain.Scene;
import com.aistorystudio.domain.StoryBible;
import com.aistorystudio.repository.AssetRepository;
import com.aistorystudio.repository.EpisodeRepository;
import com.aistorystudio.repository.SceneRepository;
import com.aistorystudio.repository.StoryBibleRepository;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the "Complete Package" zip (spec section 40) on demand from stored assets. */
@Service
public class PackagingService {

    private final EpisodeRepository episodeRepository;
    private final SceneRepository sceneRepository;
    private final AssetRepository assetRepository;
    private final StoryBibleRepository storyBibleRepository;

    public PackagingService(EpisodeRepository episodeRepository, SceneRepository sceneRepository,
                             AssetRepository assetRepository, StoryBibleRepository storyBibleRepository) {
        this.episodeRepository = episodeRepository;
        this.sceneRepository = sceneRepository;
        this.assetRepository = assetRepository;
        this.storyBibleRepository = storyBibleRepository;
    }

    public byte[] buildZip(UUID episodeId) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        List<Scene> scenes = sceneRepository.findByEpisodeIdOrderByOrderIndexAsc(episodeId);
        List<Asset> assets = assetRepository.findByEpisodeId(episodeId);
        StoryBible bible = storyBibleRepository.findFirstByEpisodeIdAndActiveTrueOrderByVersionDesc(episodeId).orElse(null);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            if (bible != null) {
                addEntry(zos, "story/story-bible.json", toBibleJson(bible).getBytes());
                addEntry(zos, "story/story.txt", (bible.getFullNarration() == null ? "" : bible.getFullNarration()).getBytes());
            }
            for (Asset asset : assets) {
                Path path = Path.of(asset.getFilePath());
                if (Files.exists(path)) {
                    addEntry(zos, folderFor(asset) + "/" + path.getFileName(), Files.readAllBytes(path));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build package zip for episode " + episodeId, e);
        }
        return baos.toByteArray();
    }

    private String folderFor(Asset asset) {
        return switch (asset.getAssetType()) {
            case IMAGE -> "images";
            case AUDIO_NARRATION -> "audio";
            case MUSIC, SFX -> "audio";
            case VIDEO -> "video";
            case SUBTITLE -> "subtitles";
            case THUMBNAIL -> "thumbnail";
            case SHORT -> "shorts";
        };
    }

    private void addEntry(ZipOutputStream zos, String name, byte[] content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content);
        zos.closeEntry();
    }

    private String toBibleJson(StoryBible b) {
        return "{"
                + "\"title\":" + quote(b.getTitle()) + ","
                + "\"logline\":" + quote(b.getLogline()) + ","
                + "\"characters\":" + orNull(b.getCharactersJson()) + ","
                + "\"locations\":" + orNull(b.getLocationsJson()) + ","
                + "\"objects\":" + orNull(b.getObjectsJson()) + ","
                + "\"continuityRules\":" + orNull(b.getContinuityRulesJson())
                + "}";
    }

    private String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private String orNull(String s) {
        return s == null ? "null" : s;
    }
}
