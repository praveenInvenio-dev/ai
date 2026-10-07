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
import com.aistorystudio.provider.VisionProvider;
import com.aistorystudio.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Path;
import java.nio.file.Files;
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
    private final CharacterReferenceRepository characterReferenceRepository;
    private final com.aistorystudio.repository.VoiceProfileRepository voiceProfileRepository;
    private final JobService jobService;
    private final ProviderGateway providerGateway;
    private final StorageProvider storageProvider;
    private final MediaProcessor mediaProcessor;
    private final SubtitleService subtitleService;
    private final EpisodeMemoryService episodeMemoryService;
    private final ImagePromptAssembler imagePromptAssembler = new ImagePromptAssembler();
    private final ObjectMapper mapper = new ObjectMapper();
    private final H3StoryboardPromptBuilder h3PromptBuilder = new H3StoryboardPromptBuilder(mapper);

    /** Classic Story Production is deliberately image-based. MiniMax H3 is used here only
     * as the soundtrack engine; standalone/video-sequence H3 remains available elsewhere. */
    @Value("${studio.audio.h3.enabled-in-story-pipeline:true}")
    private boolean h3AudioEnabledInStoryPipeline;
    @Value("${studio.audio.h3.turbo:true}")
    private boolean h3AudioTurbo;
    @Value("${studio.audio.h3.max-duration-seconds:10.0}")
    private double h3AudioMaxDurationSeconds;
    @Value("${studio.audio.rumik.enabled:true}")
    private boolean rumikSpeechEnabled;
    @Value("${studio.audio.rumik.voice:Ira}")
    private String rumikVoice;
    @Value("${studio.audio.rumik.base-url:http://tts-rumik:5006}")
    private String rumikBaseUrl;
    /** vertical (default, 1080x1920, Shorts/Reels native) or horizontal (1920x1080). */
    @Value("${studio.video.orientation:vertical}") private String videoOrientation;

    private boolean horizontalVideo() {
        return "horizontal".equalsIgnoreCase(videoOrientation) || "landscape".equalsIgnoreCase(videoOrientation);
    }

    /** Scene image base size for Qwen: 0 = provider default 768x1344 (9:16);
     *  horizontal episodes get 1344x768 so images match the 16:9 video. */
    private int sceneImageWidth() { return horizontalVideo() ? 1344 : 0; }
    private int sceneImageHeight() { return horizontalVideo() ? 768 : 0; }

    /** Hint for ImagePromptAssembler's token budget: every image is Qwen Image 2.1. */
    private static final String QWEN_PROMPT_MODEL = "qwen_image_2.1";
    @Value("${studio.quality.imageValidation.enabled:true}") private boolean imageValidationEnabled;
    @Value("${studio.quality.imageValidation.maxRetries:1}") private int imageValidationRetries;
    @Value("${studio.music.autoEnabled:true}") private boolean autoMusicEnabled;

    public ProductionPipelineService(EpisodeRepository episodeRepository, SceneRepository sceneRepository,
                                      AssetRepository assetRepository, StoryBibleRepository storyBibleRepository,
                                      CharacterRepository characterRepository, JobService jobService,
                                      ProviderGateway providerGateway, StorageProvider storageProvider,
                                      MediaProcessor mediaProcessor, SubtitleService subtitleService,
                                      EpisodeMemoryService episodeMemoryService,
                                      com.aistorystudio.repository.VoiceProfileRepository voiceProfileRepository,
                                      CharacterReferenceRepository characterReferenceRepository) {
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
        this.voiceProfileRepository = voiceProfileRepository;
        this.characterReferenceRepository = characterReferenceRepository;
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
            List<Character> chars = charactersForEpisode(episode);
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

            List<Character> universeCharacters = charactersForEpisode(episode);

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
                // Characters owning a locked/primary reference come first, so "first /
                // second character" in the prompt lines up with <image1>/<image2>.
                SceneRefs refs = qwenSceneReferences(resolveSceneCharacters(scene, universeCharacters));
                List<Character> sceneCharacters = refs.ordered();
                String referenceImagePath = refs.ref1();
                String referenceImagePath2 = refs.ref2();
                var assembled = imagePromptAssembler.assemble(scene, sceneCharacters, List.of(), visualStyle,
                        colorPalette, QWEN_PROMPT_MODEL);

                // persist final prompt actually used, versioned (spec section 31)
                scene.setImagePrompt(assembled.positivePrompt());
                scene.setNegativePrompt(assembled.negativePrompt());
                sceneRepository.save(scene);

                ImageGenerationProvider.ImageGenerationResult result0 = null;
                String validationFeedback = "";
                int attempts = Math.max(0, imageValidationRetries) + 1;
                for (int attempt = 0; attempt < attempts; attempt++) {
                    String prompt = assembled.positivePrompt();
                    if (!validationFeedback.isBlank()) {
                        prompt += ", corrected from previous QA feedback: " + validationFeedback;
                    }
                    Long stableSeed = stableSceneSeed(episode, scene, sceneCharacters);
                    // Size/cfg 0 = provider defaults (768x1344 -> 1080x1920, cfg 1).
                    var request = new ImageGenerationProvider.ImageGenerationRequest(
                            prompt, assembled.negativePrompt(), sceneImageWidth(), sceneImageHeight(), qualityImageSteps(episode), 0,
                            stableSeed, null, null, referenceImagePath, referenceImagePath2);
                    result0 = providerGateway.generateImage(request);
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
            Path existing = activeAudioPathFor(episode.getId(), scene.getId(), h3AudioEnabledInStoryPipeline);
            if (existing != null) {
                double existingDuration = probeAudioFileDuration(existing);
                if (existingDuration > 0) {
                    scene.setNarrationSeconds(existingDuration);
                    scene.setImageDurationSeconds(existingDuration + 0.6);
                    sceneRepository.save(scene);
                    log.info("Scene {} duration synchronized from existing {} audio: narration={}s, timeline={}s",
                            scene.getSceneNumber(), h3AudioEnabledInStoryPipeline ? "H3" : "TTS", fmt(existingDuration), fmt(existingDuration + 0.6));
                }
                result.put(scene.getId(), existing);
                continue;
            }
            var step = jobService.startStep(jobId, scene.getId(), "GENERATE_NARRATION", h3AudioEnabledInStoryPipeline ? "h3-audio" : "tts", null);
            long start = System.currentTimeMillis();
            try {
                Path path;
                String provider;
                if (h3AudioEnabledInStoryPipeline) {
                    path = generateSpeechH3SceneAudio(episode, scene);
                    provider = "tts+humanized-voice+h3-soundscape";
                } else {
                    path = generateTtsSceneAudio(episode, scene);
                    provider = "tts-multi-voice";
                }
                saveAsset(episode.getId(), scene.getId(), AssetType.AUDIO_NARRATION, path, provider, null, null);
                result.put(scene.getId(), path);
                double duration = probeAudioFileDuration(path);
                if (duration > 0) {
                    scene.setNarrationSeconds(duration);
                    scene.setImageDurationSeconds(duration + 0.6);
                    sceneRepository.save(scene);
                }
                jobService.completeStep(step.getId(), System.currentTimeMillis() - start);
            } catch (Exception e) {
                if (h3AudioEnabledInStoryPipeline) {
                    // H3 is the preferred classic-story audio engine, but a GPU/model outage
                    // must not destroy an otherwise valid story. Fall back to the existing TTS
                    // implementation for this scene and leave the warning visible in the job log.
                    try {
                        log.warn("H3 audio failed for scene {}: {}. Falling back to existing TTS.", scene.getSceneNumber(), e.getMessage());
                        Path fallback = generateTtsSceneAudio(episode, scene);
                        saveAsset(episode.getId(), scene.getId(), AssetType.AUDIO_NARRATION, fallback, "tts-fallback-after-h3", null, null);
                        result.put(scene.getId(), fallback);
                        double duration = probeAudioFileDuration(fallback);
                        if (duration > 0) {
                            scene.setNarrationSeconds(duration);
                            scene.setImageDurationSeconds(duration + 0.6);
                            sceneRepository.save(scene);
                        }
                        jobService.setStepWarning(step.getId(), "H3 audio failed; fell back to existing TTS: " + e.getMessage());
                        jobService.completeStep(step.getId(), System.currentTimeMillis() - start);
                        continue;
                    } catch (Exception fallbackError) {
                        e = fallbackError;
                    }
                }
                log.warn("Narration generation failed for scene {} of episode {}: {}",
                        scene.getSceneNumber(), episode.getId(), e.getMessage());
                jobService.failStep(step.getId(), e.getMessage(), 0);
                failures.add("scene " + scene.getSceneNumber() + " (" + e.getMessage() + ")");
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Narration generation failed for " + failures.size() + " of " + scenes.size()
                    + " scene(s): " + String.join("; ", failures));
        }
        return result;
    }

    /** Dialogue comes from the configured TTS provider; H3 supplies only non-verbal sound design. */
    private Path generateSpeechH3SceneAudio(Episode episode, Scene scene) {
        List<VoiceSegment> sourceSegments = readVoiceSegments(scene);
        if (sourceSegments.isEmpty()) sourceSegments = List.of(new VoiceSegment("Narrator", scene.getNarration(), "", 1.0, 1.0, scene.getEmotion(), 0, 0, null, null, List.of(), false, null, null));
        Path work = null;
        try {
            work = Files.createTempDirectory("tts-h3-scene-" + scene.getSceneNumber() + "-");
            List<byte[]> parts = new ArrayList<>();
            for (VoiceSegment seg : sourceSegments) {
                if (seg == null || seg.text() == null || seg.text().isBlank()) continue;
                double pauseScale = lookupEmotionProsody(seg.emotion()).pauseScale();
                appendSilence(parts, scaledPause(seg.pauseBeforeMs(), pauseScale));
                TextToSpeechProvider.TtsResult tts = synthesizeSegment(episode, seg, prosody(seg));
                if (tts == null || tts.audioBytes() == null || tts.audioBytes().length < 1000) throw new IllegalStateException("TTS returned empty speech");
                parts.add(tts.audioBytes());
                appendSilence(parts, scaledPause(seg.pauseAfterMs(), pauseScale));
            }
            if (parts.isEmpty()) throw new IllegalStateException("Scene " + scene.getSceneNumber() + " has no spoken text.");
            byte[] polished = mediaProcessor.humanizeVoice(concatenateWav(parts));
            Path speech = work.resolve("speech-humanized.wav"); Files.write(speech, polished);
            double duration = probeAudioFileDuration(speech);
            if (duration <= 0.2) throw new IllegalStateException("TTS produced unusable speech");
            Path soundscape = generateH3Soundscape(episode, scene, duration, work);
            Path mixed = work.resolve("scene-mixed.wav"); mixSpeechAndSoundscape(speech, soundscape, mixed);
            return storageProvider.store(assetRelativePath(episode, String.format("audio/scene-%03d-tts-h3.wav", scene.getSceneNumber())), Files.readAllBytes(mixed));
        } catch (Exception e) {
            throw new IllegalStateException("TTS + H3 audio generation failed for scene " + scene.getSceneNumber() + ": " + e.getMessage(), e);
        } finally {
            if (work != null) try (var walk = Files.walk(work)) { walk.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
        }
    }

    private Path generateH3Soundscape(Episode episode, Scene scene, double speechDuration, Path work) throws Exception {
        List<Path> parts = new ArrayList<>();
        double remaining = speechDuration;
        int part = 0;
        while (remaining > 0.15) {
            double target = Math.min(h3AudioMaxDurationSeconds, Math.max(3.0, remaining));
            String prompt = h3PromptBuilder.buildSoundscapeOnly("", scene.getAudioSpecJson(), episode.getLanguage(), scene.getEmotion(), target);
            var request = new com.aistorystudio.provider.VideoGenerationProvider.H3AudioRequest(
                    prompt, target, ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                    h3AudioTurbo ? 8 : 20, h3AudioTurbo);
            var generated = providerGateway.generateH3Audio(request);
            Path raw = work.resolve(String.format(Locale.ROOT, "sound-%03d.%s", part++, generated.fileExtension()));
            Files.write(raw, generated.audioBytes());
            if (probeAudioFileDuration(raw) <= 0.2) throw new IllegalStateException("H3 returned unusable soundscape audio");
            parts.add(raw);
            remaining -= target;
        }
        Path out = work.resolve("soundscape.flac");
        if (parts.size() == 1) Files.copy(parts.get(0), out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        else mergeAudioParts(parts, out);
        return out;
    }

    private void mixSpeechAndSoundscape(Path speech, Path soundscape, Path output) throws Exception {
        Process proc = new ProcessBuilder("ffmpeg", "-nostdin", "-y", "-i", speech.toString(), "-stream_loop", "-1", "-i", soundscape.toString(),
                "-filter_complex", "[0:a]aresample=48000,aformat=channel_layouts=stereo[voice];[1:a]aresample=48000,aformat=channel_layouts=stereo,volume=0.16[bed];[bed][voice]sidechaincompress=threshold=0.025:ratio=7:attack=25:release=350:makeup=1[ducked];[voice][ducked]amix=inputs=2:duration=first:dropout_transition=0:normalize=0,loudnorm=I=-16:TP=-1.0:LRA=7,alimiter=limit=0.97[a]", "-map", "[a]", "-ar", "48000", "-ac", "2", "-c:a", "pcm_s16le", output.toString()).redirectErrorStream(true).start();
        String logText = new String(proc.getInputStream().readAllBytes());
        if (proc.waitFor() != 0) throw new IllegalStateException("FFmpeg speech/soundscape mix failed: " + logText);
    }

    private void concatenateAudioWithPauses(List<Path> parts, List<VoiceSegment> segments, Path output) throws Exception {
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y"));
        List<String> inputs = new ArrayList<>();
        for (Path p : parts) { args.add("-i"); args.add(p.toString()); inputs.add("[" + (inputs.size()) + ":a]"); }
        StringBuilder filter = new StringBuilder();
        int n = parts.size();
        for (int i = 0; i < n; i++) filter.append("[").append(i).append(":a]aresample=24000[a").append(i).append("];" );
        for (int i = 0; i < n; i++) { filter.append("[a").append(i).append("]"); }
        filter.append("concat=n=").append(n).append(":v=0:a=1[a]");
        args.add("-filter_complex"); args.add(filter.toString()); args.add("-map"); args.add("[a]"); args.add("-c:a"); args.add("pcm_s16le"); args.add(output.toString());
        Process proc = new ProcessBuilder(args).redirectErrorStream(true).start();
        String logText = new String(proc.getInputStream().readAllBytes());
        if (proc.waitFor() != 0) throw new IllegalStateException("FFmpeg speech concat failed: " + logText);
    }

    private Path generateH3SceneAudio(Episode episode, Scene scene) {
        List<VoiceSegment> sourceSegments = readVoiceSegments(scene);
        if (sourceSegments.isEmpty()) {
            sourceSegments = List.of(new VoiceSegment("Narrator", scene.getNarration(), "", 1.0, 1.0,
                    scene.getEmotion(), 0, 0, null, null, List.of(), false, null, null));
        }
        List<List<VoiceSegment>> chunks = splitVoiceSegmentsForH3(sourceSegments);
        Path work = null;
        try {
            work = Files.createTempDirectory("h3-audio-scene-" + scene.getSceneNumber() + "-");
            List<Path> parts = new ArrayList<>();
            for (int part = 0; part < chunks.size(); part++) {
                List<VoiceSegment> chunk = chunks.get(part);
                int words = chunk.stream().map(VoiceSegment::text).filter(Objects::nonNull)
                        .mapToInt(x -> x.trim().isEmpty() ? 0 : x.trim().split("\\s+").length).sum();
                double target = Math.max(3.0, Math.min(h3AudioMaxDurationSeconds, words / 2.4 + 0.8));
                String chunkJson = mapper.writeValueAsString(chunk);
                String prompt = h3PromptBuilder.buildAudioOnly("", chunkJson, scene.getAudioSpecJson(),
                        episode.getLanguage(), scene.getEmotion(), target);
                var request = new com.aistorystudio.provider.VideoGenerationProvider.H3AudioRequest(
                        prompt, target, ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                        h3AudioTurbo ? 8 : 20, h3AudioTurbo);
                var generated = providerGateway.generateH3Audio(request);
                Path raw = work.resolve(String.format(Locale.ROOT, "part-%03d.%s", part, generated.fileExtension()));
                Files.write(raw, generated.audioBytes());
                double actual = com.aistorystudio.sequence.ClipMerger.probeDuration(raw);
                if (actual <= 0.2) throw new IllegalStateException("H3 returned an unusable audio segment for scene " + scene.getSceneNumber());
                parts.add(raw);
            }
            if (parts.isEmpty()) throw new IllegalStateException("H3 produced no audio for scene " + scene.getSceneNumber());
            Path merged = work.resolve("scene-audio.flac");
            if (parts.size() == 1) Files.copy(parts.get(0), merged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            else mergeAudioParts(parts, merged);
            Path stored = storageProvider.store(assetRelativePath(episode,
                    String.format("audio/scene-%03d-h3.flac", scene.getSceneNumber())), Files.readAllBytes(merged));
            return stored;
        } catch (Exception e) {
            throw new IllegalStateException("H3 audio generation failed for scene " + scene.getSceneNumber() + ": " + e.getMessage(), e);
        } finally {
            if (work != null) {
                try (var walk = Files.walk(work)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (Exception ignored) {} });
                } catch (Exception ignored) {}
            }
        }
    }

    private List<List<VoiceSegment>> splitVoiceSegmentsForH3(List<VoiceSegment> source) {
        int maxWords = Math.max(8, (int) Math.floor(Math.max(3.0, h3AudioMaxDurationSeconds - 0.8) * 2.4));
        List<List<VoiceSegment>> chunks = new ArrayList<>();
        List<VoiceSegment> current = new ArrayList<>();
        int currentWords = 0;
        for (VoiceSegment segment : source) {
            if (segment == null || segment.text() == null || segment.text().isBlank()) continue;
            String[] words = segment.text().trim().split("\\s+");
            if (words.length <= maxWords) {
                if (!current.isEmpty() && currentWords + words.length > maxWords) {
                    chunks.add(current); current = new ArrayList<>(); currentWords = 0;
                }
                current.add(segment);
                currentWords += words.length;
                continue;
            }
            for (int i = 0; i < words.length; i += maxWords) {
                int end = Math.min(words.length, i + maxWords);
                String text = String.join(" ", Arrays.copyOfRange(words, i, end));
                VoiceSegment split = new VoiceSegment(segment.character(), text, segment.voice(), segment.speed(), segment.pitch(),
                        segment.emotion(), i == 0 ? segment.pauseBeforeMs() : 0,
                        end == words.length ? segment.pauseAfterMs() : 0, segment.emotionIntensity(), segment.delivery(),
                        segment.emphasis(), segment.breath(), segment.paralinguisticEvent(), segment.actingDirection());
                if (!current.isEmpty()) { chunks.add(current); current = new ArrayList<>(); currentWords = 0; }
                chunks.add(new ArrayList<>(List.of(split)));
            }
        }
        if (!current.isEmpty()) chunks.add(current);
        if (chunks.isEmpty()) chunks.add(List.of(new VoiceSegment("Narrator", "", "", 1.0, 1.0, "neutral", 0, 0, null, null, List.of(), false, null, null)));
        return chunks;
    }

    private double estimatedAudioDuration(Scene scene) {
        if (scene.getNarrationSeconds() != null && scene.getNarrationSeconds() > 0) return scene.getNarrationSeconds();
        String text = scene.getNarration() == null ? "" : scene.getNarration().trim();
        try {
            List<VoiceSegment> segments = readVoiceSegments(scene);
            if (!segments.isEmpty()) text = segments.stream().map(VoiceSegment::text).filter(Objects::nonNull).reduce("", (a,b) -> a + " " + b).trim();
        } catch (Exception ignored) {}
        int words = text.isBlank() ? 12 : text.split("\\s+").length;
        return Math.max(3.0, Math.min(60.0, words / 2.4 + 0.8));
    }

    private void mergeAudioParts(List<Path> parts, Path output) {
        try {
            Path list = Files.createTempFile("h3-audio-concat-", ".txt");
            try {
                StringBuilder b = new StringBuilder();
                for (Path part : parts) b.append("file '").append(part.toAbsolutePath().toString().replace("'", "'\\''")).append("'\\n");
                Files.writeString(list, b.toString());
                Process proc = new ProcessBuilder("ffmpeg", "-nostdin", "-y", "-f", "concat", "-safe", "0", "-i", list.toString(), "-c:a", "flac", output.toString())
                        .redirectErrorStream(true).start();
                String logText = new String(proc.getInputStream().readAllBytes());
                if (proc.waitFor() != 0) throw new IllegalStateException("FFmpeg audio concat failed: " + logText);
            } finally { Files.deleteIfExists(list); }
        } catch (Exception e) { throw new IllegalStateException("Could not merge H3 audio segments", e); }
    }

    private Path generateTtsSceneAudio(Episode episode, Scene scene) {
        List<VoiceSegment> segments = readVoiceSegments(scene);
        if (segments.isEmpty()) segments = List.of(new VoiceSegment("Narrator", scene.getNarration(), "", 1.0, 1.0, scene.getEmotion(), 0, 0, null, null, List.of(), false, null, null));
        List<byte[]> parts = new ArrayList<>();
        for (VoiceSegment seg : segments) {
            if (seg.text() == null || seg.text().isBlank()) continue;
            double pauseScale = lookupEmotionProsody(seg.emotion()).pauseScale();
            maybeInsertBreath(parts, seg.pauseBeforeMs(), seg.breath());
            appendSilence(parts, scaledPause(seg.pauseBeforeMs(), pauseScale));
            Prosody p = prosody(seg);
            parts.add(synthesizeSegment(episode, seg, p).audioBytes());
            appendSilence(parts, scaledPause(seg.pauseAfterMs(), pauseScale));
        }
        if (parts.isEmpty()) throw new IllegalStateException("Scene " + scene.getSceneNumber() + " has no spoken text.");
        byte[] combined = mediaProcessor.humanizeVoice(concatenateWav(parts));
        return storageProvider.store(assetRelativePath(episode, String.format("audio/scene-%03d.wav", scene.getSceneNumber())), combined);
    }

    /** The current active AUDIO_NARRATION asset's file, if this scene already has one. */
    private Path activeAudioPathFor(UUID episodeId, UUID sceneId, boolean h3Preferred) {
        return assetRepository.findByEpisodeIdAndAssetType(episodeId, AssetType.AUDIO_NARRATION).stream()
                .filter(a -> sceneId.equals(a.getSceneId()) && a.isActive())
                // Do not reuse a legacy Rumik/H3-only asset after the new pipeline
                // is enabled. Otherwise an existing active row would make the scene
                // silently skip the new TTS + humanization + ducking path.
                .filter(a -> !h3Preferred || "tts+humanized-voice+h3-soundscape".equals(a.getProvider())
                        || (a.getFilePath() != null && a.getFilePath().contains("-tts-h3.")))
                .findFirst()
                .map(a -> Path.of(a.getFilePath()))
                .filter(p -> p.toFile().exists() && p.toFile().length() > 0)
                .orElse(null);
    }

    private record VoiceSegment(String character, String text, String voice, Double speed, Double pitch, String emotion, Integer pauseBeforeMs, Integer pauseAfterMs, Double emotionIntensity, String delivery, List<String> emphasis, Boolean breath, String paralinguisticEvent, String actingDirection) {}
    private record Prosody(double speed, double pitch, String emotion, Double intensity, String delivery, List<String> emphasis, Boolean breath, String paralinguisticEvent, String actingDirection) {}

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

    /** Resolves this segment's actual synthesis call: if its speaker is a
     *  Character with an assigned VoiceProfile (Phase 1's "same voice reusable
     *  across ANY story"), uses that profile's own provider/voice; otherwise
     *  falls back to the app-wide default TTS provider exactly as before this
     *  field existed. Character matching is by name (case-insensitive)
     *  against the episode's universe, same lookup style already used
     *  elsewhere in this class (readCharacterNames-style matching) - a
     *  "Narrator" segment or an unmatched name simply has no assignment to
     *  find, which is not an error, just the unassigned case. */
    private TextToSpeechProvider.TtsResult synthesizeSegment(Episode episode, VoiceSegment seg, Prosody p) {
        UUID voiceProfileId = explicitVoiceProfileId(seg.voice());
        if (voiceProfileId != null) {
            var profileOpt = voiceProfileRepository.findById(voiceProfileId);
            if (profileOpt.isPresent()) {
                var profile = profileOpt.get();
                return providerGateway.synthesizeWithVoice(
                        profile.getProvider(), profile.getVoiceName(), seg.text().trim(),
                        episode.getLanguage(), p.speed(), p.pitch(), p.emotion(), p.intensity(), p.delivery(),
                        p.emphasis(), p.breath(), p.paralinguisticEvent(), p.actingDirection(), profile.getReferenceTranscript());
            }
            log.warn("Explicit voice profile {} no longer exists; falling back to character/default voice.", voiceProfileId);
        }
        voiceProfileId = resolveAssignedVoiceProfileId(episode, seg.character());
        if (voiceProfileId == null && seg.character() != null && "narrator".equalsIgnoreCase(seg.character().trim())) {
            voiceProfileId = episode.getNarratorVoiceProfileId();
        }
        if (voiceProfileId != null) {
            var profileOpt = voiceProfileRepository.findById(voiceProfileId);
            if (profileOpt.isPresent()) {
                var profile = profileOpt.get();
                return providerGateway.synthesizeWithVoice(
                        profile.getProvider(), profile.getVoiceName(), seg.text().trim(),
                        episode.getLanguage(), p.speed(), p.pitch(), p.emotion(), p.intensity(), p.delivery(),
                        p.emphasis(), p.breath(), p.paralinguisticEvent(), p.actingDirection(), profile.getReferenceTranscript());
            }
            log.warn("Character '{}' has voiceProfileId {} but that VoiceProfile no longer exists - "
                    + "falling back to the default TTS provider for this line.", seg.character(), voiceProfileId);
        }
        TextToSpeechProvider.TtsRequest request = new TextToSpeechProvider.TtsRequest(
                seg.text().trim(), seg.voice() == null || seg.voice().isBlank() ? null : seg.voice(),
                episode.getLanguage(), p.speed(), p.pitch(), p.emotion(), p.intensity(), p.delivery(),
                p.emphasis(), p.breath(), p.paralinguisticEvent(), p.actingDirection());
        return providerGateway.synthesizeForStoryLanguage(request);
    }

    private List<Character> charactersForEpisode(Episode episode) {
        if (episode.getUniverseId() != null) return characterRepository.findByUniverseId(episode.getUniverseId());
        return characterRepository.findByEpisodeId(episode.getId());
    }

    private UUID explicitVoiceProfileId(String voice) {
        if (voice == null || !voice.startsWith("profile:")) return null;
        try { return UUID.fromString(voice.substring("profile:".length())); }
        catch (IllegalArgumentException e) { return null; }
    }

    private UUID resolveAssignedVoiceProfileId(Episode episode, String characterName) {
        // Previously also required episode.getUniverseId() != null, which
        // silently broke voice assignment for every standalone/episode-scoped
        // story (no universe) - charactersForEpisode() already correctly
        // falls back to findByEpisodeId() for that case, this guard just
        // hadn't been updated to match when episode-scoped characters were
        // added. A universe-less episode's characters can have assigned
        // voices too; this was the reason they never applied.
        if (characterName == null) return null;
        return charactersForEpisode(episode).stream()
                .filter(c -> characterName.equalsIgnoreCase(c.getName()))
                .map(Character::getVoiceProfileId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** FAST/BALANCED/QUALITY (Phase 4). Only overrides step count, not
     *  resolution - width/height stay at the configured default (0 = "use
     *  provider default") regardless of tier, since guessing the right
     *  resolution requires knowing whether the active checkpoint is SD1.5 or
     *  SDXL, which this method doesn't have visibility into; steps is a safe,
     *  checkpoint-agnostic lever (works for LCM/Lightning-style models this
     *  app is tuned around either way). BALANCED returns 0 - genuinely no
     *  override, identical to this app's behavior before quality profiles
     *  existed, not just "a middle value". */
    /** Qwen steps per profile. 0 = provider default (30). */
    private int qualityImageSteps(Episode episode) {
        return "FAST".equalsIgnoreCase(episode.getQualityProfile()) ? 20 : 0;
    }

    /** Same tier idea as qualityImageSteps() but for Wan video generation -
     *  a separate method rather than one shared function, since a sensible
     *  FAST/QUALITY step count for a diffusion image model and for a video
     *  model are not the same numbers (video's official reference default is
     *  already 20, not the ~20-30 typical for an SDXL image). BALANCED
     *  returns 0 (no override), same "identical to pre-profile behavior"
     *  guarantee as the image version. */
    private int qualityVideoSteps(Episode episode) {
        String tier = episode.getQualityProfile();
        if ("FAST".equalsIgnoreCase(tier)) return 10;
        if ("QUALITY".equalsIgnoreCase(tier)) return 20;
        return 0;
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
        // Intentional Voice Director variation replaces random jitter. A fixed voice should
        // remain stable across stories; emotional variation belongs in the segment metadata.
        double intensity = s.emotionIntensity() == null ? 0.5 : Math.max(0.0, Math.min(1.0, s.emotionIntensity()));
        double scale = 0.85 + (intensity * 0.15);
        speed *= scale;
        return new Prosody(Math.max(.6, Math.min(1.6, speed)), Math.max(.6, Math.min(1.5, pitch)),
                s.emotion(), s.emotionIntensity(), s.delivery(), s.emphasis() == null ? List.of() : s.emphasis(),
                s.breath(), s.paralinguisticEvent(), s.actingDirection());
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
    private void maybeInsertBreath(List<byte[]> parts, Integer pauseBeforeMs, Boolean requested) {
        if (pauseBeforeMs == null || pauseBeforeMs < 400) {
            return;
        }
        // Voice Director can explicitly request a breath. For older scenes that
        // have no breath field, retain the previous conservative 35% fallback.
        if (!Boolean.TRUE.equals(requested) && requested != null) return;
        if (!Boolean.TRUE.equals(requested) && ThreadLocalRandom.current().nextDouble() > 0.35) {
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

    private double probeAudioFileDuration(Path audio) {
        if (audio == null || !Files.isRegularFile(audio)) return -1;
        try { return com.aistorystudio.sequence.ClipMerger.probeDuration(audio); }
        catch (Exception e) { return probeWavFileDuration(audio); }
    }

    private double probeWavFileDuration(Path wav) {
        if (wav == null || !Files.isRegularFile(wav)) return -1;
        try (var ais = javax.sound.sampled.AudioSystem.getAudioInputStream(wav.toFile())) {
            return ais.getFrameLength() / ais.getFormat().getFrameRate();
        } catch (Exception e) {
            log.warn("Could not probe existing narration WAV {}: {}", wav, e.getMessage());
            return -1;
        }
    }

    private Path assembleVideo(Episode episode, List<Scene> scenes, Map<UUID, Path> images, Map<UUID, Path> audio) {
        List<MediaProcessor.SceneClip> clips = new ArrayList<>();
        for (Scene scene : scenes) {
            if (images.get(scene.getId()) == null || audio.get(scene.getId()) == null) {
                throw new IllegalStateException("Scene " + scene.getSceneNumber() + " is missing its image or narration audio.");
            }

            // The final render timeline must always follow the actual narration
            // file, not a stale/story-engine estimate. This prevents the renderer
            // from moving to the next image while the current sentence is still
            // speaking. Keep a short end beat after speech for a clean scene cut.
            double measuredAudio = durationFromSceneAudio(audio.get(scene.getId()));
            double duration = measuredAudio > 0
                    ? measuredAudio
                    : (scene.getImageDurationSeconds() != null && scene.getImageDurationSeconds() > 0
                        ? scene.getImageDurationSeconds()
                        : (scene.getNarrationSeconds() != null && scene.getNarrationSeconds() > 0
                            ? scene.getNarrationSeconds() + 0.75
                            : 5.0));
            if (measuredAudio > 0) {
                double narrationOnly = Math.max(0.0, measuredAudio - 0.75);
                scene.setNarrationSeconds(narrationOnly);
                scene.setImageDurationSeconds(duration);
                sceneRepository.save(scene);
            }

            // Classic Story Production is always image-based. H3 supplies only
            // the soundtrack; the existing 2.5D renderer remains responsible for
            // camera/parallax/particle motion on the generated still image.
            clips.add(new MediaProcessor.SceneClip(images.get(scene.getId()), audio.get(scene.getId()), duration,
                    scene.getCameraMovement(), scene.getTransitionIn(), scene.getEmotion(),
                    scene.getLighting(), scene.getAction(), scene.getLocation(), scene.getImportance(),
                    scene.getAnimationMode(), null));
            MediaProcessor.SceneClip built = clips.get(clips.size() - 1);
            log.info("Scene {} classic image-only motion profile: {}", scene.getSceneNumber(),
                    mediaProcessor.buildMotionProfileJson(built));
        }
        Path outputPath = storageProvider.resolve(assetRelativePath(episode, "video/final.mp4"));
        // H3 audio mode already contains narration/dialogue + ambience + SFX +
        // background music. Adding the old music bed would double the soundtrack.
        Path musicPath = h3AudioEnabledInStoryPipeline ? null : resolveMusicPath(episode, scenes);
        Path video = mediaProcessor.assembleVideo(new MediaProcessor.VideoAssemblyRequest(clips, musicPath, outputPath,
                horizontalVideo() ? 1920 : 1080, horizontalVideo() ? 1080 : 1920));
        saveAsset(episode.getId(), null, AssetType.VIDEO, video, "ffmpeg", null, null);
        return video;
    }

    private double durationFromSceneAudio(Path audioPath) {
        double audioDuration = probeAudioFileDuration(audioPath);
        // Scene audio is already the complete Rumik/H3 mix. Add a small visual
        // end beat rather than trimming the final speech sample at a scene cut.
        return audioDuration > 0 ? audioDuration + 0.75 : -1;
    }

    /**
     * Attempts real local AI image-to-video for one scene, animating the
     * scene's own already-generated image (which is itself character-
     * consistent when a reference exists - see qwenSceneReferences)
     * as the starting frame. This is the actual consistency mechanism: the
     * video model never re-imagines the character from text, it animates
     * the exact frame that was already generated for this scene.
     *
     * Returns null (never throws) on any failure - the caller always has a
     * working 2.5D fallback, and a slow/broken video model should degrade
     * the scene's look, not break the whole episode's generation.
     */
    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
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
            preset = audioSpecMusicPreset(scenes);
            if (preset == null) preset = autoMusicPreset(scenes);
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

    /** Prefer the LLM's structured AudioSceneSpec mood when it maps cleanly to
     * one of the bundled production beds. If absent/unknown, preserve the older
     * deterministic keyword heuristic. */
    private String audioSpecMusicPreset(List<Scene> scenes) {
        if (scenes == null) return null;
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (Scene scene : scenes) {
            try {
                if (scene.getAudioSpecJson() == null || scene.getAudioSpecJson().isBlank()) continue;
                var root = mapper.readTree(scene.getAudioSpecJson());
                String mood = root.path("music").path("mood").asText("").toLowerCase(Locale.ROOT);
                String mapped = null;
                if (mood.matches(".*(adventure|energetic|playful|excited|epic|wonder).*")) mapped = "adventurous";
                else if (mood.matches(".*(sad|emotional|tender|melancholy|dramatic|lonely).*")) mapped = "emotional";
                else if (mood.matches(".*(calm|gentle|peaceful|warm|magical|bedtime).*")) mapped = "calm";
                if (mapped != null) counts.merge(mapped, 1, Integer::sum);
            } catch (Exception ignored) { }
        }
        return counts.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey).orElse(null);
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

    /** Scene characters reordered so the (max 2) that own a locked/primary
     *  reference come first, plus those reference paths in the same order. */
    private record SceneRefs(List<Character> ordered, String ref1, String ref2) {}

    private SceneRefs qwenSceneReferences(List<Character> sceneCharacters) {
        List<Character> withRef = new ArrayList<>();
        List<Character> without = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (Character c : sceneCharacters) {
            String path = paths.size() < 2 ? referenceImageForCharacter(c) : null;
            if (path != null) {
                withRef.add(c);
                paths.add(path);
            } else {
                without.add(c);
            }
        }
        withRef.addAll(without);
        return new SceneRefs(withRef, paths.isEmpty() ? null : paths.get(0), paths.size() > 1 ? paths.get(1) : null);
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
            // Order here matters, not just membership: for a 2-character
            // scene, this list's order decides which character is mentioned
            // first in the prompt (and so owns <image1>, see
            // qwenSceneReferences). The previous version filtered
            // universeCharacters and kept ITS order (database insertion
            // order) - completely disconnected from the scene's own
            // narrative order. If the scene/prompt says "Bunny on the left,
            // Squirrel on the right" but Squirrel happened to be inserted
            // into the DB first, the mask assignment would be backwards
            // from what the text prompt describes - a real, direct cause of
            // the reported character-mixing, not just a style preference.
            // Iterate the SCENE's own name order instead, looking up each
            // Character from universeCharacters as we go.
            Map<String, Character> byName = universeCharacters.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            c -> c.getName().toLowerCase(), c -> c, (a, b) -> a));
            List<Character> ordered = new ArrayList<>();
            for (String name : names) {
                Character c = byName.get(name);
                if (c != null) ordered.add(c);
            }
            return ordered;
        } catch (Exception e) {
            return List.of();
        }
    }



    private String referenceImageForCharacter(Character c) {
        List<CharacterReference> refs = characterReferenceRepository.findByCharacterId(c.getId());
        if (refs.isEmpty()) {
            return null;
        }
        CharacterReference chosen = refs.stream()
                .filter(CharacterReference::isLocked)
                .findFirst()
                .orElseGet(() -> refs.stream()
                        .filter(CharacterReference::isPrimary)
                        .findFirst()
                        .orElseGet(() -> refs.stream()
                        .max(java.util.Comparator.comparing(CharacterReference::getCreatedAt))
                        .orElse(refs.get(0))));
        Path path = Path.of(chosen.getImagePath());
        if (path.toFile().exists() && path.toFile().length() > 0) {
            return path.toString();
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
        List<Character> characters = charactersForEpisode(episode);

        SceneRefs refs = qwenSceneReferences(resolveSceneCharacters(scene, characters));
        List<Character> sceneCharacters = refs.ordered();
        String colorPalette = extractColorPalette(bible);
        var assembled = imagePromptAssembler.assemble(scene, sceneCharacters, List.of(), episode.getVisualStyle(),
                colorPalette, QWEN_PROMPT_MODEL);
        scene.setImagePrompt(assembled.positivePrompt());
        scene.setNegativePrompt(assembled.negativePrompt());
        sceneRepository.save(scene);

        var request = new ImageGenerationProvider.ImageGenerationRequest(
                assembled.positivePrompt(), assembled.negativePrompt(), sceneImageWidth(), sceneImageHeight(), qualityImageSteps(episode), 0, null,
                null, null, refs.ref1(), refs.ref2());
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
            segments = List.of(new VoiceSegment("Narrator", scene.getNarration(), "", 1.0, 1.0, scene.getEmotion(), 0, 0, null, null, List.of(), false, null, null));
        }
        List<byte[]> parts = new ArrayList<>();
        for (VoiceSegment seg : segments) {
            if (seg.text() == null || seg.text().isBlank()) continue;
            double pauseScale = lookupEmotionProsody(seg.emotion()).pauseScale();
            maybeInsertBreath(parts, seg.pauseBeforeMs(), seg.breath());
            appendSilence(parts, scaledPause(seg.pauseBeforeMs(), pauseScale));
                    // One synthesis call per voice segment keeps the same voice and
                    // prosody context across the sentence. Punctuation remains inside
                    // the request; explicit pauses are added outside it.
                    Prosody p = prosody(seg);
                    var tts = synthesizeSegment(episode, seg, p);
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
