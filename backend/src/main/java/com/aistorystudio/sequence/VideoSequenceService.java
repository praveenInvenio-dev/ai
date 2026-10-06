package com.aistorystudio.sequence;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.domain.Character;
import com.aistorystudio.domain.CharacterReference;
import com.aistorystudio.domain.Asset;
import com.aistorystudio.domain.Episode;
import com.aistorystudio.domain.Scene;
import com.aistorystudio.domain.VideoSequenceRecord;
import com.aistorystudio.domain.enums.AssetType;
import com.aistorystudio.pipeline.promptbuilder.NegativePromptBuilder;
import com.aistorystudio.provider.ImageGenerationProvider;
import com.aistorystudio.provider.StorageProvider;
import com.aistorystudio.provider.VideoGenerationProvider;
import com.aistorystudio.repository.CharacterReferenceRepository;
import com.aistorystudio.repository.CharacterRepository;
import com.aistorystudio.repository.AssetRepository;
import com.aistorystudio.repository.EpisodeRepository;
import com.aistorystudio.repository.SceneRepository;
import com.aistorystudio.repository.VideoSequenceRecordRepository;
import com.aistorystudio.sequence.SequenceModels.SceneStep;
import com.aistorystudio.sequence.SequenceModels.SequenceScene;
import com.aistorystudio.sequence.SequenceModels.SequenceStatus;
import com.aistorystudio.sequence.SequenceModels.VideoSequence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Multi-scene video "sequence": N scenes -> N consistent clips, one by one -> one long video.
 *
 * Consistency comes from the SAME mechanism the story pipeline uses for images: each scene's
 * keyframe is drawn by Qwen Image 2.1 with the locked character reference(s) passed natively
 * (&lt;image1&gt;/&lt;image2&gt;), so every keyframe shows the same faces/outfits/style. The video
 * model (H3 / Wan) then only animates that keyframe. Optional CHAIN continuity starts each
 * scene from the last frame of the previous clip (smoother, but drift can build up).
 *
 * Flow: keyframes (fast) -> optional review pause -> clips one by one (GPU, slow) -> merge.
 * Every step is saved to disk (sequence.json + files) so a finished scene is never redone.
 */
@Service
public class VideoSequenceService {

    private static final Logger log = LoggerFactory.getLogger(VideoSequenceService.class);
    private static final Set<String> ENGINES = Set.of("WAN_2_2", "WAN_2_2_14B", "MINIMAX_H3");
    private static final int MAX_SCENES = 12;
    private static final String ROOT = "video-sequences";
    /** H3 visual-shot target on the 16 GB profile. Long scenes are composed from
     * several continuous shots; narration duration is never reduced to this value. */
    @org.springframework.beans.factory.annotation.Value("${studio.animation.local-ai.minimax-h3-shot-seconds:8.0}")
    private double h3ShotSeconds;

    private final ProviderGateway providerGateway;
    private final StorageProvider storage;
    private final CharacterRepository characterRepository;
    private final CharacterReferenceRepository characterReferenceRepository;
    private final AssetRepository assetRepository;
    private final EpisodeRepository episodeRepository;
    private final SceneRepository sceneRepository;
    private final VideoSequenceRecordRepository sequenceRecordRepository;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final NegativePromptBuilder negativePromptBuilder = new NegativePromptBuilder();
    private final Map<UUID, VideoSequence> sequences = new ConcurrentHashMap<>();
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final Duration retention;

    public VideoSequenceService(ProviderGateway providerGateway, StorageProvider storage,
                                CharacterRepository characterRepository,
                                CharacterReferenceRepository characterReferenceRepository,
                                AssetRepository assetRepository, EpisodeRepository episodeRepository,
                                SceneRepository sceneRepository, VideoSequenceRecordRepository sequenceRecordRepository,
                                @Value("${studio.video-sequence.retention-hours:24}") long retentionHours) {
        this.providerGateway = providerGateway;
        this.storage = storage;
        this.characterRepository = characterRepository;
        this.characterReferenceRepository = characterReferenceRepository;
        this.assetRepository = assetRepository;
        this.episodeRepository = episodeRepository;
        this.sceneRepository = sceneRepository;
        this.sequenceRecordRepository = sequenceRecordRepository;
        this.retention = Duration.ofHours(retentionHours);
    }

    // ------------------------------------------------------------------ views

    public record SceneView(int index, UUID sceneId, String visual, String motion, String narration, String dialogue, String audioSpecJson, String musicPreset, String language, SceneStep step, String error,
                            boolean hasKeyframe, boolean hasClip, boolean uploadedKeyframe,
                            Double clipSeconds, Double requestedSeconds, Long videoMillis,
                            long keyframeStamp, long clipStamp) {}

    public record SequenceView(UUID id, UUID projectId, UUID episodeId, String title, String style, List<UUID> characterIds, String engine,
                               double secondsPerScene, String orientation, double crossfadeSeconds,
                               String continuity, boolean reviewKeyframes, SequenceStatus status, String error,
                               boolean busy, boolean hasMerged, Double mergedSeconds, long mergedStamp,
                               int doneScenes, int totalScenes, Instant createdAt, List<SceneView> scenes) {}

    public record SceneInput(String visual, String motion) {}

    public record CreateRequest(String title, String style, List<UUID> characterIds, String engine,
                                Double secondsPerScene, String orientation, Double crossfadeSeconds,
                                String continuity, Boolean reviewKeyframes, List<SceneInput> scenes) {}

    public record Status(boolean available, String reason, double wanMaxSeconds, double wan14bMaxSeconds,
                         double h3MaxSeconds, int maxScenes) {}

    public Status status() {
        return new Status(providerGateway.isLocalAiVideoAvailable(), providerGateway.localAiVideoUnavailableReason(),
                providerGateway.maxVideoDurationSecondsFor("wan-ti2v-5b-image-to-video"),
                providerGateway.maxVideoDurationSecondsFor("wan22-i2v-a14b"),
                providerGateway.maxVideoDurationSecondsFor("minimax-h3-image-to-video"), MAX_SCENES);
    }

    public SequenceView view(UUID id) {
        return toView(require(id));
    }

    public List<SequenceView> list() {
        return sequences.values().stream()
                .sorted(Comparator.comparingLong((VideoSequence s) -> s.createdAtMs).reversed())
                .map(this::toView).toList();
    }

    private SequenceView toView(VideoSequence s) {
        List<SceneView> scenes = new ArrayList<>();
        int done = 0;
        for (SequenceScene sc : s.scenes) {
            Path kf = file(s, sc.keyframeFile);
            Path clip = file(s, sc.clipFile);
            boolean hasKf = kf != null && Files.isRegularFile(kf);
            boolean hasClip = clip != null && Files.isRegularFile(clip);
            if (hasClip) {
                done++;
            }
            scenes.add(new SceneView(sc.index, sc.sceneId, sc.visual, sc.motion, sc.narration, sc.dialogue,
                    sc.audioSpecJson, sc.musicPreset, sc.language, sc.step, sc.error, hasKf, hasClip,
                    sc.uploadedKeyframe, sc.clipSeconds, sc.requestedSeconds, sc.videoMillis,
                    stamp(kf), stamp(clip)));
        }
        Path merged = file(s, s.mergedFile);
        boolean hasMerged = merged != null && Files.isRegularFile(merged);
        return new SequenceView(s.id, s.projectId, s.episodeId, s.title, s.style, s.characterIds, s.engine, s.secondsPerScene, s.orientation,
                s.crossfadeSeconds, s.continuity, s.reviewKeyframes, s.status, s.error, running.contains(s.id),
                hasMerged, s.mergedSeconds, stamp(merged), done, s.scenes.size(), Instant.ofEpochMilli(s.createdAtMs), scenes);
    }

    // ----------------------------------------------------------------- create

    public VideoSequence create(CreateRequest req) {
        if (req == null || req.scenes() == null || req.scenes().isEmpty()) {
            throw new IllegalArgumentException("Add at least one scene.");
        }
        if (req.scenes().size() > MAX_SCENES) {
            throw new IllegalArgumentException("A sequence can have at most " + MAX_SCENES + " scenes.");
        }
        String engine = req.engine() == null ? "MINIMAX_H3" : req.engine().trim().toUpperCase(Locale.ROOT);
        if (!ENGINES.contains(engine)) {
            throw new IllegalArgumentException("Unsupported engine: " + req.engine());
        }
        String reason = providerGateway.localAiVideoUnavailableReason();
        if (reason != null) {
            throw new IllegalStateException("Video generation is not available: " + reason);
        }
        VideoSequence s = new VideoSequence();
        s.id = UUID.randomUUID();
        s.title = blankTo(req.title(), "Untitled sequence");
        s.style = blankTo(req.style(), "");
        s.engine = engine;
        double max = providerGateway.maxVideoDurationSecondsFor(workflowFor(engine));
        double wanted = req.secondsPerScene() == null ? 5.0 : req.secondsPerScene();
        s.secondsPerScene = Math.max(2.0, max > 0 ? Math.min(wanted, max) : wanted);
        s.orientation = "horizontal".equalsIgnoreCase(req.orientation()) ? "horizontal" : "vertical";
        s.crossfadeSeconds = Math.max(0, Math.min(1.5, req.crossfadeSeconds() == null ? 0.4 : req.crossfadeSeconds()));
        s.continuity = "CHAIN".equalsIgnoreCase(req.continuity()) ? "CHAIN" : "KEYFRAMES";
        s.reviewKeyframes = req.reviewKeyframes() == null || req.reviewKeyframes();
        if (req.characterIds() != null) {
            for (UUID cid : req.characterIds()) {
                characterRepository.findById(cid).orElseThrow(
                        () -> new IllegalArgumentException("Character not found: " + cid));
                s.characterIds.add(cid);
            }
        }
        int i = 0;
        for (SceneInput in : req.scenes()) {
            if (in == null || in.visual() == null || in.visual().isBlank()) {
                throw new IllegalArgumentException("Scene " + (i + 1) + " needs a description.");
            }
            SequenceScene sc = new SequenceScene();
            sc.index = i++;
            sc.visual = in.visual().trim();
            sc.motion = in.motion() == null ? "" : in.motion().trim();
            s.scenes.add(sc);
        }
        sequences.put(s.id, s);
        save(s);
        return s;
    }

    // ---------------------------------------------------------- story loading

    public VideoSequence createFromEpisode(UUID episodeId, String engine, Double secondsPerScene, String orientation,
                                           Double crossfadeSeconds, String continuity, Boolean reviewKeyframes) {
        Episode ep = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Story not found: " + episodeId));
        List<Scene> dbScenes = sceneRepository.findByEpisodeIdOrderByOrderIndexAsc(episodeId);
        if (dbScenes.isEmpty()) throw new IllegalArgumentException("This story has no scenes yet.");
        String normalizedEngine = engine == null ? "MINIMAX_H3" : engine.trim().toUpperCase(Locale.ROOT);
        if (!ENGINES.contains(normalizedEngine)) throw new IllegalArgumentException("Unsupported video engine: " + engine);
        // Existing stories are narration-duration driven. Never let the generic
        // UI 5s value become the story scene duration. H3 is a visual-shot
        // limit; long narration is split into continuous <=8s shots later.
        double max = providerGateway.maxVideoDurationSecondsFor(workflowFor(normalizedEngine));
        double wanted = secondsPerScene == null ? 8.0 : secondsPerScene;
        if (dbScenes.stream().anyMatch(sc -> (sc.getNarrationSeconds() != null && sc.getNarrationSeconds() > 0)
                || (sc.getImageDurationSeconds() != null && sc.getImageDurationSeconds() > 0))) {
            wanted = 8.0;
        }

        VideoSequence s = new VideoSequence();
        s.id = UUID.randomUUID(); s.projectId = ep.getProjectId(); s.episodeId = ep.getId();
        s.title = blankTo(ep.getTitle(), "Story sequence"); s.style = blankTo(ep.getVisualStyle(), ""); s.engine = normalizedEngine;
        s.secondsPerScene = Math.max(2.0, max > 0 ? Math.min(wanted, max) : wanted);
        s.orientation = "horizontal".equalsIgnoreCase(orientation) ? "horizontal" : "vertical";
        s.crossfadeSeconds = Math.max(0, Math.min(1.5, crossfadeSeconds == null ? 0.4 : crossfadeSeconds));
        s.continuity = "CHAIN".equalsIgnoreCase(continuity) ? "CHAIN" : "KEYFRAMES";
        s.reviewKeyframes = false; s.status = SequenceStatus.AWAITING_APPROVAL;

        List<Character> chars = new ArrayList<>();
        if (ep.getUniverseId() != null) chars.addAll(characterRepository.findByUniverseId(ep.getUniverseId()));
        else chars.addAll(characterRepository.findByEpisodeId(ep.getId()));
        s.characterIds.addAll(chars.stream().map(Character::getId).toList());

        int i = 0;
        for (Scene sc : dbScenes) {
            SequenceScene out = new SequenceScene();
            out.index = i++; out.sceneId = sc.getId();
            out.visual = sc.getImagePrompt() != null && !sc.getImagePrompt().isBlank() ? sc.getImagePrompt() : blankTo(sc.getAction(), "Scene " + i);
            out.motion = blankTo(sc.getMotionPrompt(), "Natural cinematic movement");
            out.narration = blankTo(sc.getNarration(), "");
            out.dialogue = dialogueFrom(sc); out.audioSpecJson = sc.getAudioSpecJson();
            out.musicPreset = ep.getMusicPreset(); out.language = ep.getLanguage();
            // The story's measured TTS/narration duration is authoritative.
            // A 16s scene remains 16s; H3 divides the visual into <=8s shots.
            out.requestedSeconds = storySceneDuration(sc);
            snapshotAsset(s, sc, AssetType.IMAGE, String.format(Locale.ROOT, "source-image-%02d.png", out.index + 1), true, out);
            snapshotAsset(s, sc, AssetType.AUDIO_NARRATION, String.format(Locale.ROOT, "source-audio-%02d.wav", out.index + 1), false, out);
            s.scenes.add(out);
        }
        sequences.put(s.id, s); save(s); return s;
    }

    private String dialogueFrom(Scene sc) {
        try {
            if (sc.getVoiceSegmentsJson() == null) return "";
            var arr = mapper.readTree(sc.getVoiceSegmentsJson()); List<String> lines = new ArrayList<>();
            if (arr.isArray()) for (var n : arr) {
                String speaker = n.path("character").asText("Speaker"); String text = n.path("text").asText("");
                if (!text.isBlank() && !"Narrator".equalsIgnoreCase(speaker)) lines.add(speaker + ": " + text);
            }
            return String.join("\n", lines);
        } catch (Exception e) { return ""; }
    }

    private void snapshotAsset(VideoSequence s, Scene sc, AssetType type, String name, boolean image, SequenceScene out) {
        Asset a = assetRepository.findFirstBySceneIdAndAssetTypeAndActiveTrueOrderByVersionDesc(sc.getId(), type).orElse(null);
        if (a == null) return; Path source = Path.of(a.getFilePath());
        if (!Files.isRegularFile(source)) return;
        try {
            storage.store(rel(s, name), Files.readAllBytes(source));
            if (image) { out.keyframeFile = name; out.sourceImageFile = name; out.uploadedKeyframe = true; out.step = SceneStep.KEYFRAME_READY; }
            else out.sourceAudioFile = name;
        } catch (IOException e) { log.warn("Could not snapshot {} for scene {}: {}", type, sc.getId(), e.getMessage()); }
    }

    // ---------------------------------------------------------- async actions

    /** Phase 1: keyframes (all scenes, or only scene 1 in CHAIN mode), then pause or continue. */
    @Async("videoSequenceExecutor")
    public void runKeyframesAsync(UUID id) {
        VideoSequence s = sequences.get(id);
        if (s == null || !running.add(id)) {
            return;
        }
        try {
            s.status = SequenceStatus.KEYFRAMES_RUNNING;
            s.error = null;
            save(s);
            for (SequenceScene sc : s.scenes) {
                if (s.cancelRequested) {
                    finishCancelled(s);
                    return;
                }
                if (s.chain() && sc.index > 0) {
                    break;
                }
                if (!keyframeExists(s, sc)) {
                    try {
                        makeKeyframe(s, sc);
                    } catch (Exception e) {
                        // One bad keyframe must not stop the others; it is redrawn automatically
                        // before its video, or by "Regenerate keyframe".
                        sc.step = SceneStep.FAILED;
                        sc.error = message(e);
                        log.warn("Sequence {} keyframe {} failed: {}", s.id, sc.index + 1, sc.error);
                        save(s);
                    }
                }
            }
            if (s.reviewKeyframes) {
                s.status = SequenceStatus.AWAITING_APPROVAL;
                save(s);
                return;
            }
            runVideos(s);
        } catch (Exception e) {
            fail(s, e);
        } finally {
            running.remove(id);
        }
    }

    /** Phase 2: the clips, one by one (skips scenes that already have one), then merge. */
    @Async("videoSequenceExecutor")
    public void startVideosAsync(UUID id) {
        VideoSequence s = sequences.get(id);
        if (s == null || !running.add(id)) {
            return;
        }
        try {
            runVideos(s);
        } catch (Exception e) {
            fail(s, e);
        } finally {
            running.remove(id);
        }
    }

    @Async("videoSequenceExecutor")
    public void regenerateKeyframeAsync(UUID id, int index) {
        VideoSequence s = sequences.get(id);
        if (s == null || !running.add(id)) {
            return;
        }
        try {
            SequenceScene sc = scene(s, index);
            makeKeyframe(s, sc);
            invalidateClip(s, sc);
            sc.step = SceneStep.KEYFRAME_READY;
            if (s.status != SequenceStatus.AWAITING_APPROVAL) {
                s.status = SequenceStatus.PARTIAL;
            }
            save(s);
        } catch (Exception e) {
            SequenceScene sc = scene(s, index);
            sc.step = SceneStep.FAILED;
            sc.error = message(e);
            save(s);
        } finally {
            running.remove(id);
        }
    }

    @Async("videoSequenceExecutor")
    public void regenerateClipAsync(UUID id, int index) {
        VideoSequence s = sequences.get(id);
        if (s == null || !running.add(id)) {
            return;
        }
        try {
            SequenceScene sc = scene(s, index);
            invalidateClip(s, sc);
            s.status = SequenceStatus.VIDEOS_RUNNING;
            save(s);
            makeClip(s, sc);
            afterVideos(s);
        } catch (Exception e) {
            fail(s, e);
        } finally {
            running.remove(id);
        }
    }

    @Async("videoSequenceExecutor")
    public void mergeAsync(UUID id) {
        VideoSequence s = sequences.get(id);
        if (s == null || !running.add(id)) {
            return;
        }
        try {
            merge(s);
            s.status = allClips(s) ? SequenceStatus.COMPLETED : SequenceStatus.PARTIAL;
            save(s);
        } catch (Exception e) {
            s.error = "Merge failed: " + message(e);
            s.status = SequenceStatus.PARTIAL;
            save(s);
        } finally {
            running.remove(id);
        }
    }

    // ----------------------------------------------------- sync (guarded) ops

    public void requireIdle(UUID id) {
        require(id);
        if (running.contains(id)) {
            throw new IllegalStateException("This sequence is still working - wait for the current step to finish.");
        }
    }

    public void updateScene(UUID id, int index, String visual, String motion) {
        requireIdle(id);
        VideoSequence s = require(id);
        SequenceScene sc = scene(s, index);
        if (visual != null && !visual.isBlank()) {
            sc.visual = visual.trim();
        }
        if (motion != null) {
            sc.motion = motion.trim();
        }
        save(s);
    }

    public void replaceKeyframe(UUID id, int index, MultipartFile image) {
        requireIdle(id);
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("That image is empty.");
        }
        String name = image.getOriginalFilename() == null ? "" : image.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp"))) {
            throw new IllegalArgumentException("Unsupported image format. Use png, jpg, jpeg or webp.");
        }
        VideoSequence s = require(id);
        SequenceScene sc = scene(s, index);
        try {
            String ext = name.substring(name.lastIndexOf('.') + 1);
            String fileName = String.format(Locale.ROOT, "keyframe-%02d.%s", index + 1, ext);
            deleteFile(s, sc.keyframeFile);
            storage.store(rel(s, fileName), image.getBytes());
            sc.keyframeFile = fileName;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not store the keyframe", e);
        }
        sc.uploadedKeyframe = true;
        sc.keyframeSeed = null;
        invalidateClip(s, sc);
        sc.step = SceneStep.KEYFRAME_READY;
        sc.error = null;
        save(s);
    }

    public void cancel(UUID id) {
        VideoSequence s = require(id);
        s.cancelRequested = true;
        if (!running.contains(id)) {
            finishCancelled(s);
        }
        // else: the runner notices between steps (a ComfyUI call already in progress cannot be interrupted).
    }

    public void delete(UUID id) {
        VideoSequence s = require(id);
        if (running.contains(id)) {
            throw new IllegalStateException("Cancel the running sequence first.");
        }
        sequences.remove(id);
        deleteDir(dir(s));
        try { sequenceRecordRepository.deleteById(id); } catch (Exception ignored) { }
    }

    public void clearCancel(UUID id) {
        require(id).cancelRequested = false;
    }

    // ------------------------------------------------------------- file access

    public Path keyframePath(UUID id, int index) {
        VideoSequence s = require(id);
        Path p = file(s, scene(s, index).keyframeFile);
        if (p == null || !Files.isRegularFile(p)) {
            throw new IllegalArgumentException("That scene has no keyframe yet.");
        }
        return p;
    }

    public Path clipPath(UUID id, int index) {
        VideoSequence s = require(id);
        Path p = file(s, scene(s, index).clipFile);
        if (p == null || !Files.isRegularFile(p)) {
            throw new IllegalArgumentException("That scene has no video yet.");
        }
        return p;
    }

    public Path mergedPath(UUID id) {
        VideoSequence s = require(id);
        Path p = file(s, s.mergedFile);
        if (p == null || !Files.isRegularFile(p)) {
            throw new IllegalArgumentException("The merged video is not ready yet.");
        }
        return p;
    }

    // ----------------------------------------------------------- the real work

    private void runVideos(VideoSequence s) {
        s.status = SequenceStatus.VIDEOS_RUNNING;
        s.error = null;
        s.cancelRequested = false;
        save(s);
        for (SequenceScene sc : s.scenes) {
            if (s.cancelRequested) {
                finishCancelled(s);
                return;
            }
            if (clipExists(s, sc)) {
                sc.step = SceneStep.DONE;
                continue;
            }
            try {
                makeClip(s, sc);
            } catch (Exception e) {
                sc.step = SceneStep.FAILED;
                sc.error = message(e);
                log.warn("Sequence {} scene {} failed: {}", s.id, sc.index + 1, sc.error);
                save(s);
                // keep going: one bad scene should not throw away the others
            }
        }
        afterVideos(s);
    }

    private void afterVideos(VideoSequence s) {
        if (s.cancelRequested) {
            finishCancelled(s);
            return;
        }
        boolean all = allClips(s);
        boolean any = s.scenes.stream().anyMatch(sc -> clipExists(s, sc));
        if (all) {
            try {
                s.status = SequenceStatus.MERGING;
                save(s);
                merge(s);
            } catch (Exception e) {
                s.error = "Merge failed: " + message(e) + " (all scene clips are preserved - retry merge)";
                log.warn("Sequence {} merge failed", s.id, e);
            }
        }
        s.status = all ? (s.error == null ? SequenceStatus.COMPLETED : SequenceStatus.PARTIAL)
                : (any ? SequenceStatus.PARTIAL : SequenceStatus.FAILED);
        if (!all && s.error == null) {
            s.error = "Generation is incomplete. Finish all scenes before creating the final production video.";
        }
        save(s);
    }

    private void makeKeyframe(VideoSequence s, SequenceScene sc) {
        sc.step = SceneStep.KEYFRAME_RUNNING;
        sc.error = null;
        save(s);
        List<Character> chars = orderedCharacters(s);
        String ref1 = chars.size() > 0 ? referenceImageFor(chars.get(0)) : null;
        String ref2 = chars.size() > 1 && ref1 != null ? referenceImageFor(chars.get(1)) : null;
        String prompt = keyframePrompt(s, sc, chars);
        String negative = negativePromptBuilder.build(
                chars.stream().map(Character::getNegativeConstraints).filter(x -> x != null && !x.isBlank())
                        .reduce((a, b) -> a + ", " + b).orElse(null),
                "extra characters, duplicate characters, different outfit, off-model face");
        long seed = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        int w = s.horizontal() ? 1344 : 0;
        int h = s.horizontal() ? 768 : 0;
        var result = providerGateway.generateImage(new ImageGenerationProvider.ImageGenerationRequest(
                prompt, negative, w, h, 0, 0, seed, null, null, ref1, ref2));
        String fileName = String.format(Locale.ROOT, "keyframe-%02d.%s", sc.index + 1, result.fileExtension());
        deleteFile(s, sc.keyframeFile);
        storage.store(rel(s, fileName), result.imageBytes());
        sc.keyframeFile = fileName;
        sc.keyframeSeed = result.seedUsed();
        sc.uploadedKeyframe = false;
        sc.step = SceneStep.KEYFRAME_READY;
        save(s);
    }

    private void makeClip(VideoSequence s, SequenceScene sc) {
        sc.error = null;
        if (s.chain() && sc.index > 0) {
            SequenceScene prev = s.scenes.get(sc.index - 1);
            if (clipExists(s, prev)) {
                sc.step = SceneStep.KEYFRAME_RUNNING;
                save(s);
                String fileName = String.format(Locale.ROOT, "keyframe-%02d.png", sc.index + 1);
                deleteFile(s, sc.keyframeFile);
                ClipMerger.extractLastFrame(file(s, prev.clipFile), dir(s).resolve(fileName));
                sc.keyframeFile = fileName;
                sc.uploadedKeyframe = false;
            }
        }
        if (!keyframeExists(s, sc)) makeKeyframe(s, sc);
        sc.step = SceneStep.VIDEO_RUNNING;
        save(s);

        String workflow = workflowFor(s.engine);
        double seconds = requestedSceneSeconds(s, sc);
        String prompt = videoPrompt(s, sc);
        long started = System.currentTimeMillis();
        Path stored;
        long seed = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        try {
            if ("MINIMAX_H3".equals(s.engine)) {
                stored = generateContinuousH3SequenceClip(s, sc, prompt, seconds);
            } else {
                double max = providerGateway.maxVideoDurationSecondsFor(workflow);
                double requestSeconds = max > 0 ? Math.min(seconds, max) : seconds;
                VideoGenerationProvider.VideoGenerationResult result;
                try {
                    result = generate(s, sc, workflow, prompt, requestSeconds);
                } catch (RuntimeException e) {
                    if (requestSeconds > 5.0 && looksLikeOutOfMemory(e)) {
                        log.warn("Sequence {} scene {} OOM at {}s, retrying at 5s", s.id, sc.index + 1, requestSeconds);
                        result = generate(s, sc, workflow, prompt, 5.0);
                        requestSeconds = 5.0;
                    } else throw e;
                }
                Path raw = dir(s).resolve(String.format(Locale.ROOT, "scene-%02d-raw.%s", sc.index + 1, result.fileExtension()));
                Files.write(raw, result.videoBytes());
                stored = raw;
                seed = result.seedUsed();
            }

            // H3's native speech is deliberately discarded. The exact TTS narration/dialogue
            // snapshot is the authoritative audio track, which fixes multilingual pronunciation
            // and guarantees the full scene dialogue remains audible for the entire scene.
            Path finalPath = dir(s).resolve(String.format(Locale.ROOT, "scene-%02d.mp4", sc.index + 1));
            if (sc.sourceAudioFile != null && Files.isRegularFile(file(s, sc.sourceAudioFile))) {
                // Never ask FFmpeg to read and overwrite the same MP4 in one command.
                Path muxed = dir(s).resolve(String.format(Locale.ROOT, "scene-%02d-audio.mp4", sc.index + 1));
                ClipMerger.muxExternalAudio(stored, file(s, sc.sourceAudioFile), muxed, seconds);
                Files.move(muxed, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (!stored.equals(finalPath)) Files.deleteIfExists(stored);
                stored = finalPath;
            } else {
                if (!stored.equals(finalPath)) Files.move(stored, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                stored = finalPath;
            }
            if (sc.clipFile != null && !sc.clipFile.equals(stored.getFileName().toString())) deleteFile(s, sc.clipFile);
            sc.clipFile = stored.getFileName().toString();
            sc.videoSeed = seed;
            sc.requestedSeconds = seconds;
            sc.videoMillis = System.currentTimeMillis() - started;
            sc.clipSeconds = ClipMerger.probeDuration(stored);
            if (sc.clipSeconds + 0.35 < seconds) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "Scene %d rendered only %.2fs of %.2fs required.", sc.index + 1, sc.clipSeconds, seconds));
            }
            sc.step = SceneStep.DONE;
            save(s);
        } catch (RuntimeException | java.io.IOException e) {
            sc.step = SceneStep.FAILED;
            sc.error = message(e);
            save(s);
            throw new IllegalStateException(sc.error, e);
        }
    }

    private double requestedSceneSeconds(VideoSequence s, SequenceScene sc) {
        if (sc.requestedSeconds != null && sc.requestedSeconds > 0) return sc.requestedSeconds;
        double max = providerGateway.maxVideoDurationSecondsFor(workflowFor(s.engine));
        return Math.max(2.0, max > 0 ? Math.min(s.secondsPerScene, max) : s.secondsPerScene);
    }

    private double storySceneDuration(Scene sc) {
        // The actual narration WAV is the strongest source of truth. This also
        // repairs older stories whose DB duration was left at the old 5s default.
        try {
            Asset audio = assetRepository
                    .findFirstBySceneIdAndAssetTypeAndActiveTrueOrderByVersionDesc(sc.getId(), AssetType.AUDIO_NARRATION)
                    .orElse(null);
            if (audio != null && audio.getFilePath() != null && Files.isRegularFile(Path.of(audio.getFilePath()))) {
                double wavSeconds = ClipMerger.probeDuration(Path.of(audio.getFilePath()));
                if (wavSeconds > 0.2) return wavSeconds + 0.6;
            }
        } catch (Exception ignored) { }
        if (sc.getNarrationSeconds() != null && sc.getNarrationSeconds() > 0) return sc.getNarrationSeconds() + 0.6;
        if (sc.getImageDurationSeconds() != null && sc.getImageDurationSeconds() > 0) return sc.getImageDurationSeconds();
        if (sc.getNarration() != null && !sc.getNarration().isBlank()) {
            int words = sc.getNarration().trim().split("\\s+").length;
            return Math.max(3.0, Math.min(300.0, words / 2.5 + 0.6));
        }
        return 3.0;
    }

    /** Generate one long H3 scene as a chain of short visual shots. */
    private Path generateContinuousH3SequenceClip(VideoSequence s, SequenceScene sc, String prompt, double seconds) throws java.io.IOException {
        double providerMax = providerGateway.maxVideoDurationSecondsFor("minimax-h3-image-to-video");
        double maxShot = h3ShotSeconds > 0 ? h3ShotSeconds : 8.0;
        if (providerMax > 0) maxShot = Math.min(maxShot, providerMax);
        maxShot = Math.max(3.0, maxShot);
        Path work = Files.createTempDirectory("sequence-h3-" + s.id + "-" + sc.index + "-");
        try {
            List<Path> shots = new ArrayList<>();
            Path currentImage = file(s, sc.keyframeFile);
            double remaining = seconds;
            int index = 0;
            while (remaining > 0.20 && index < 64) {
                double requested = Math.min(maxShot, remaining);
                String continuation = prompt
                        + "\n\nVISUAL CONTINUITY CONTRACT: continuation shot " + (index + 1)
                        + ". The starting frame is the exact final frame of the previous shot. "
                        + "Continue from it without resetting the environment. Preserve exact face identity, "
                        + "hair, clothing, body proportions, props, architecture, road/ground layout, sky, "
                        + "weather, time of day, lighting direction, shadows, color palette and atmosphere. "
                        + "Do not introduce unrelated objects or a different background. No subtitles, no captions, "
                        + "no invented speech. Final audio is supplied separately by the TTS track."
                        + (index == 0 ? "" : " Keep the same camera language and action trajectory as the prior shot.");
                VideoGenerationProvider.VideoGenerationResult result;
                try {
                    result = generateFromImage(s, currentImage, continuation, requested, workflowFor(s.engine));
                } catch (RuntimeException e) {
                    if (requested > 5.0 && looksLikeOutOfMemory(e)) {
                        result = generateFromImage(s, currentImage, continuation, 5.0, workflowFor(s.engine));
                    } else throw e;
                }
                Path raw = work.resolve(String.format(Locale.ROOT, "shot-%03d.%s", index, result.fileExtension()));
                Files.write(raw, result.videoBytes());
                double actual = ClipMerger.probeDuration(raw);
                if (actual <= 0.2) throw new IllegalStateException("H3 returned an unusable shot.");
                shots.add(raw);
                remaining -= actual;
                if (remaining > 0.20) currentImage = ClipMerger.extractLastFrame(raw, work.resolve(String.format(Locale.ROOT, "frame-%03d.png", index)));
                index++;
            }
            if (shots.isEmpty()) throw new IllegalStateException("H3 produced no shots.");
            Path merged = work.resolve("scene-visual.mp4");
            ClipMerger.merge(shots, merged, s.horizontal() ? 1920 : 1080, s.horizontal() ? 1080 : 1920, 0);
            Path finalPath = dir(s).resolve(String.format(Locale.ROOT, "scene-%02d.mp4", sc.index + 1));
            Files.copy(merged, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return finalPath;
        } finally {
            try (var walk = Files.walk(work)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
        }
    }

    private VideoGenerationProvider.VideoGenerationResult generateFromImage(VideoSequence s, Path image, String prompt,
                                                                              double seconds, String workflow) {
        boolean h3 = "MINIMAX_H3".equals(s.engine);
        int w = 0, h = 0;
        if (s.horizontal() && !h3) {
            w = "WAN_2_2".equals(s.engine) ? 1280 : 832;
            h = "WAN_2_2".equals(s.engine) ? 704 : 480;
        }
        return providerGateway.generateVideo(new VideoGenerationProvider.VideoGenerationRequest(
                image.toString(), prompt, null, seconds, w, h, workflow, null,
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), 0));
    }

    private VideoGenerationProvider.VideoGenerationResult generate(VideoSequence s, SequenceScene sc, String workflow,
                                                                   String prompt, double seconds) {
        return generateFromImage(s, file(s, sc.keyframeFile), prompt, seconds, workflow);
    }

    private void merge(VideoSequence s) {
        if (!allClips(s)) throw new IllegalStateException("Final merge requires every scene video to be completed.");
        List<Path> clips = new ArrayList<>();
        for (SequenceScene sc : s.scenes) {
            if (clipExists(s, sc)) {
                clips.add(file(s, sc.clipFile));
            }
        }
        int w = s.horizontal() ? 1920 : 1080;
        int h = s.horizontal() ? 1080 : 1920;
        Path out = dir(s).resolve("final.mp4");
        ClipMerger.merge(clips, out, w, h, s.crossfadeSeconds);
        s.mergedFile = "final.mp4";
        try {
            s.mergedSeconds = ClipMerger.probeDuration(out);
        } catch (RuntimeException e) {
            s.mergedSeconds = null;
        }
        s.error = null;
        save(s);
    }

    // ----------------------------------------------------------------- prompts

    private String keyframePrompt(VideoSequence s, SequenceScene sc, List<Character> chars) {
        StringBuilder b = new StringBuilder();
        if (!chars.isEmpty()) {
            b.append(chars.stream().map(c -> c.getName() + " (" + cut(c.getCanonicalDescription(), 380) + ")")
                    .reduce((a, c) -> a + " and " + c).orElse("")).append(". ");
        }
        b.append(sc.visual.trim());
        if (!sc.visual.trim().endsWith(".")) {
            b.append('.');
        }
        if (!s.style.isBlank()) {
            b.append(" Style: ").append(s.style.trim()).append('.');
        }
        b.append(" Cinematic keyframe, characters clearly visible with consistent faces and outfits, "
                + "richly detailed environment and background, sharp focus, natural lighting.");
        return b.toString();
    }

    private String videoPrompt(VideoSequence s, SequenceScene sc) {
        String motion = sc.motion == null || sc.motion.isBlank()
                ? "Natural, subtle character motion and a slow, smooth camera move."
                : sc.motion.trim();
        // CHAIN: the keyframe is just the previous clip's last frame, so the scene text must drive the story.
        if (s.chain() && sc.index > 0) {
            motion = sc.visual.trim() + ". " + motion;
        }
        StringBuilder p = new StringBuilder(motion)
                .append(" Keep the characters' faces, outfits, colors and proportions exactly as in the first frame; the background and lighting stay consistent.");
        if (s.engine.equals("MINIMAX_H3")) {
            if (sc.language != null && !sc.language.isBlank()) p.append("\nLANGUAGE CONTEXT: ").append(sc.language).append(". ");
            p.append("\nAUDIO OWNERSHIP: generate visuals only. Do not invent dialogue, narration, subtitles or captions. ");
            p.append("The final scene uses the exact saved multilingual TTS/dialogue WAV, including its natural pauses and emotional delivery. ");
            p.append("VISUAL CONTINUITY: preserve the same environment, background architecture, lighting, weather, props, color palette and atmosphere throughout this scene. ");
            if (sc.audioSpecJson != null && !sc.audioSpecJson.isBlank()) p.append("Visual/environment cues from the scene audio plan: ").append(sc.audioSpecJson);
        }
        return p.toString();
    }

    // ------------------------------------------------------------ small helpers

    private List<Character> orderedCharacters(VideoSequence s) {
        List<Character> withRef = new ArrayList<>();
        List<Character> without = new ArrayList<>();
        for (UUID id : s.characterIds) {
            characterRepository.findById(id).ifPresent(c -> {
                if (referenceImageFor(c) != null) {
                    withRef.add(c);
                } else {
                    without.add(c);
                }
            });
        }
        withRef.addAll(without);
        return withRef;
    }

    /** Same pick order the story pipeline uses: locked, else primary, else newest. */
    private String referenceImageFor(Character c) {
        List<CharacterReference> refs = characterReferenceRepository.findByCharacterId(c.getId());
        if (refs.isEmpty()) {
            return null;
        }
        CharacterReference chosen = refs.stream().filter(CharacterReference::isLocked).findFirst()
                .orElseGet(() -> refs.stream().filter(CharacterReference::isPrimary).findFirst()
                        .orElseGet(() -> refs.stream()
                                .max(Comparator.comparing(CharacterReference::getCreatedAt)).orElse(refs.get(0))));
        Path path = Path.of(chosen.getImagePath());
        return Files.isRegularFile(path) && path.toFile().length() > 0 ? path.toString() : null;
    }

    static String workflowFor(String engine) {
        return switch (engine) {
            case "WAN_2_2" -> "wan-ti2v-5b-image-to-video";
            case "WAN_2_2_14B" -> "wan22-i2v-a14b";
            default -> "minimax-h3-image-to-video";
        };
    }

    private static boolean looksLikeOutOfMemory(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (m.contains("out of memory") || m.contains("oom") || m.contains("cuda error")
                    || m.contains("allocat") || m.contains("killed")) {
                return true;
            }
        }
        return false;
    }

    private void invalidateClip(VideoSequence s, SequenceScene sc) {
        deleteFile(s, sc.clipFile);
        sc.clipFile = null;
        sc.clipSeconds = null;
        sc.videoMillis = null;
        sc.requestedSeconds = null;
        if (sc.step == SceneStep.DONE) {
            sc.step = SceneStep.KEYFRAME_READY;
        }
    }

    private boolean keyframeExists(VideoSequence s, SequenceScene sc) {
        Path p = file(s, sc.keyframeFile);
        return p != null && Files.isRegularFile(p);
    }

    private boolean clipExists(VideoSequence s, SequenceScene sc) {
        Path p = file(s, sc.clipFile);
        return p != null && Files.isRegularFile(p);
    }

    private boolean allClips(VideoSequence s) {
        return s.scenes.stream().allMatch(sc -> clipExists(s, sc));
    }

    private void fail(VideoSequence s, Exception e) {
        log.error("Video sequence {} failed", s.id, e);
        s.error = message(e);
        s.status = s.scenes.stream().anyMatch(sc -> clipExists(s, sc)) ? SequenceStatus.PARTIAL : SequenceStatus.FAILED;
        save(s);
    }

    private void finishCancelled(VideoSequence s) {
        s.status = SequenceStatus.CANCELLED;
        s.cancelRequested = false;
        for (SequenceScene sc : s.scenes) {
            if (sc.step == SceneStep.KEYFRAME_RUNNING || sc.step == SceneStep.VIDEO_RUNNING) {
                sc.step = keyframeExists(s, sc) ? SceneStep.KEYFRAME_READY : SceneStep.PENDING;
            }
        }
        save(s);
    }

    private VideoSequence require(UUID id) {
        VideoSequence s = sequences.get(id);
        if (s == null) {
            throw new IllegalArgumentException("Video sequence not found: " + id);
        }
        return s;
    }

    private static SequenceScene scene(VideoSequence s, int index) {
        if (index < 0 || index >= s.scenes.size()) {
            throw new IllegalArgumentException("No scene " + (index + 1) + " in this sequence.");
        }
        return s.scenes.get(index);
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static String cut(String v, int max) {
        if (v == null) {
            return "";
        }
        String t = v.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m != null ? m : e.getClass().getSimpleName();
    }

    private static long stamp(Path p) {
        try {
            return p != null && Files.isRegularFile(p) ? Files.getLastModifiedTime(p).toMillis() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private String rel(VideoSequence s, String name) {
        return ROOT + "/" + s.id + "/" + name;
    }

    private Path dir(VideoSequence s) {
        return storage.resolve(ROOT + "/" + s.id);
    }

    private Path file(VideoSequence s, String name) {
        return name == null ? null : dir(s).resolve(name).normalize();
    }

    private void deleteFile(VideoSequence s, String name) {
        Path p = file(s, name);
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.warn("Could not delete {}: {}", p, e.getMessage());
            }
        }
    }

    private void deleteDir(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // nothing to delete
        }
    }

    private synchronized void save(VideoSequence s) {
        try {
            Path dir = dir(s);
            Files.createDirectories(dir);
            String json = mapper.writeValueAsString(s);
            Files.writeString(dir.resolve("sequence.json"), json);
            VideoSequenceRecord r = sequenceRecordRepository.findById(s.id).orElseGet(VideoSequenceRecord::new);
            r.setId(s.id); r.setProjectId(s.projectId); r.setEpisodeId(s.episodeId);
            r.setCreatedAt(Instant.ofEpochMilli(s.createdAtMs)); r.setUpdatedAt(Instant.now());
            r.setExpiresAt(Instant.ofEpochMilli(s.createdAtMs).plus(retention)); r.setStatus(s.status.name()); r.setManifestJson(json);
            sequenceRecordRepository.save(r);
        } catch (Exception e) {
            log.warn("Could not save sequence manifest {}: {}", s.id, e.getMessage());
        }
    }

    /** Reload sequences after a backend restart. A step that was running is shown as interrupted. */
    @PostConstruct
    void loadExisting() {
        try {
            for (VideoSequenceRecord r : sequenceRecordRepository.findAllByOrderByCreatedAtDesc()) {
                try {
                    VideoSequence s = mapper.readValue(r.getManifestJson(), VideoSequence.class);
                    if (s.status == SequenceStatus.KEYFRAMES_RUNNING || s.status == SequenceStatus.VIDEOS_RUNNING || s.status == SequenceStatus.MERGING) {
                        s.status = s.scenes.stream().anyMatch(sc -> clipExists(s, sc)) ? SequenceStatus.PARTIAL : SequenceStatus.AWAITING_APPROVAL;
                        s.error = "Interrupted by a restart/disconnect - press Retry / Start videos to continue; finished scenes are kept.";
                        for (SequenceScene sc : s.scenes) if (sc.step == SceneStep.KEYFRAME_RUNNING || sc.step == SceneStep.VIDEO_RUNNING) sc.step = keyframeExists(s, sc) ? SceneStep.KEYFRAME_READY : SceneStep.PENDING;
                    }
                    s.cancelRequested = false; sequences.put(s.id, s);
                } catch (Exception e) { log.warn("Skipping DB sequence {}: {}", r.getId(), e.getMessage()); }
            }
        } catch (Exception e) { log.warn("Could not restore video sequences from DB: {}", e.getMessage()); }
    }

    @Scheduled(fixedRate = 60 * 60 * 1000L)
    void cleanup() {
        Instant cutoff = Instant.now().minus(retention);
        sequences.values().removeIf(s -> {
            boolean expired = Instant.ofEpochMilli(s.createdAtMs).isBefore(cutoff) && !running.contains(s.id);
            if (expired) {
                deleteDir(dir(s));
                try { sequenceRecordRepository.deleteById(s.id); } catch (Exception ignored) { }
            }
            return expired;
        });
    }
}
