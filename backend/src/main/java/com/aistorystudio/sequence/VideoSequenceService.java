package com.aistorystudio.sequence;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.domain.Character;
import com.aistorystudio.domain.CharacterReference;
import com.aistorystudio.pipeline.promptbuilder.NegativePromptBuilder;
import com.aistorystudio.provider.ImageGenerationProvider;
import com.aistorystudio.provider.StorageProvider;
import com.aistorystudio.provider.VideoGenerationProvider;
import com.aistorystudio.repository.CharacterReferenceRepository;
import com.aistorystudio.repository.CharacterRepository;
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

    private final ProviderGateway providerGateway;
    private final StorageProvider storage;
    private final CharacterRepository characterRepository;
    private final CharacterReferenceRepository characterReferenceRepository;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final NegativePromptBuilder negativePromptBuilder = new NegativePromptBuilder();
    private final Map<UUID, VideoSequence> sequences = new ConcurrentHashMap<>();
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final Duration retention;

    public VideoSequenceService(ProviderGateway providerGateway, StorageProvider storage,
                                CharacterRepository characterRepository,
                                CharacterReferenceRepository characterReferenceRepository,
                                @Value("${studio.video-sequence.retention-hours:72}") long retentionHours) {
        this.providerGateway = providerGateway;
        this.storage = storage;
        this.characterRepository = characterRepository;
        this.characterReferenceRepository = characterReferenceRepository;
        this.retention = Duration.ofHours(retentionHours);
    }

    // ------------------------------------------------------------------ views

    public record SceneView(int index, String visual, String motion, SceneStep step, String error,
                            boolean hasKeyframe, boolean hasClip, boolean uploadedKeyframe,
                            Double clipSeconds, Double requestedSeconds, Long videoMillis,
                            long keyframeStamp, long clipStamp) {}

    public record SequenceView(UUID id, String title, String style, List<UUID> characterIds, String engine,
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
            scenes.add(new SceneView(sc.index, sc.visual, sc.motion, sc.step, sc.error, hasKf, hasClip,
                    sc.uploadedKeyframe, sc.clipSeconds, sc.requestedSeconds, sc.videoMillis,
                    stamp(kf), stamp(clip)));
        }
        Path merged = file(s, s.mergedFile);
        boolean hasMerged = merged != null && Files.isRegularFile(merged);
        return new SequenceView(s.id, s.title, s.style, s.characterIds, s.engine, s.secondsPerScene, s.orientation,
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
        if (any) {
            try {
                s.status = SequenceStatus.MERGING;
                save(s);
                merge(s);
            } catch (Exception e) {
                s.error = "Merge failed: " + message(e) + " (the clips are fine - press Merge to retry)";
                log.warn("Sequence {} merge failed", s.id, e);
            }
        }
        s.status = all ? (s.error == null ? SequenceStatus.COMPLETED : SequenceStatus.PARTIAL)
                : (any ? SequenceStatus.PARTIAL : SequenceStatus.FAILED);
        if (!all && s.error == null) {
            s.error = "Some scenes failed - see the red scenes, then press Retry.";
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
        // CHAIN: scene n starts from the last frame of scene n-1. Scene 1 (or a scene whose
        // predecessor failed) starts from a normal locked-character keyframe.
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
        if (!keyframeExists(s, sc)) {
            makeKeyframe(s, sc);
        }
        sc.step = SceneStep.VIDEO_RUNNING;
        save(s);

        String workflow = workflowFor(s.engine);
        double max = providerGateway.maxVideoDurationSecondsFor(workflow);
        double seconds = max > 0 ? Math.min(s.secondsPerScene, max) : s.secondsPerScene;
        String prompt = videoPrompt(s, sc);
        long started = System.currentTimeMillis();
        VideoGenerationProvider.VideoGenerationResult result;
        try {
            result = generate(s, sc, workflow, prompt, seconds);
            sc.requestedSeconds = seconds;
        } catch (RuntimeException e) {
            // A 10 s H3 clip is heavy for a 16 GB card. On an out-of-memory failure retry once at 5 s
            // so the sequence still completes (the merge copes with clips of different length).
            if (seconds > 5.0 && looksLikeOutOfMemory(e)) {
                log.warn("Sequence {} scene {}: out of memory at {}s, retrying at 5s", s.id, sc.index + 1, seconds);
                result = generate(s, sc, workflow, prompt, 5.0);
                sc.requestedSeconds = 5.0;
            } else {
                throw e;
            }
        }
        String fileName = String.format(Locale.ROOT, "scene-%02d.%s", sc.index + 1, result.fileExtension());
        deleteFile(s, sc.clipFile);
        Path stored = storage.store(rel(s, fileName), result.videoBytes());
        sc.clipFile = fileName;
        sc.videoSeed = result.seedUsed();
        sc.videoMillis = System.currentTimeMillis() - started;
        try {
            sc.clipSeconds = ClipMerger.probeDuration(stored);
        } catch (RuntimeException e) {
            sc.clipSeconds = null;
        }
        sc.step = SceneStep.DONE;
        save(s);
    }

    private VideoGenerationProvider.VideoGenerationResult generate(VideoSequence s, SequenceScene sc, String workflow,
                                                                   String prompt, double seconds) {
        boolean h3 = "MINIMAX_H3".equals(s.engine);
        // H3 follows the start image's orientation by itself; Wan needs an explicit canvas for 16:9.
        int w = 0;
        int h = 0;
        if (s.horizontal() && !h3) {
            w = "WAN_2_2".equals(s.engine) ? 1280 : 832;
            h = "WAN_2_2".equals(s.engine) ? 704 : 480;
        }
        return providerGateway.generateVideo(new VideoGenerationProvider.VideoGenerationRequest(
                file(s, sc.keyframeFile).toString(), prompt, null, seconds, w, h, workflow, null,
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), 0));
    }

    private void merge(VideoSequence s) {
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
        return motion + " Keep the characters' faces, outfits, colors and proportions exactly as in the first frame; "
                + "the background and lighting stay consistent.";
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
            Files.writeString(dir.resolve("sequence.json"), mapper.writeValueAsString(s));
        } catch (Exception e) {
            log.warn("Could not save sequence manifest {}: {}", s.id, e.getMessage());
        }
    }

    /** Reload sequences after a backend restart. A step that was running is shown as interrupted. */
    @PostConstruct
    void loadExisting() {
        Path root = storage.resolve(ROOT);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(d -> {
                Path manifest = d.resolve("sequence.json");
                if (!Files.isRegularFile(manifest)) {
                    return;
                }
                try {
                    VideoSequence s = mapper.readValue(manifest.toFile(), VideoSequence.class);
                    if (s.status == SequenceStatus.KEYFRAMES_RUNNING || s.status == SequenceStatus.VIDEOS_RUNNING
                            || s.status == SequenceStatus.MERGING) {
                        s.status = s.scenes.stream().anyMatch(sc -> clipExists(s, sc))
                                ? SequenceStatus.PARTIAL : SequenceStatus.AWAITING_APPROVAL;
                        s.error = "Interrupted by a restart - press Retry / Start videos to continue; "
                                + "finished scenes are kept.";
                        for (SequenceScene sc : s.scenes) {
                            if (sc.step == SceneStep.KEYFRAME_RUNNING || sc.step == SceneStep.VIDEO_RUNNING) {
                                sc.step = keyframeExists(s, sc) ? SceneStep.KEYFRAME_READY : SceneStep.PENDING;
                            }
                        }
                    }
                    s.cancelRequested = false;
                    sequences.put(s.id, s);
                } catch (Exception e) {
                    log.warn("Skipping unreadable sequence manifest {}: {}", manifest, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not scan {}: {}", root, e.getMessage());
        }
    }

    @Scheduled(fixedRate = 60 * 60 * 1000L)
    void cleanup() {
        Instant cutoff = Instant.now().minus(retention);
        sequences.values().removeIf(s -> {
            boolean expired = Instant.ofEpochMilli(s.createdAtMs).isBefore(cutoff) && !running.contains(s.id);
            if (expired) {
                deleteDir(dir(s));
            }
            return expired;
        });
    }
}
