package com.aistorystudio.service;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.domain.*;
import com.aistorystudio.domain.Character;
import com.aistorystudio.domain.enums.AssetType;
import com.aistorystudio.domain.enums.EpisodeStatus;
import com.aistorystudio.domain.enums.JobStatus;
import com.aistorystudio.pipeline.promptbuilder.ImagePromptAssembler;
import com.aistorystudio.provider.ImageGenerationProvider;
import com.aistorystudio.provider.MediaProcessor;
import com.aistorystudio.provider.StorageProvider;
import com.aistorystudio.provider.TextToSpeechProvider;
import com.aistorystudio.provider.VideoGenerationProvider;
import com.aistorystudio.provider.VisionProvider;
import com.aistorystudio.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Orchestrates PHASE 5-10 of the production pipeline (spec section 27) once a draft
 * has been explicitly approved. Runs asynchronously; progress is persisted as
 * GenerationJob/GenerationStep rows and broadcast over SSE so the Production
 * Dashboard updates live. Failures are isolated per scene/step wherever possible
 * (spec section 29): a single failing scene image does not restart the whole episode.
 */
@Service
public class ProductionPipelineService {

    private static final Logger log = LoggerFactory.getLogger(ProductionPipelineService.class);

    private final EpisodeRepository episodeRepository;
    private final SceneRepository sceneRepository;
    private final AssetRepository assetRepository;
    private final StoryBibleRepository storyBibleRepository;
    private final CharacterRepository characterRepository;
    private final JobService jobService;
    private final ProviderGateway providerGateway;
    private final StorageProvider storageProvider;
    private final MediaProcessor mediaProcessor;
    private final SubtitleService subtitleService;
    private final EpisodeMemoryService episodeMemoryService;
    private final CharacterReferenceRepository characterReferenceRepository;
    private final com.aistorystudio.animation.AnimationDecisionService animationDecisionService;
    private final ImagePromptAssembler imagePromptAssembler = new ImagePromptAssembler();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${studio.comfyui.styleModels.anime:}") private String animeModel;
    @Value("${studio.comfyui.styleModels.cartoon:}") private String cartoonModel;
    @Value("${studio.comfyui.styleModels.3dAnimated:}") private String threeDAnimatedModel;
    @Value("${studio.comfyui.styleModels.storybook:}") private String storybookModel;
    @Value("${studio.comfyui.styleModels.watercolor:}") private String watercolorModel;
    @Value("${studio.comfyui.styleModels.comicBook:}") private String comicBookModel;
    @Value("${studio.comfyui.styleModels.fantasy:}") private String fantasyModel;
    @Value("${studio.quality.imageValidation.enabled:true}") private boolean imageValidationEnabled;
    @Value("${studio.quality.imageValidation.maxRetries:1}") private int imageValidationRetries;
    @Value("${studio.music.autoEnabled:true}") private boolean autoMusicEnabled;

    public ProductionPipelineService(EpisodeRepository episodeRepository, SceneRepository sceneRepository,
                                      AssetRepository assetRepository, StoryBibleRepository storyBibleRepository,
                                      CharacterRepository characterRepository, JobService jobService,
                                      ProviderGateway providerGateway, StorageProvider storageProvider,
                                      MediaProcessor mediaProcessor, SubtitleService subtitleService,
                                      EpisodeMemoryService episodeMemoryService,
                                      CharacterReferenceRepository characterReferenceRepository,
                                      com.aistorystudio.animation.AnimationDecisionService animationDecisionService) {
        this.episodeRepository = episodeRepository;
        this.sceneRepository = sceneRepository;
        this.assetRepository = assetRepository;
        this.storyBibleRepository = storyBibleRepository;
        this.characterRepository = characterRepository;
        this.jobService = jobService;
        this.providerGateway = providerGateway;
        this.storageProvider = storageProvider;
        this.mediaProcessor = mediaProcessor;
        this.subtitleService = subtitleService;
        this.characterReferenceRepository = characterReferenceRepository;
        this.animationDecisionService = animationDecisionService;
        this.episodeMemoryService = episodeMemoryService;
    }

    public GenerationJob generateImagesOnly(UUID episodeId) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        episode.setStatus(EpisodeStatus.APPROVED);
        episodeRepository.save(episode);
        GenerationJob job = jobService.createJob(episodeId);
        runImageGenerationAsync(job.getId(), episodeId);
        return job;
    }

    public GenerationJob approveAndStartProduction(UUID episodeId) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        List<Scene> scenes = sceneRepository.findByEpisodeIdOrderByOrderIndexAsc(episodeId);
        if (scenes.isEmpty()) throw new IllegalStateException("The story has no scenes.");
        boolean missing = scenes.stream().anyMatch(s -> assetRepository.findByEpisodeIdAndAssetType(episodeId, AssetType.IMAGE).stream()
                .noneMatch(a -> s.getId().equals(a.getSceneId()) && a.isActive()));
        if (missing) throw new IllegalStateException("Generate all scene images before starting the video.");
        episode.setStatus(EpisodeStatus.APPROVED);
        episodeRepository.save(episode);
        GenerationJob job = jobService.createJob(episodeId);
        runAudioVideoPipelineAsync(job.getId(), episodeId);
        return job;
    }

    @Async
    public void runImageGenerationAsync(UUID jobId, UUID episodeId) {
        try {
            Episode episode = episodeRepository.findById(episodeId).orElseThrow();
            List<Scene> scenes = sceneRepository.findByEpisodeIdOrderByOrderIndexAsc(episodeId);
            StoryBible bible = storyBibleRepository.findFirstByEpisodeIdAndActiveTrueOrderByVersionDesc(episodeId)
                    .orElseThrow(() -> new IllegalStateException("No active Story Bible for episode " + episodeId));
            List<Character> chars = episode.getUniverseId() != null ? characterRepository.findByUniverseId(episode.getUniverseId()) : List.of();
            jobService.updateStatus(jobId, JobStatus.GENERATING_IMAGES, 10);
            Map<UUID, Path> paths = generateSceneImages(jobId, episode, scenes, chars, bible);
            jobService.updateStatus(jobId, JobStatus.VALIDATING_IMAGES, 90);
            validateImages(paths);
            jobService.updateStatus(jobId, JobStatus.COMPLETED, 100);
        } catch (Exception e) {
            log.error("Image generation failed for episode {}: {}", episodeId, e.getMessage(), e);
            jobService.fail(jobId, e.getMessage());
        }
    }

    @Async
    public void runAudioVideoPipelineAsync(UUID jobId, UUID episodeId) {
        try {
            Episode episode = episodeRepository.findById(episodeId).orElseThrow();
            episode.setStatus(EpisodeStatus.IN_PRODUCTION); episodeRepository.save(episode);
            List<Scene> scenes = sceneRepository.findByEpisodeIdOrderByOrderIndexAsc(episodeId);
            StoryBible bible = storyBibleRepository.findFirstByEpisodeIdAndActiveTrueOrderByVersionDesc(episodeId).orElseThrow();
            Map<UUID, Path> images = activeImagePaths(episodeId, scenes);
            jobService.updateStatus(jobId, JobStatus.GENERATING_AUDIO, 15);
            Map<UUID, Path> audio = generateSceneAudio(jobId, episode, scenes);
            jobService.updateStatus(jobId, JobStatus.ASSEMBLING_VIDEO, 55);
            Path finalVideo = assembleVideo(episode, scenes, images, audio);
            jobService.updateStatus(jobId, JobStatus.GENERATING_THUMBNAIL, 72);
            generateThumbnail(episode, scenes, images);
            String srt = subtitleService.buildSrt(scenes);
            Path srtPath = storageProvider.store(assetRelativePath(episode, "subtitles/subtitles.srt"), srt.getBytes());
            saveAsset(episode.getId(), null, AssetType.SUBTITLE, srtPath, "internal", null, null);
            jobService.updateStatus(jobId, JobStatus.GENERATING_SHORTS, 85);
            generateShorts(episode, scenes, finalVideo);
            jobService.updateStatus(jobId, JobStatus.QUALITY_CHECK, 95);
            episode.setStatus(EpisodeStatus.PRODUCTION_COMPLETE); episodeRepository.save(episode);
            episodeMemoryService.recordMemory(episode, bible);
            jobService.updateStatus(jobId, JobStatus.COMPLETED, 100);
        } catch (Exception e) {
            log.error("Audio/video pipeline failed for episode {}: {}", episodeId, e.getMessage(), e);
            jobService.fail(jobId, e.getMessage());
            episodeRepository.findById(episodeId).ifPresent(ep -> { ep.setStatus(EpisodeStatus.FAILED); episodeRepository.save(ep); });
        }
    }

    private Map<UUID, Path> activeImagePaths(UUID episodeId, List<Scene> scenes) {
        Map<UUID, Path> result = new LinkedHashMap<>();
        for (Scene s : scenes) {
            Asset asset = assetRepository.findByEpisodeIdAndAssetType(episodeId, AssetType.IMAGE).stream()
                    .filter(a -> s.getId().equals(a.getSceneId()) && a.isActive()).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing image for scene " + s.getSceneNumber()));
            result.put(s.getId(), Path.of(asset.getFilePath()));
        }
        return result;
    }

    @Async
    public void runPipelineAsync(UUID jobId, UUID episodeId) {
        try {
            Episode episode = episodeRepository.findById(episodeId).orElseThrow();
            episode.setStatus(EpisodeStatus.IN_PRODUCTION);
            episodeRepository.save(episode);

            List<Scene> scenes = sceneRepository.findByEpisodeIdOrderByOrderIndexAsc(episodeId);
            StoryBible bible = storyBibleRepository.findFirstByEpisodeIdAndActiveTrueOrderByVersionDesc(episodeId)
                    .orElseThrow(() -> new IllegalStateException("No active Story Bible for episode " + episodeId));

            List<Character> universeCharacters = episode.getUniverseId() != null
                    ? characterRepository.findByUniverseId(episode.getUniverseId())
                    : List.of();

            jobService.updateStatus(jobId, JobStatus.GENERATING_IMAGES, 10);
            Map<UUID, Path> sceneImagePaths = generateSceneImages(jobId, episode, scenes, universeCharacters, bible);

            jobService.updateStatus(jobId, JobStatus.VALIDATING_IMAGES, 35);
            validateImages(sceneImagePaths);

            jobService.updateStatus(jobId, JobStatus.GENERATING_AUDIO, 45);
            Map<UUID, Path> sceneAudioPaths = generateSceneAudio(jobId, episode, scenes);

            jobService.updateStatus(jobId, JobStatus.ASSEMBLING_VIDEO, 65);
            Path finalVideo = assembleVideo(episode, scenes, sceneImagePaths, sceneAudioPaths);

            jobService.updateStatus(jobId, JobStatus.GENERATING_THUMBNAIL, 78);
            generateThumbnail(episode, scenes, sceneImagePaths);

            // subtitles
            String srt = subtitleService.buildSrt(scenes);
            Path srtPath = storageProvider.store(assetRelativePath(episode, "subtitles/subtitles.srt"), srt.getBytes());
            saveAsset(episode.getId(), null, AssetType.SUBTITLE, srtPath, "internal", null, null);

            jobService.updateStatus(jobId, JobStatus.GENERATING_SHORTS, 88);
            generateShorts(episode, scenes, finalVideo);

            jobService.updateStatus(jobId, JobStatus.QUALITY_CHECK, 95);
            // Final quality gate is a light pass-through: draft-time score already computed;
            // production-time issues (missing assets) would already have thrown above.

            episode.setStatus(EpisodeStatus.PRODUCTION_COMPLETE);
            episodeRepository.save(episode);
            episodeMemoryService.recordMemory(episode, bible);

            jobService.updateStatus(jobId, JobStatus.COMPLETED, 100);
        } catch (Exception e) {
            log.error("Production pipeline failed for episode {}: {}", episodeId, e.getMessage(), e);
            jobService.fail(jobId, e.getMessage());
            episodeRepository.findById(episodeId).ifPresent(ep -> {
                ep.setStatus(EpisodeStatus.FAILED);
                episodeRepository.save(ep);
            });
        }
    }

    // ---- per-stage helpers -------------------------------------------------

    private Map<UUID, Path> generateSceneImages(UUID jobId, Episode episode, List<Scene> scenes,
                                                 List<Character> universeCharacters, StoryBible bible) {
        Map<UUID, Path> result = new LinkedHashMap<>();
        String visualStyle = episode.getVisualStyle();
        String colorPalette = extractColorPalette(bible);
        List<String> failures = new ArrayList<>();
        // When an explicit CharacterReference exists it is the strongest source of
        // identity. If a project has not created one yet, carry the last approved
        // scene image forward for scenes that still contain the same character(s).
        // This gives IPAdapter something real to condition on without inventing a
        // new character sheet for every episode.
        Path lastContinuityReference = null;
        Set<String> lastContinuityCharacters = Set.of();

        for (Scene scene : scenes) {
            // Idempotent skip: a scene that already has a successful image (from
            // an earlier run of this same method, e.g. after retrying a partial
            // failure) is not regenerated. This is what makes retrying after a
            // partial failure cheap - only the scenes that actually failed get
            // redone, not the whole episode.
            Path existing = activeImagePathFor(episode.getId(), scene.getId());
            if (existing != null) {
                result.put(scene.getId(), existing);
                continue;
            }

            var step = jobService.startStep(jobId, scene.getId(), "GENERATE_IMAGE", "image", null);
            long start = System.currentTimeMillis();
            try {
                List<Character> sceneCharacters = resolveSceneCharacters(scene, universeCharacters);
                String referenceImagePath = resolvePrimaryReferenceImage(sceneCharacters);
                Set<String> currentCharacterNames = sceneCharacters.stream()
                        .map(c -> c.getName() == null ? "" : c.getName().trim().toLowerCase(Locale.ROOT))
                        .filter(n -> !n.isBlank())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                if (referenceImagePath == null && lastContinuityReference != null
                        && !currentCharacterNames.isEmpty()
                        && currentCharacterNames.stream().anyMatch(lastContinuityCharacters::contains)
                        && lastContinuityReference.toFile().exists()) {
                    referenceImagePath = lastContinuityReference.toString();
                    log.info("Scene {} using previous-scene character continuity reference for {}",
                            scene.getSceneNumber(), currentCharacterNames);
                }
                var assembled = imagePromptAssembler.assemble(scene, sceneCharacters, List.of(), visualStyle, colorPalette);

                // persist final prompt actually used, versioned (spec section 31)
                scene.setImagePrompt(assembled.positivePrompt());
                scene.setNegativePrompt(assembled.negativePrompt());
                sceneRepository.save(scene);

                // Size/steps/cfg are left at 0 deliberately so the provider applies the
                // configured defaults (studio.comfyui.width/height/steps/cfg). Hard-coding
                // them here made the single biggest cost driver in the whole pipeline
                // un-tunable without a rebuild - on CPU, 1024x576 is roughly 4x the work of
                // 512x512 per step. FFmpeg upscales to 1080p during assembly anyway, so
                // generating above the checkpoint's native resolution buys nothing and, on
                // SD1.5, actively produces duplicated limbs and heads.
                //
                // The IPAdapter workflow is only selected when a reference image is
                // actually available - a scene with no reference-carrying character
                // renders through the plain default workflow exactly as before.
                ImageGenerationProvider.ImageGenerationResult result0 = null;
                String validationFeedback = "";
                int attempts = Math.max(0, imageValidationRetries) + 1;
                for (int attempt = 0; attempt < attempts; attempt++) {
                    String prompt = assembled.positivePrompt();
                    if (!validationFeedback.isBlank()) {
                        prompt += ", corrected from previous QA feedback: " + validationFeedback;
                    }
                    Long stableSeed = stableSceneSeed(episode, scene, sceneCharacters);
                    var request = new ImageGenerationProvider.ImageGenerationRequest(
                            prompt, assembled.negativePrompt(), 0, 0, 0, 0,
                            stableSeed, referenceImagePath != null ? "character-consistent-story-ipadapter" : null,
                            styleModel(visualStyle), referenceImagePath);
                    result0 = providerGateway.generateImage(request);
                    if (result0 != null && "mock".equalsIgnoreCase(result0.workflowUsed())
                            && referenceImagePath != null) {
                        // A missing IPAdapter custom node must never turn into a
                        // placeholder frame. Retry the same deterministic scene
                        // without reference conditioning so the real checkpoint
                        // still produces an image.
                        log.warn("Reference workflow was unavailable for scene {}; retrying plain ComfyUI workflow.",
                                scene.getSceneNumber());
                        var fallbackRequest = new ImageGenerationProvider.ImageGenerationRequest(
                                prompt, assembled.negativePrompt(), 0, 0, 0, 0,
                                stableSeed, null, styleModel(visualStyle), null);
                        result0 = providerGateway.generateImage(fallbackRequest);
                    }
                    var qa = validateGeneratedImage(result0, prompt, assembled.negativePrompt(), visualStyle, scene);
                    validationFeedback = qa.feedback();
                    if (qa.passed()) break;
                    log.warn("Image QA rejected scene {} attempt {}/{}: {}", scene.getSceneNumber(), attempt + 1, attempts, qa.feedback());
                    if (attempt == attempts - 1) {
                        throw new IllegalStateException("Image quality validation failed for scene "
                                + scene.getSceneNumber() + ": " + qa.feedback());
                    }
                }

                String relative = assetRelativePath(episode, String.format("images/scene-%03d.%s", scene.getSceneNumber(), result0.fileExtension()));
                Path path = storageProvider.store(relative, result0.imageBytes());
                saveAsset(episode.getId(), scene.getId(), AssetType.IMAGE, path, result0.workflowUsed(), result0.modelUsed(), result0.seedUsed());
                result.put(scene.getId(), path);
                if (!currentCharacterNames.isEmpty()) {
                    lastContinuityReference = path;
                    lastContinuityCharacters = currentCharacterNames;
                }

                jobService.completeStep(step.getId(), System.currentTimeMillis() - start);
            } catch (Exception e) {
                // Isolated per scene (spec section 29): record the failure and
                // move on to the next scene rather than aborting the batch.
                // Previously this rethrew immediately, which meant one bad
                // prompt on scene 4 of 10 silently prevented scenes 5-10 from
                // ever being attempted at all.
                log.warn("Image generation failed for scene {} of episode {}: {}",
                        scene.getSceneNumber(), episode.getId(), e.getMessage());
                jobService.failStep(step.getId(), e.getMessage(), 0);
                failures.add("scene " + scene.getSceneNumber() + " (" + e.getMessage() + ")");
            }
        }

        if (!failures.isEmpty()) {
            // Every other scene's image is already saved as a real asset by this
            // point, so retrying (generateImagesOnly again) only redoes these.
            throw new IllegalStateException(
                    "Image generation failed for " + failures.size() + " of " + scenes.size()
                            + " scene(s): " + String.join("; ", failures)
                            + ". The other scenes' images were generated successfully; run image generation again to retry only the failed ones.");
        }
        return result;
    }

    /** The current active IMAGE asset's file, if this scene already has one. */
    private Path activeImagePathFor(UUID episodeId, UUID sceneId) {
        return assetRepository.findByEpisodeIdAndAssetType(episodeId, AssetType.IMAGE).stream()
                .filter(a -> sceneId.equals(a.getSceneId()) && a.isActive())
                .findFirst()
                .map(a -> Path.of(a.getFilePath()))
                .filter(p -> p.toFile().exists() && p.toFile().length() > 0)
                .orElse(null);
    }

    private void validateImages(Map<UUID, Path> sceneImagePaths) {
        for (Path path : sceneImagePaths.values()) {
            if (!path.toFile().exists() || path.toFile().length() == 0) {
                throw new IllegalStateException("Generated image is missing or empty: " + path);
            }
        }
    }

    private Map<UUID, Path> generateSceneAudio(UUID jobId, Episode episode, List<Scene> scenes) {
        Map<UUID, Path> result = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        for (Scene scene : scenes) {
            // Idempotent skip, same reasoning as generateSceneImages: a scene
            // that already has a successful narration asset (e.g. from an
            // earlier run that partially failed) is not re-synthesised.
            Path existing = activeAudioPathFor(episode.getId(), scene.getId());
            if (existing != null) {
                result.put(scene.getId(), existing);
                continue;
            }
            var step = jobService.startStep(jobId, scene.getId(), "GENERATE_NARRATION", "tts", null);
            long start = System.currentTimeMillis();
            try {
                List<VoiceSegment> segments = readVoiceSegments(scene);
                if (segments.isEmpty()) segments = List.of(new VoiceSegment("Narrator", scene.getNarration(), "", 1.0, 1.0, scene.getEmotion(), 0, 0));
                List<byte[]> parts = new ArrayList<>();
                for (VoiceSegment seg : segments) {
                    if (seg.text() == null || seg.text().isBlank()) continue;
                    double pauseScale = lookupEmotionProsody(seg.emotion()).pauseScale();
                    maybeInsertBreath(parts, seg.pauseBeforeMs());
                    appendSilence(parts, scaledPause(seg.pauseBeforeMs(), pauseScale));
                    // One synthesis call per voice segment keeps the same voice and
                    // prosody context across the sentence. Punctuation remains inside
                    // the request; explicit pauses are added outside it.
                    Prosody p = prosody(seg);
                    var tts = providerGateway.synthesize(new TextToSpeechProvider.TtsRequest(
                            seg.text().trim(),
                            seg.voice() == null || seg.voice().isBlank() ? null : seg.voice(),
                            episode.getLanguage(), p.speed(), p.pitch()));
                    parts.add(tts.audioBytes());
                    appendSilence(parts, scaledPause(seg.pauseAfterMs(), pauseScale));
                }
                if (parts.isEmpty()) throw new IllegalStateException("Scene " + scene.getSceneNumber() + " has no spoken text.");
                byte[] combined = concatenateWav(parts);
                combined = mediaProcessor.humanizeVoice(combined);
                Path path = storageProvider.store(assetRelativePath(episode, String.format("audio/scene-%03d.wav", scene.getSceneNumber())), combined);
                saveAsset(episode.getId(), scene.getId(), AssetType.AUDIO_NARRATION, path, "tts-multi-voice", null, null);
                result.put(scene.getId(), path);
                double duration = probeWavDuration(combined);
                if (duration > 0) { scene.setNarrationSeconds(duration); scene.setImageDurationSeconds(duration + 0.6); sceneRepository.save(scene); }
                jobService.completeStep(step.getId(), System.currentTimeMillis() - start);
            } catch (Exception e) {
                // Isolated per scene, same as generateSceneImages: one scene's
                // TTS failure (a bad voice id, a provider timeout) no longer
                // silently prevents every later scene from being attempted.
                log.warn("Narration generation failed for scene {} of episode {}: {}",
                        scene.getSceneNumber(), episode.getId(), e.getMessage());
                jobService.failStep(step.getId(), e.getMessage(), 0);
                failures.add("scene " + scene.getSceneNumber() + " (" + e.getMessage() + ")");
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Narration generation failed for " + failures.size() + " of " + scenes.size()
                            + " scene(s): " + String.join("; ", failures)
                            + ". The other scenes' narration was generated successfully; run this step again to retry only the failed ones.");
        }
        return result;
    }

    /** The current active AUDIO_NARRATION asset's file, if this scene already has one. */
    private Path activeAudioPathFor(UUID episodeId, UUID sceneId) {
        return assetRepository.findByEpisodeIdAndAssetType(episodeId, AssetType.AUDIO_NARRATION).stream()
                .filter(a -> sceneId.equals(a.getSceneId()) && a.isActive())
                .findFirst()
                .map(a -> Path.of(a.getFilePath()))
                .filter(p -> p.toFile().exists() && p.toFile().length() > 0)
                .orElse(null);
    }

    private record VoiceSegment(String character, String text, String voice, Double speed, Double pitch, String emotion, Integer pauseBeforeMs, Integer pauseAfterMs) {}
    private record Prosody(double speed, double pitch) {}

    private List<VoiceSegment> readVoiceSegments(Scene scene) {
        if (scene.getVoiceSegmentsJson() == null || scene.getVoiceSegmentsJson().isBlank()) return List.of();
        try { return mapper.readValue(scene.getVoiceSegmentsJson(), mapper.getTypeFactory().constructCollectionType(List.class, VoiceSegment.class)); }
        catch (Exception e) { return List.of(); }
    }

    /** speed/pitch multiplier + pauseScale: how much longer/shorter this
     *  emotion's pauses should read (sad/calm linger, excited/scared clip
     *  tight) - a real human speaking sadly doesn't just talk slower, they
     *  also leave bigger gaps between clauses; a flat 1.0 pause regardless
     *  of emotion is exactly what reads robotic. */
    private record ProsodyEntry(double speed, double pitch, double pauseScale) {}

    private static final Map<String, ProsodyEntry> EMOTION_PROSODY = Map.ofEntries(
            Map.entry("happy", new ProsodyEntry(1.06, 1.06, 0.85)),
            Map.entry("joyful", new ProsodyEntry(1.06, 1.06, 0.85)),
            Map.entry("cheerful", new ProsodyEntry(1.06, 1.06, 0.85)),
            Map.entry("excited", new ProsodyEntry(1.14, 1.12, 0.65)),
            Map.entry("thrilled", new ProsodyEntry(1.14, 1.12, 0.65)),
            Map.entry("sad", new ProsodyEntry(0.90, 0.94, 1.35)),
            Map.entry("melancholy", new ProsodyEntry(0.90, 0.94, 1.35)),
            Map.entry("sorrowful", new ProsodyEntry(0.90, 0.94, 1.35)),
            Map.entry("calm", new ProsodyEntry(0.92, 0.97, 1.25)),
            Map.entry("gentle", new ProsodyEntry(0.92, 0.97, 1.25)),
            Map.entry("peaceful", new ProsodyEntry(0.92, 0.97, 1.25)),
            Map.entry("angry", new ProsodyEntry(1.10, 1.05, 0.75)),
            Map.entry("furious", new ProsodyEntry(1.14, 1.08, 0.65)),
            Map.entry("scared", new ProsodyEntry(1.12, 1.10, 0.70)),
            Map.entry("afraid", new ProsodyEntry(1.12, 1.10, 0.70)),
            Map.entry("fearful", new ProsodyEntry(1.12, 1.10, 0.70)),
            Map.entry("whisper", new ProsodyEntry(0.90, 0.92, 1.15)),
            Map.entry("nervous", new ProsodyEntry(1.08, 1.06, 0.80)),
            Map.entry("anxious", new ProsodyEntry(1.08, 1.06, 0.80)),
            Map.entry("surprised", new ProsodyEntry(1.10, 1.10, 0.75)),
            Map.entry("shocked", new ProsodyEntry(1.12, 1.12, 0.70)),
            Map.entry("tender", new ProsodyEntry(0.90, 0.98, 1.30)),
            Map.entry("loving", new ProsodyEntry(0.92, 0.98, 1.20)),
            Map.entry("tired", new ProsodyEntry(0.86, 0.92, 1.30)),
            Map.entry("sleepy", new ProsodyEntry(0.84, 0.90, 1.35)),
            Map.entry("confident", new ProsodyEntry(1.02, 1.02, 0.90)),
            Map.entry("determined", new ProsodyEntry(1.04, 1.02, 0.85)),
            Map.entry("mysterious", new ProsodyEntry(0.90, 0.95, 1.30)),
            Map.entry("curious", new ProsodyEntry(1.02, 1.04, 0.90)),
            Map.entry("worried", new ProsodyEntry(1.02, 1.02, 1.10))
    );

    /** Exact match first; fuzzy contains-match second (LLM may write
     *  "joyfully"/"super excited" rather than the bare key) - blank text
     *  guarded before the fuzzy loop, since an empty string is a substring
     *  of every key and would otherwise false-match the first table entry. */
    private ProsodyEntry lookupEmotionProsody(String emotion) {
        String e = emotion == null ? "" : emotion.toLowerCase(Locale.ROOT).trim();
        if (e.isEmpty()) {
            return new ProsodyEntry(1.0, 1.0, 1.0);
        }
        ProsodyEntry exact = EMOTION_PROSODY.get(e);
        if (exact != null) {
            return exact;
        }
        for (var entry : EMOTION_PROSODY.entrySet()) {
            if (e.contains(entry.getKey()) || entry.getKey().contains(e)) {
                return entry.getValue();
            }
        }
        return new ProsodyEntry(1.0, 1.0, 1.0);
    }

    private Prosody prosody(VoiceSegment s) {
        double speed = s.speed() == null || s.speed() <= 0 ? 1.0 : s.speed();
        double pitch = s.pitch() == null || s.pitch() <= 0 ? 1.0 : s.pitch();
        ProsodyEntry p = lookupEmotionProsody(s.emotion());
        speed *= p.speed();
        pitch *= p.pitch();
        // Real voice is never perfectly flat clause to clause - a tiny
        // random jitter (~2%) per line keeps consecutive lines from
        // sounding like they were stamped from the same template, without
        // being large enough to sound unstable.
        double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.04;
        speed *= jitter;
        return new Prosody(Math.max(.6, Math.min(1.6, speed)), Math.max(.6, Math.min(1.5, pitch)));
    }

    private List<String> splitPauseMarkers(String text) {
        List<String> parts = new ArrayList<>();
        var m = java.util.regex.Pattern.compile("\\[pause\\s*:\\s*(\\d{1,5})\\s*ms?\\]", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        int last = 0;
        while (m.find()) { if (m.start() > last) parts.add(text.substring(last, m.start())); parts.add("\u0000PAUSE:" + m.group(1)); last = m.end(); }
        if (last < text.length()) parts.add(text.substring(last));
        return parts.isEmpty() ? List.of(text) : parts;
    }

    private static final java.util.regex.Pattern PUNCTUATION_PAUSE_PATTERN =
            java.util.regex.Pattern.compile("(\\.\\.\\.|\u2026)|([.!?]+)|([,;:])|(--|\u2014)");

    /**
     * Automatically inserts natural pauses at sentence/clause punctuation
     * (spec: "pauses at ,  .  and other special characters"), so narration
     * doesn't run on in a single unbroken breath even for a scene where the
     * story engine didn't author an explicit [pause:Xms] marker mid-sentence.
     * Skipped entirely if the segment already contains an explicit marker -
     * authored rhythm control always wins over this heuristic.
     *
     * Known limitation, stated rather than hidden: this is punctuation
     * matching, not language understanding, so an abbreviation like "Dr." or
     * "Mr." gets the same pause as a real sentence end - narration reads
     * only slightly odd there, not broken. A decimal number like "3.14" is
     * specifically guarded against (digit immediately before and after the
     * period), since that pattern is common enough in generated narration
     * (distances, ages, times) to be worth handling explicitly rather than
     * leaving every number awkwardly broken up.
     */
    private String insertPunctuationPauses(String text, double pauseScale) {
        if (text == null || text.isBlank()
                || text.toLowerCase(java.util.Locale.ROOT).contains("[pause")) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        var m = PUNCTUATION_PAUSE_PATTERN.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.end());
            last = m.end();
            int ms;
            if (m.group(1) != null) {
                ms = 600; // ellipsis
            } else if (m.group(2) != null) {
                int i = m.start();
                boolean decimal = m.group(2).equals(".") && i > 0 && java.lang.Character.isDigit(text.charAt(i - 1))
                        && i + 1 < text.length() && java.lang.Character.isDigit(text.charAt(i + 1));
                ms = decimal ? 0 : 450; // sentence end, unless it's a decimal point
            } else if (m.group(3) != null) {
                ms = 220; // comma / semicolon / colon
            } else {
                ms = 350; // dash
            }
            if (ms > 0) {
                ms = (int) Math.round(ms * pauseScale);
                out.append(" [pause:").append(ms).append("ms]");
            }
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /** Null-safe emotion-scaled pause - LLM-authored pauseBefore/AfterMs
     *  should breathe the same way punctuation pauses do (sad lingers,
     *  excited clips tight), not stay fixed regardless of mood. */
    private Integer scaledPause(Integer ms, double scale) {
        return ms == null ? null : (int) Math.round(ms * scale);
    }

    private void appendSilence(List<byte[]> parts, Integer milliseconds) {
        int ms = milliseconds == null ? 0 : Math.max(0, Math.min(10000, milliseconds));
        if (ms == 0) return;
        javax.sound.sampled.AudioFormat f = new javax.sound.sampled.AudioFormat(22050, 16, 1, true, false);
        byte[] silence = new byte[(int) (f.getSampleRate() * (ms / 1000.0) * f.getFrameSize())];
        try (var ais = new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(silence), f, silence.length / f.getFrameSize());
             var out = new java.io.ByteArrayOutputStream()) {
            javax.sound.sampled.AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, out); parts.add(out.toByteArray());
        } catch (Exception e) { throw new IllegalStateException("Could not create narration pause", e); }
    }

    private static volatile byte[] breathSampleCache;

    /**
     * A quiet, procedurally-synthesised breath sound (classpath:breath/breath.wav,
     * already in the 22050Hz/16-bit/mono format the rest of this pipeline uses,
     * so it splices straight into `parts` with no conversion). Loaded once per
     * JVM and reused - it's a single ~18KB file, not worth re-reading per call.
     * Returns null (silently: no breath, not an error) if the resource is
     * somehow missing, matching this pipeline's general rule that a missing
     * optional polish asset should never break narration generation.
     */
    private byte[] loadBreathSample() {
        byte[] cached = breathSampleCache;
        if (cached != null) {
            return cached;
        }
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("breath/breath.wav")) {
            if (in == null) {
                return null;
            }
            byte[] loaded = in.readAllBytes();
            breathSampleCache = loaded;
            return loaded;
        } catch (Exception e) {
            log.warn("Could not load breath sample, narration will have no breaths: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Spec section 31's breath rules, applied: only at a real pause (>=400ms -
     * a sentence/paragraph boundary, not a short comma-level pause), not
     * before every one (~35% chance), and always quiet (the sample itself is
     * mixed low - see the ffmpeg synthesis that produced breath.wav).
     * Inserted before the silence it precedes, so the breath sits inside the
     * pause rather than extending it by much.
     */
    private void maybeInsertBreath(List<byte[]> parts, Integer pauseBeforeMs) {
        if (pauseBeforeMs == null || pauseBeforeMs < 400) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() > 0.35) {
            return;
        }
        byte[] breath = loadBreathSample();
        if (breath != null) {
            parts.add(breath);
        }
    }

    private byte[] concatenateWav(List<byte[]> wavs) {
        javax.sound.sampled.AudioFormat target = new javax.sound.sampled.AudioFormat(22050, 16, 1, true, false);
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        try {
            for (byte[] wav : wavs) {
                try (var source = javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.ByteArrayInputStream(wav));
                     var converted = javax.sound.sampled.AudioSystem.isConversionSupported(target, source.getFormat())
                             ? javax.sound.sampled.AudioSystem.getAudioInputStream(target, source) : source) { converted.transferTo(pcm); }
            }
            byte[] data = pcm.toByteArray();
            try (var combined = new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(data), target, data.length / target.getFrameSize());
                 var out = new java.io.ByteArrayOutputStream()) { javax.sound.sampled.AudioSystem.write(combined, javax.sound.sampled.AudioFileFormat.Type.WAVE, out); return out.toByteArray(); }
        } catch (Exception e) { throw new IllegalStateException("Could not combine multi-voice narration", e); }
    }

    private double probeWavDuration(byte[] wav) {
        try (var ais = javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.ByteArrayInputStream(wav))) { return ais.getFrameLength() / ais.getFormat().getFrameRate(); }
        catch (Exception e) { return -1; }
    }

    private Path assembleVideo(Episode episode, List<Scene> scenes, Map<UUID, Path> images, Map<UUID, Path> audio) {
        List<MediaProcessor.SceneClip> clips = new ArrayList<>();
        for (Scene scene : scenes) {
            var decision = animationDecisionService.decide(scene);
            double duration = scene.getImageDurationSeconds() != null ? scene.getImageDurationSeconds() : 5.0;
            Path aiVideoPath = null;
            // Both AI tiers still correctly report unavailable unless
            // genuinely configured (see LocalAIAnimationProvider /
            // CloudAIAnimationProvider) - this only ever fires on hardware
            // where it's actually been set up, exactly as the decision
            // service's own priority table intends. Any failure here (OOM,
            // ComfyUI down, workflow rejected, timeout) falls back to the
            // always-available 2.5D path below rather than failing the
            // scene - the whole point of a fallback chain is that a video
            // model having a bad day never blocks the story.
            if ("local-ai".equals(decision.providerId()) && providerGateway.isLocalAiVideoAvailable()) {
                aiVideoPath = tryGenerateAiVideo(episode, scene, images.get(scene.getId()), duration);
            }
            clips.add(new MediaProcessor.SceneClip(images.get(scene.getId()), audio.get(scene.getId()), duration,
                    scene.getCameraMovement(), scene.getTransitionIn(), scene.getEmotion(),
                    scene.getLighting(), scene.getAction(), scene.getLocation(), scene.getImportance(),
                    scene.getAnimationMode(), aiVideoPath));
            MediaProcessor.SceneClip built = clips.get(clips.size() - 1);
            // Spec section 12/55: log the full motion profile alongside the
            // tier decision, not just the tier - "why this animation" should
            // be inspectable down to the actual camera/parallax/character/
            // environment values, not just "which provider".
            log.info("Scene {} motion profile: {}", scene.getSceneNumber(),
                    aiVideoPath != null ? "{\"note\":\"AI video - 2.5D motion profile not applicable\"}"
                            : mediaProcessor.buildMotionProfileJson(built));
        }
        Path outputPath = storageProvider.resolve(assetRelativePath(episode, "video/final.mp4"));
        Path musicPath = resolveMusicPath(episode, scenes);
        Path video = mediaProcessor.assembleVideo(new MediaProcessor.VideoAssemblyRequest(clips, musicPath, outputPath, 1920, 1080));
        saveAsset(episode.getId(), null, AssetType.VIDEO, video, "ffmpeg", null, null);
        return video;
    }

    /**
     * Attempts real local AI image-to-video for one scene, animating the
     * scene's own already-generated image (which is itself character-
     * consistent when a reference exists - see resolvePrimaryReferenceImage)
     * as the starting frame. This is the actual consistency mechanism: the
     * video model never re-imagines the character from text, it animates
     * the exact frame that was already generated for this scene.
     *
     * Returns null (never throws) on any failure - the caller always has a
     * working 2.5D fallback, and a slow/broken video model should degrade
     * the scene's look, not break the whole episode's generation.
     */
    private Path tryGenerateAiVideo(Episode episode, Scene scene, Path sceneImage, double duration) {
        if (sceneImage == null) {
            return null;
        }
        try {
            String prompt = (scene.getAction() == null ? "" : scene.getAction() + ". ")
                    + (scene.getCameraMovement() == null ? "" : "Camera: " + scene.getCameraMovement() + ". ")
                    + (scene.getEmotion() == null ? "" : "Mood: " + scene.getEmotion());
            var request = new VideoGenerationProvider.VideoGenerationRequest(
                    sceneImage.toString(), prompt, "static, blurry, distorted, extra limbs",
                    duration, 0, 0, null, null);
            long start = System.currentTimeMillis();
            var result = providerGateway.generateVideo(request);
            Path path = storageProvider.store(
                    assetRelativePath(episode, String.format("video/scene-%03d-ai.%s", scene.getSceneNumber(), result.fileExtension())),
                    result.videoBytes());
            log.info("AI video generated for scene {} in {}s ({} bytes)",
                    scene.getSceneNumber(), (System.currentTimeMillis() - start) / 1000, result.videoBytes().length);
            return path;
        } catch (Exception e) {
            log.warn("AI video generation failed for scene {}, falling back to 2.5D: {}",
                    scene.getSceneNumber(), e.getMessage());
            return null;
        }
    }

    /**
     * A user-uploaded MUSIC asset (via the attach-music endpoint) always
     * wins over the episode's preset selection - an explicit upload is a
     * stronger signal of intent than a mood dropdown. Falls back to the
     * preset if set, then to no music at all. Never throws: a missing or
     * unreadable music file should silently mean "no music", not fail the
     * whole video.
     */
    /**
     * Resolves the episode's music bed. An explicit uploaded track always wins.
     * If the user did not pick a preset, the story gets a deterministic mood
     * preset automatically so a completed children's episode never ships silent
     * unless music was deliberately disabled.
     *
     * Bundled music is copied into the episode's own asset folder. That makes the
     * BGM reproducible and ensures the Complete Package ZIP contains the exact
     * track used by final.mp4.
     */
    private Path resolveMusicPath(Episode episode, List<Scene> scenes) {
        Path uploaded = assetRepository.findByEpisodeIdAndAssetType(episode.getId(), AssetType.MUSIC).stream()
                .filter(Asset::isActive)
                .filter(a -> a.getProvider() == null || !a.getProvider().startsWith("bundled-music:"))
                .findFirst()
                .map(a -> Path.of(a.getFilePath()))
                .filter(p -> p.toFile().exists() && p.toFile().length() > 0)
                .orElse(null);
        if (uploaded != null) {
            return uploaded;
        }

        String preset = episode.getMusicPreset();
        if (preset == null || preset.isBlank()) {
            if (!autoMusicEnabled) {
                return null;
            }
            preset = autoMusicPreset(scenes);
            episode.setMusicPreset(preset);
            episodeRepository.save(episode);
        }
        Path presetSource = mediaProcessor.musicPresetPath(preset);
        if (presetSource == null || !presetSource.toFile().exists()) {
            return null;
        }

        // Reuse the already-copied bundled track when this episode has been
        // rendered before. This also avoids accumulating duplicate MUSIC rows.
        String provider = "bundled-music:" + preset;
        Path existing = assetRepository.findByEpisodeIdAndAssetType(episode.getId(), AssetType.MUSIC).stream()
                .filter(Asset::isActive)
                .filter(a -> provider.equals(a.getProvider()))
                .map(a -> Path.of(a.getFilePath()))
                .filter(p -> p.toFile().exists() && p.toFile().length() > 0)
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        try {
            String relative = assetRelativePath(episode, "audio/background-music-" + preset + ".m4a");
            Path stored = storageProvider.store(relative, java.nio.file.Files.readAllBytes(presetSource));

            // Deactivate an older bundled bed when the preset changed. Uploaded
            // music is intentionally left alone; it still wins above.
            assetRepository.findByEpisodeIdAndAssetType(episode.getId(), AssetType.MUSIC).stream()
                    .filter(Asset::isActive)
                    .filter(a -> a.getProvider() != null && a.getProvider().startsWith("bundled-music:"))
                    .forEach(a -> {
                        a.setActive(false);
                        assetRepository.save(a);
                    });
            saveAsset(episode.getId(), null, AssetType.MUSIC, stored, provider, null, null);
            return stored;
        } catch (Exception e) {
            log.warn("Could not persist bundled background music '{}': {}", preset, e.getMessage());
            return presetSource;
        }
    }

    private String autoMusicPreset(List<Scene> scenes) {
        if (scenes == null || scenes.isEmpty()) {
            return "calm";
        }
        int emotional = 0;
        int adventurous = 0;
        for (Scene scene : scenes) {
            String text = ((scene.getEmotion() == null ? "" : scene.getEmotion()) + " "
                    + (scene.getAction() == null ? "" : scene.getAction()) + " "
                    + (scene.getLighting() == null ? "" : scene.getLighting())).toLowerCase(Locale.ROOT);
            if (text.matches(".*(sad|worried|melancholy|sorrow|tender|emotional|lonely|scared|fear).*")) {
                emotional++;
            }
            if (text.matches(".*(adventure|run|fly|flying|chase|journey|excited|thrilled|battle|discover|suddenly).*")) {
                adventurous++;
            }
        }
        if (emotional > adventurous && emotional >= Math.max(2, scenes.size() / 3)) {
            return "emotional";
        }
        if (adventurous >= 2 || adventurous > emotional) {
            return "adventurous";
        }
        return "calm";
    }

    private void generateThumbnail(Episode episode, List<Scene> scenes, Map<UUID, Path> images) {
        if (scenes.isEmpty()) return;
        // heuristic: use the strongest emotional scene's image as the thumbnail base (spec section 25)
        Scene chosen = scenes.stream()
                .filter(s -> s.getEmotion() != null && !s.getEmotion().isBlank())
                .findFirst().orElse(scenes.get(0));
        Path sourceImage = images.get(chosen.getId());
        if (sourceImage == null) return;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(sourceImage);
            Path path = storageProvider.store(assetRelativePath(episode, "thumbnail/thumbnail.png"), bytes);
            saveAsset(episode.getId(), chosen.getId(), AssetType.THUMBNAIL, path, "reused-scene-image", null, null);
        } catch (Exception e) {
            log.warn("Thumbnail generation failed for episode {}: {}", episode.getId(), e.getMessage());
        }
    }

    private void generateShorts(Episode episode, List<Scene> scenes, Path finalVideo) {
        if (scenes.isEmpty()) return;
        double totalDuration = scenes.stream().mapToDouble(s -> s.getImageDurationSeconds() == null ? 5.0 : s.getImageDurationSeconds()).sum();
        double[][] windows = {
                {0, Math.min(20, totalDuration)},
                {Math.max(0, totalDuration / 2 - 10), Math.min(totalDuration, totalDuration / 2 + 10)},
                {Math.max(0, totalDuration - 20), totalDuration}
        };
        int i = 1;
        for (double[] window : windows) {
            if (window[1] <= window[0]) continue;
            try {
                Path outputPath = storageProvider.resolve(assetRelativePath(episode, String.format("shorts/short-%02d.mp4", i)));
                Path shortPath = mediaProcessor.renderShort(finalVideo, window[0], window[1], outputPath);
                saveAsset(episode.getId(), null, AssetType.SHORT, shortPath, "ffmpeg", null, null);
            } catch (Exception e) {
                log.warn("Short {} generation failed for episode {}: {}", i, episode.getId(), e.getMessage());
            }
            i++;
        }
    }

    // ---- utilities -----------------------------------------------------

    private ImageGenerationProvider.ImageGenerationResult safeResult(ImageGenerationProvider.ImageGenerationResult result) {
        if (result == null || result.imageBytes() == null || result.imageBytes().length < 1024) {
            throw new IllegalStateException("ComfyUI returned an empty or invalid image.");
        }
        try (var in = new java.io.ByteArrayInputStream(result.imageBytes())) {
            var image = javax.imageio.ImageIO.read(in);
            if (image == null || image.getWidth() < 256 || image.getHeight() < 256) {
                throw new IllegalStateException("Generated image is too small or unreadable.");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Generated image could not be decoded.", e);
        }
        return result;
    }

    private VisionProvider.ImageValidationResult validateGeneratedImage(ImageGenerationProvider.ImageGenerationResult result,
                                                                         String prompt, String negativePrompt,
                                                                         String visualStyle, Scene scene) {
        result = safeResult(result);
        if (!imageValidationEnabled || providerGateway.vision().providerName().startsWith("mock-vision")) {
            return new VisionProvider.ImageValidationResult(true, 1.0, "");
        }
        try {
            String context = "characters=" + scene.getCharactersJson() + "; location=" + scene.getLocation()
                    + "; action=" + scene.getAction() + "; emotion=" + scene.getEmotion();
            return providerGateway.vision().validateGeneratedImage(result.imageBytes(), prompt, negativePrompt,
                    visualStyle, context);
        } catch (Exception e) {
            // Vision QA is an enhancement, never a reason to break a working
            // image pipeline when the optional model/API is unavailable.
            log.warn("Optional image vision QA unavailable; accepting generated image: {}", e.getMessage());
            return new VisionProvider.ImageValidationResult(true, 0.0, "Vision QA unavailable");
        }
    }

    private String styleModel(String visualStyle) {
        if (visualStyle == null) return null;
        return switch (visualStyle.trim().toUpperCase(Locale.ROOT)) {
            case "ANIME", "ANIME-INSPIRED" -> blankToNull(animeModel);
            case "CARTOON" -> blankToNull(cartoonModel);
            case "3D ANIMATED FEATURE" -> blankToNull(threeDAnimatedModel);
            case "STORYBOOK" -> blankToNull(storybookModel);
            case "WATERCOLOR" -> blankToNull(watercolorModel);
            case "COMIC BOOK" -> blankToNull(comicBookModel);
            case "FANTASY ILLUSTRATION" -> blankToNull(fantasyModel);
            default -> null;
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Stable-but-scene-specific seed. Keeping the character/style portion stable
     * gives the diffusion model a repeatable latent foundation, while the scene
     * number still prevents every shot from becoming a near-duplicate.
     */
    private long stableSceneSeed(Episode episode, Scene scene, List<Character> characters) {
        String names = characters == null ? "" : characters.stream()
                .map(Character::getName)
                .filter(Objects::nonNull)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(java.util.stream.Collectors.joining("|"));
        String key = episode.getId() + "|" + episode.getVisualStyle() + "|" + names + "|scene:" + scene.getSceneNumber();
        long hash = Integer.toUnsignedLong(key.hashCode());
        hash = (hash * 0x9E3779B97F4A7C15L) ^ (hash >>> 29);
        return hash == 0 ? 1L : Math.abs(hash == Long.MIN_VALUE ? Long.MAX_VALUE : hash);
    }

    private List<Character> resolveSceneCharacters(Scene scene, List<Character> universeCharacters) {
        if (scene.getCharactersJson() == null || universeCharacters.isEmpty()) return List.of();
        try {
            List<String> names = new ArrayList<>();
            mapper.readTree(scene.getCharactersJson()).forEach(n -> names.add(n.asText().toLowerCase()));
            return universeCharacters.stream()
                    .filter(c -> names.contains(c.getName().toLowerCase()))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * The reference image (if any) that should condition this scene's
     * generation, so the same character looks like the same character
     * across scenes instead of the text prompt alone deciding that fresh
     * every time. Checks scene characters in order and returns the first
     * one that has a reference: its explicitly marked-primary reference if
     * it has one, else its most recently generated reference. Null (no
     * reference-conditioning, plain txt2img as before) if none of the
     * scene's characters have any reference image yet, or the file on disk
     * has gone missing.
     */
    private String resolvePrimaryReferenceImage(List<Character> sceneCharacters) {
        for (Character c : sceneCharacters) {
            List<CharacterReference> refs = characterReferenceRepository.findByCharacterId(c.getId());
            if (refs.isEmpty()) {
                continue;
            }
            CharacterReference chosen = refs.stream()
                    .filter(CharacterReference::isPrimary)
                    .findFirst()
                    .orElseGet(() -> refs.stream()
                            .max(java.util.Comparator.comparing(CharacterReference::getCreatedAt))
                            .orElse(refs.get(0)));
            Path path = Path.of(chosen.getImagePath());
            if (path.toFile().exists() && path.toFile().length() > 0) {
                return path.toString();
            }
        }
        return null;
    }

    private String extractColorPalette(StoryBible bible) {
        try {
            if (bible.getVisualStyleJson() == null) return null;
            var node = mapper.readTree(bible.getVisualStyleJson());
            return node.has("colorPalette") ? node.get("colorPalette").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String assetRelativePath(Episode episode, String suffix) {
        return episode.getProjectId() + "/" + episode.getId() + "/" + suffix;
    }

    private void saveAsset(UUID episodeId, UUID sceneId, AssetType type, Path path, String provider, String model, Long seed) {
        // Deactivate whatever was previously active for this exact
        // episode+scene+type before adding the new one. Without this, a
        // second save for the same scene+type (e.g. force-regenerating one
        // scene's image) leaves two "active" rows and every active-asset
        // lookup elsewhere in this class becomes non-deterministic about
        // which one it returns - StoryboardService already gets this right;
        // this class did not.
        if (sceneId != null) {
            assetRepository.findByEpisodeIdAndAssetType(episodeId, type).stream()
                    .filter(a -> sceneId.equals(a.getSceneId()) && a.isActive())
                    .forEach(a -> { a.setActive(false); assetRepository.save(a); });
        }
        Asset asset = new Asset();
        asset.setEpisodeId(episodeId);
        asset.setSceneId(sceneId);
        asset.setAssetType(type);
        asset.setFilePath(path.toString());
        asset.setProvider(provider);
        asset.setModel(model);
        asset.setSeed(seed);
        assetRepository.save(asset);
    }

    /**
     * Force-regenerates exactly one scene's image, bypassing the
     * skip-if-already-generated check {@link #generateSceneImages} uses.
     * Refuses outright if the scene is locked - unlocking it first is the
     * only way to replace a locked image.
     */
    public Scene regenerateSingleSceneImage(UUID episodeId, UUID sceneId) {
        Scene scene = sceneRepository.findById(sceneId)
                .filter(s -> s.getEpisodeId().equals(episodeId))
                .orElseThrow(() -> new IllegalArgumentException("Scene not found for this episode."));
        if (scene.isLocked()) {
            throw new IllegalStateException(
                    "Scene " + scene.getSceneNumber() + " is locked. Unlock it before regenerating its image.");
        }
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        StoryBible bible = storyBibleRepository.findByEpisodeIdOrderByVersionDesc(episodeId)
                .stream().findFirst().orElse(null);
        List<Character> characters = episode.getUniverseId() == null
                ? List.of() : characterRepository.findByUniverseId(episode.getUniverseId());

        List<Character> sceneCharacters = resolveSceneCharacters(scene, characters);
        String colorPalette = extractColorPalette(bible);
        var assembled = imagePromptAssembler.assemble(scene, sceneCharacters, List.of(), episode.getVisualStyle(), colorPalette);
        scene.setImagePrompt(assembled.positivePrompt());
        scene.setNegativePrompt(assembled.negativePrompt());
        sceneRepository.save(scene);

        String referenceImagePath = resolvePrimaryReferenceImage(sceneCharacters);
        var request = new ImageGenerationProvider.ImageGenerationRequest(
                assembled.positivePrompt(), assembled.negativePrompt(), 0, 0, 0, 0, null,
                referenceImagePath != null ? "character-consistent-story-ipadapter" : null,
                styleModel(episode.getVisualStyle()), referenceImagePath);
        var result = providerGateway.generateImage(request);
        String relative = assetRelativePath(episode, String.format("images/scene-%03d.%s", scene.getSceneNumber(), result.fileExtension()));
        Path path = storageProvider.store(relative, result.imageBytes());
        saveAsset(episodeId, sceneId, AssetType.IMAGE, path, result.workflowUsed(), result.modelUsed(), result.seedUsed());
        return scene;
    }

    /**
     * Force-regenerates exactly one scene's narration, the audio counterpart
     * to {@link #regenerateSingleSceneImage}. Bypasses generateSceneAudio's
     * skip-if-already-generated check and refuses outright if the scene's
     * narration is locked.
     */
    public Scene regenerateSingleSceneNarration(UUID episodeId, UUID sceneId) {
        Scene scene = sceneRepository.findById(sceneId)
                .filter(s -> s.getEpisodeId().equals(episodeId))
                .orElseThrow(() -> new IllegalArgumentException("Scene not found for this episode."));
        if (scene.isNarrationLocked()) {
            throw new IllegalStateException(
                    "Scene " + scene.getSceneNumber() + " narration is locked. Unlock it before regenerating.");
        }
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));

        List<VoiceSegment> segments = readVoiceSegments(scene);
        if (segments.isEmpty()) {
            segments = List.of(new VoiceSegment("Narrator", scene.getNarration(), "", 1.0, 1.0, scene.getEmotion(), 0, 0));
        }
        List<byte[]> parts = new ArrayList<>();
        for (VoiceSegment seg : segments) {
            if (seg.text() == null || seg.text().isBlank()) continue;
            double pauseScale = lookupEmotionProsody(seg.emotion()).pauseScale();
            maybeInsertBreath(parts, seg.pauseBeforeMs());
            appendSilence(parts, scaledPause(seg.pauseBeforeMs(), pauseScale));
                    // One synthesis call per voice segment keeps the same voice and
                    // prosody context across the sentence. Punctuation remains inside
                    // the request; explicit pauses are added outside it.
                    Prosody p = prosody(seg);
                    var tts = providerGateway.synthesize(new TextToSpeechProvider.TtsRequest(
                            seg.text().trim(),
                            seg.voice() == null || seg.voice().isBlank() ? null : seg.voice(),
                            episode.getLanguage(), p.speed(), p.pitch()));
                    parts.add(tts.audioBytes());
            appendSilence(parts, scaledPause(seg.pauseAfterMs(), pauseScale));
        }
        if (parts.isEmpty()) {
            throw new IllegalStateException("Scene " + scene.getSceneNumber() + " has no spoken text.");
        }
        byte[] combined = concatenateWav(parts);
        combined = mediaProcessor.humanizeVoice(combined);
        Path path = storageProvider.store(assetRelativePath(episode, String.format("audio/scene-%03d.wav", scene.getSceneNumber())), combined);
        saveAsset(episodeId, sceneId, AssetType.AUDIO_NARRATION, path, "tts-multi-voice", null, null);
        double duration = probeWavDuration(combined);
        if (duration > 0) {
            scene.setNarrationSeconds(duration);
            scene.setImageDurationSeconds(duration + 0.6);
        }
        sceneRepository.save(scene);
        return scene;
    }
}
