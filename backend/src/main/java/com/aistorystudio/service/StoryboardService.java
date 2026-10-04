package com.aistorystudio.service;

import com.aistorystudio.config.ProviderGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.aistorystudio.domain.Asset;
import com.aistorystudio.domain.Character;
import com.aistorystudio.domain.CharacterReference;
import com.aistorystudio.domain.Episode;
import com.aistorystudio.domain.Scene;
import com.aistorystudio.domain.enums.AssetType;
import com.aistorystudio.domain.enums.EpisodeStatus;
import com.aistorystudio.provider.MediaProcessor;
import com.aistorystudio.provider.StorageProvider;
import com.aistorystudio.provider.TextToSpeechProvider;
import com.aistorystudio.provider.VideoGenerationProvider;
import com.aistorystudio.domain.Project;
import com.aistorystudio.repository.AssetRepository;
import com.aistorystudio.repository.EpisodeRepository;
import com.aistorystudio.repository.ProjectRepository;
import com.aistorystudio.repository.SceneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Path;
import java.util.*;

/**
 * Storyboard mode: build a video from images the user supplies, rather than
 * images the pipeline generates.
 *
 * Two entry points, one implementation underneath:
 *
 * <ul>
 *   <li><b>Custom story.</b> The user types a title, then adds scenes one at a
 *       time with narration text, and attaches an image to each.</li>
 *   <li><b>Existing story.</b> An episode already drafted by the LLM has scenes
 *       with narration; the user bulk-uploads images that are matched to those
 *       scenes in order.</li>
 * </ul>
 *
 * This deliberately reuses {@link Episode}, {@link Scene} and {@link Asset}
 * rather than introducing parallel entities. A storyboard episode is a normal
 * episode whose IMAGE assets happen to have provider "upload" instead of
 * "comfyui", which means the existing scene editor, subtitle builder and
 * production dashboard all work on it unchanged.
 *
 * It also means ComfyUI is not involved at all - which on hardware where a
 * single image takes minutes is the difference between a usable workflow and
 * an overnight one.
 */
@Service
public class StoryboardService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardService.class);

    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "webp");

    /** Used when a scene has no narration to time against. */
    private static final double DEFAULT_SCENE_SECONDS = 5.0;

    /**
     * Narration rarely ends exactly on the last syllable, and cutting an image
     * the instant the voice stops feels abrupt. A small tail is added to every
     * scene so the viewer gets a beat before the transition.
     */
    private static final double NARRATION_TAIL_SECONDS = 0.6;

    private final EpisodeRepository episodes;
    private final ProjectRepository projects;
    private final SceneRepository scenes;
    private final AssetRepository assets;
    private final StorageProvider storage;
    private final MediaProcessor mediaProcessor;
    private final ProviderGateway gateway;
    private final SubtitleService subtitleService;
    private final com.aistorystudio.repository.VoiceProfileRepository voiceProfileRepository;
    private final com.aistorystudio.repository.CharacterRepository characterRepository;
    private final com.aistorystudio.repository.CharacterReferenceRepository characterReferenceRepository;
    private final String defaultVoice;
    private final ObjectMapper objectMapper;
    private final H3StoryboardPromptBuilder h3PromptBuilder;

    public StoryboardService(EpisodeRepository episodes,
                             ProjectRepository projects,
                             SceneRepository scenes,
                             AssetRepository assets,
                             StorageProvider storage,
                             MediaProcessor mediaProcessor,
                             ProviderGateway gateway,
                             SubtitleService subtitleService,
                             ObjectMapper objectMapper,
                             com.aistorystudio.repository.VoiceProfileRepository voiceProfileRepository,
                             com.aistorystudio.repository.CharacterRepository characterRepository,
                             com.aistorystudio.repository.CharacterReferenceRepository characterReferenceRepository,
                             @Value("${studio.tts.voice:edge:en-IN-NeerjaNeural}") String defaultVoice) {
        this.episodes = episodes;
        this.projects = projects;
        this.scenes = scenes;
        this.assets = assets;
        this.storage = storage;
        this.mediaProcessor = mediaProcessor;
        this.gateway = gateway;
        this.subtitleService = subtitleService;
        this.objectMapper = objectMapper;
        this.h3PromptBuilder = new H3StoryboardPromptBuilder(objectMapper);
        this.voiceProfileRepository = voiceProfileRepository;
        this.characterRepository = characterRepository;
        this.characterReferenceRepository = characterReferenceRepository;
        this.defaultVoice = defaultVoice;
    }

    // ---- episodes ----------------------------------------------------------

    public record CreateStoryboardRequest(UUID projectId, String title, String visualStyle,
                                          String language, Integer durationTargetSec) {}

    /**
     * Name of the auto-created container project. Storyboard mode is meant to be
     * usable without first setting up a project, but episode.project_id is
     * NOT NULL, so one has to exist. Reusing a single named project keeps
     * storyboards together instead of creating a new one per episode.
     */
    private static final String DEFAULT_PROJECT_NAME = "Storyboards";

    @Transactional
    public Episode createStoryboard(CreateStoryboardRequest request) {
        Episode episode = new Episode();
        episode.setProjectId(resolveProjectId(request.projectId()));
        episode.setTitle(request.title() == null || request.title().isBlank()
                ? "Untitled storyboard" : request.title().trim());
        episode.setVisualStyle(request.visualStyle());
        if (request.language() != null && !request.language().isBlank()) {
            episode.setLanguage(request.language());
        }
        episode.setDurationTargetSec(request.durationTargetSec());
        // Storyboards are image-first/H3-first. FAST selects the 10-step H3
        // profile while QUALITY can still be requested by an API client later.
        episode.setQualityProfile("FAST");
        // No LLM ran, so there is nothing to approve - the user is the author.
        // Marking it APPROVED keeps it out of the story-approval queue, which
        // would otherwise ask them to review their own writing.
        episode.setStatus(EpisodeStatus.APPROVED);
        episode.setUserPrompt("(storyboard - scenes entered by hand)");
        return episodes.save(episode);
    }

    /**
     * Uses the supplied project when there is one, otherwise finds or creates
     * the shared "Storyboards" project.
     *
     * An explicit id that does not exist is an error rather than something to
     * quietly replace with the default - silently filing an episode under the
     * wrong project is worse than a clear failure.
     */
    private UUID resolveProjectId(UUID requested) {
        if (requested != null) {
            if (!projects.existsById(requested)) {
                throw new NoSuchElementException("No project " + requested);
            }
            return requested;
        }
        return projects.findAll().stream()
                .filter(p -> DEFAULT_PROJECT_NAME.equals(p.getName()))
                .findFirst()
                .map(Project::getId)
                .orElseGet(() -> {
                    Project project = new Project();
                    project.setName(DEFAULT_PROJECT_NAME);
                    project.setDescription("Auto-created for storyboards built from uploaded images.");
                    log.info("Created the '{}' container project for storyboard episodes",
                            DEFAULT_PROJECT_NAME);
                    return projects.save(project).getId();
                });
    }

    // ---- scenes ------------------------------------------------------------

    public record VoiceSegmentInput(String character, String text, String voice,
                                    Double speed, Double pitch, String emotion,
                                    Integer pauseBeforeMs, Integer pauseAfterMs,
                                    Double emotionIntensity, String delivery, List<String> emphasis,
                                    Boolean breath, String paralinguisticEvent, String actingDirection) {
        public VoiceSegmentInput(String character, String text, String voice, Double speed, Double pitch, String emotion,
                                 Integer pauseBeforeMs, Integer pauseAfterMs) {
            this(character, text, voice, speed, pitch, emotion, pauseBeforeMs, pauseAfterMs, null, null, List.of(), false, null, null);
        }
    }

    public record SceneInput(String narration, String action, String location,
                             String emotion, Double durationSeconds,
                             List<VoiceSegmentInput> voiceSegments) {}

    @Transactional
    public Scene addScene(UUID episodeId, SceneInput input) {
        Episode episode = requireEpisode(episodeId);
        List<Scene> existing = scenes.findByEpisodeIdOrderByOrderIndexAsc(episode.getId());

        Scene scene = new Scene();
        scene.setEpisodeId(episode.getId());
        scene.setSceneNumber(existing.size() + 1);
        scene.setOrderIndex(existing.size());
        applySceneInput(scene, input);
        return scenes.save(scene);
    }

    @Transactional
    public Scene updateScene(UUID episodeId, UUID sceneId, SceneInput input) {
        Scene scene = requireScene(episodeId, sceneId);
        applySceneInput(scene, input);
        return scenes.save(scene);
    }

    private void applySceneInput(Scene scene, SceneInput input) {
        scene.setNarration(input.narration());
        scene.setAction(input.action());
        scene.setLocation(input.location());
        scene.setEmotion(input.emotion());
        if (input.voiceSegments() != null) {
            try {
                scene.setVoiceSegmentsJson(objectMapper.writeValueAsString(input.voiceSegments()));
            } catch (Exception e) {
                throw new IllegalArgumentException("Could not save the scene voice settings.", e);
            }
        }
        // Storyboard scenes are narration-driven: unless an explicit duration is
        // supplied by an API client, keep the shot duration synchronized with the
        // current narration. The UI intentionally has no manual duration field.
        if (input.durationSeconds() != null && input.durationSeconds() > 0) {
            scene.setImageDurationSeconds(clampSceneDuration(input.durationSeconds()));
        } else {
            scene.setImageDurationSeconds(estimateStoryboardDuration(input.narration(), input.voiceSegments()));
        }
    }

    @Transactional
    public void deleteScene(UUID episodeId, UUID sceneId) {
        Scene scene = requireScene(episodeId, sceneId);
        // Remove the scene's own assets too, otherwise they linger as orphans
        // pointing at a scene that no longer exists. Scoped to the episode:
        // findAll() would scan every asset in the database.
        assets.findByEpisodeId(episodeId).stream()
                .filter(a -> sceneId.equals(a.getSceneId()))
                .forEach(assets::delete);
        scenes.delete(scene);
        renumber(episodeId);
    }

    @Transactional
    public List<Scene> reorderScenes(UUID episodeId, List<UUID> orderedSceneIds) {
        Map<UUID, Scene> byId = new LinkedHashMap<>();
        for (Scene scene : scenes.findByEpisodeIdOrderByOrderIndexAsc(episodeId)) {
            byId.put(scene.getId(), scene);
        }
        if (byId.size() != orderedSceneIds.size() || !byId.keySet().containsAll(orderedSceneIds)) {
            throw new IllegalArgumentException(
                    "The reorder request must list every scene in this episode exactly once.");
        }
        int number = 1;
        for (UUID sceneId : orderedSceneIds) {
            Scene scene = byId.get(sceneId);
            scene.setSceneNumber(number);
            scene.setOrderIndex(number - 1);
            number++;
        }
        return scenes.saveAll(byId.values());
    }

    private void renumber(UUID episodeId) {
        List<Scene> remaining = scenes.findByEpisodeIdOrderByOrderIndexAsc(episodeId);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setSceneNumber(i + 1);
            remaining.get(i).setOrderIndex(i);
        }
        scenes.saveAll(remaining);
    }

    // ---- images ------------------------------------------------------------

    /** Attaches one uploaded image to one scene, replacing any previous one. */
    @Transactional
    public Asset attachImage(UUID episodeId, UUID sceneId, MultipartFile file) {
        Episode episode = requireEpisode(episodeId);
        Scene scene = requireScene(episodeId, sceneId);
        String extension = validateImage(file);

        Path stored = storage.store(
                imageRelativePath(episode, scene, extension), readBytes(file));

        // Supersede rather than delete: the existing pipeline treats IMAGE
        // assets as versioned with an `active` flag, and quietly deleting the
        // old row would break that convention for storyboard episodes only.
        assets.findByEpisodeIdAndAssetType(episodeId, AssetType.IMAGE).stream()
                .filter(a -> sceneId.equals(a.getSceneId()) && a.isActive())
                .forEach(a -> {
                    a.setActive(false);
                    assets.save(a);
                });

        Asset asset = new Asset();
        asset.setEpisodeId(episodeId);
        asset.setSceneId(sceneId);
        asset.setAssetType(AssetType.IMAGE);
        asset.setFilePath(stored.toString());
        asset.setProvider("upload");
        asset.setPrompt(scene.getNarration());
        asset.setActive(true);
        return assets.save(asset);
    }

    private static final java.util.Set<String> ALLOWED_MUSIC_EXTENSIONS = java.util.Set.of("mp3", "m4a", "wav", "ogg");

    /** Uploaded background music for the whole episode (episode-level asset,
     *  no sceneId - mirrors how VIDEO/SUBTITLE assets are stored). Always
     *  takes priority over the episode's music preset at render time (see
     *  resolveMusicPath in this class and ProductionPipelineService). */
    public Asset attachMusic(UUID episodeId, MultipartFile file) {
        Episode episode = requireEpisode(episodeId);
        if (episode.isMusicLocked()) {
            throw new IllegalStateException("Music is locked for this episode. Unlock it before uploading a new track.");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("That music file is empty.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_MUSIC_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "Unsupported music format. Use " + String.join(", ", ALLOWED_MUSIC_EXTENSIONS) + ".");
        }
        Path stored = storage.store(relativePath(episode, "music/upload." + extension), readBytes(file));

        assets.findByEpisodeIdAndAssetType(episodeId, AssetType.MUSIC).stream()
                .filter(Asset::isActive)
                .forEach(a -> { a.setActive(false); assets.save(a); });

        Asset asset = new Asset();
        asset.setEpisodeId(episodeId);
        asset.setSceneId(null);
        asset.setAssetType(AssetType.MUSIC);
        asset.setFilePath(stored.toString());
        asset.setProvider("upload");
        asset.setActive(true);
        return assets.save(asset);
    }

    /**
     * Bulk upload: files are matched to scenes in the order given.
     *
     * Order is the contract, and it is stated plainly to the caller because
     * filename-based matching is a trap - "scene1.png, scene10.png, scene2.png"
     * is what a filesystem sorts to, and silently building an episode in that
     * order would look like the app scrambling the story.
     */
    @Transactional
    public List<Asset> attachImagesInOrder(UUID episodeId, List<MultipartFile> files) {
        requireEpisode(episodeId);
        List<Scene> ordered = scenes.findByEpisodeIdOrderByOrderIndexAsc(episodeId);
        if (ordered.isEmpty()) {
            throw new IllegalStateException("This episode has no scenes to attach images to.");
        }
        if (files.size() != ordered.size()) {
            throw new IllegalArgumentException(
                    "This episode has " + ordered.size() + " scene(s) but " + files.size() +
                    " image(s) were uploaded. Upload exactly one image per scene, in scene order.");
        }
        List<Asset> created = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            created.add(attachImage(episodeId, ordered.get(i).getId(), files.get(i)));
        }
        return created;
    }

    @Transactional(readOnly = true)
    public List<Scene> scenesOf(UUID episodeId) {
        return scenes.findByEpisodeIdOrderByOrderIndexAsc(episodeId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, Asset> imagesBySceneId(UUID episodeId) {
        Map<UUID, Asset> result = new HashMap<>();
        for (Asset asset : assets.findByEpisodeIdAndAssetType(episodeId, AssetType.IMAGE)) {
            if (asset.isActive() && asset.getSceneId() != null) {
                result.put(asset.getSceneId(), asset);
            }
        }
        return result;
    }

    // ---- assembly ----------------------------------------------------------

    /**
     * Narrates every scene and assembles the video.
     *
     * Runs synchronously. TTS on this hardware is roughly real-time, so a
     * six-scene story is under a minute - fast enough that a progress stream
     * would be more machinery than the wait justifies. If narration ever moves
     * to a slower engine this should become a job.
     */
    @Transactional
    public Path assemble(UUID episodeId, String voice) {
        Episode episode = requireEpisode(episodeId);
        List<Scene> ordered = scenes.findByEpisodeIdOrderByOrderIndexAsc(episodeId);
        if (ordered.isEmpty()) {
            throw new IllegalStateException("Add at least one scene before generating the video.");
        }

        Map<UUID, Asset> images = imagesBySceneId(episodeId);
        List<String> missing = ordered.stream()
                .filter(s -> !images.containsKey(s.getId()))
                .map(s -> "scene " + s.getSceneNumber())
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No image uploaded for " + String.join(", ", missing) + ".");
        }

        List<MediaProcessor.SceneClip> clips = new ArrayList<>(ordered.size());
        boolean allNativeH3 = true;
        for (Scene scene : ordered) {
            // The storyboard UI is narration-driven. Recompute on every render so
            // editing the text immediately changes the H3 shot length.
            double duration = estimateStoryboardDuration(scene.getNarration(), readVoiceSegments(scene));
            scene.setImageDurationSeconds(duration);
            scenes.save(scene);

            Path aiVideoPath = null;
            try {
                if (providerGateway.isLocalAiVideoAvailable()) {
                    aiVideoPath = generateH3StoryboardVideo(episode, scene, images.get(scene.getId()), duration);
                }
            } catch (Exception e) {
                log.warn("H3 storyboard generation failed for scene {} - falling back to existing TTS/2.5D path: {}",
                        scene.getSceneNumber(), e.getMessage());
            }

            Path audioPath;
            if (aiVideoPath != null) {
                // H3 already contains synchronized native stereo audio. Generating
                // another TTS track would waste time and could replace the H3 audio.
                audioPath = null;
            } else {
                allNativeH3 = false;
                audioPath = narrate(episode, scene, voice);
            }

            clips.add(new MediaProcessor.SceneClip(
                    Path.of(images.get(scene.getId()).getFilePath()),
                    audioPath,
                    duration,
                    scene.getCameraMovement(),
                    scene.getTransitionIn(),
                    scene.getEmotion(),
                    scene.getLighting(),
                    scene.getAction(),
                    scene.getLocation(),
                    scene.getImportance(),
                    scene.getAnimationMode(),
                    aiVideoPath));
        }

        Path output = storage.resolve(relativePath(episode, "video/final.mp4"));
        // H3 supplies its own ambience/SFX/music. Only add the legacy music bed
        // when the episode contains at least one fallback scene.
        Path musicPath = allNativeH3 ? null : resolveMusicPath(episode);
        Path video = mediaProcessor.assembleVideo(
                new MediaProcessor.VideoAssemblyRequest(clips, musicPath, output, 1080, 1920));
        saveAsset(episode, null, AssetType.VIDEO, video, allNativeH3 ? "minimax-h3-native-audio" : "ffmpeg");

        String srt = subtitleService.buildSrt(ordered);
        Path srtPath = storage.store(relativePath(episode, "subtitles/subtitles.srt"),
                srt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        saveAsset(episode, null, AssetType.SUBTITLE, srtPath, "internal");

        episode.setStatus(EpisodeStatus.PRODUCTION_COMPLETE);
        episodes.save(episode);
        log.info("Storyboard episode {} assembled from {} images using H3 native audio={}.",
                episodeId, ordered.size(), allNativeH3);
        return video;
    }

    private Path generateH3StoryboardVideo(Episode episode, Scene scene, Asset image, double duration) {
        if (image == null || image.getFilePath() == null) return null;
        Path characterReference = resolveCharacterReference(episode, scene);
        List<String> characterNames = sceneCharacterNames(scene);
        String prompt = h3PromptBuilder.build(
                scene.getAction(), scene.getLocation(), scene.getEmotion(), scene.getNarration(),
                scene.getVoiceSegmentsJson(), scene.getAudioSpecJson(), episode.getLanguage(), episode.getVisualStyle(), duration,
                characterNames, characterReference != null);

        // FAST storyboard mode deliberately uses 10 steps on the existing W6A8
        // graph. It needs no extra LoRA/model file, unlike the newer distilled
        // FastH3 workflow, and is therefore safe for this deployed stack.
        int steps = characterReference != null ? 20 : ("QUALITY".equalsIgnoreCase(episode.getQualityProfile()) ? 20 : 8);
        var request = new VideoGenerationProvider.VideoGenerationRequest(
                image.getFilePath(), prompt, "static frame, blurry, distorted, extra limbs, identity drift, duplicate subject",
                duration, 0, 0, characterReference != null ? "minimax-h3-reference-to-video" : "minimax-h3-image-to-video", null,
                characterReference == null ? null : characterReference.toString(), null, steps);
        long started = System.currentTimeMillis();
        VideoGenerationProvider.VideoGenerationResult result = providerGateway.generateVideo(request);
        Path stored = storage.store(relativePath(episode, String.format("video/scene-%03d-h3.%s", scene.getSceneNumber(), result.fileExtension())), result.videoBytes());
        log.info("H3 storyboard scene {} generated in {}s, duration={}s, steps={}, bytes={}",
                scene.getSceneNumber(), (System.currentTimeMillis() - started) / 1000, duration, steps, result.videoBytes().length);
        return stored;
    }


    private List<String> sceneCharacterNames(Scene scene) {
        List<String> names = new ArrayList<>();
        try {
            if (scene.getCharactersJson() != null && !scene.getCharactersJson().isBlank()) {
                JsonNode arr = objectMapper.readTree(scene.getCharactersJson());
                if (arr.isArray()) for (JsonNode n : arr) {
                    String name = n.isTextual() ? n.asText() : n.path("name").asText("");
                    if (!name.isBlank()) names.add(name.trim());
                }
            }
        } catch (Exception ignored) {}
        return names;
    }

    private Path resolveCharacterReference(Episode episode, Scene scene) {
        List<String> names = sceneCharacterNames(scene);
        if (names.isEmpty()) return null;
        List<Character> chars = characterRepository.findByEpisodeId(episode.getId());
        if (chars.isEmpty() && episode.getUniverseId() != null) chars = characterRepository.findByUniverseId(episode.getUniverseId());
        for (String name : names) {
            Character c = chars.stream().filter(x -> x.getName() != null && x.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (c == null) continue;
            List<CharacterReference> refs = characterReferenceRepository.findByCharacterId(c.getId());
            CharacterReference ref = refs.stream().filter(CharacterReference::isLocked).findFirst()
                    .orElseGet(() -> refs.stream().filter(CharacterReference::isPrimary).findFirst().orElse(null));
            if (ref != null && ref.getImagePath() != null && java.nio.file.Files.isRegularFile(Path.of(ref.getImagePath()))) return Path.of(ref.getImagePath());
        }
        return null;
    }

    /** Same resolution order as ProductionPipelineService's: an uploaded
     *  MUSIC asset wins over the episode's preset selection, which wins over
     *  no music. Never throws - a bad/missing file just means no music. */
    private Path resolveMusicPath(Episode episode) {
        Path uploaded = assets.findByEpisodeIdAndAssetType(episode.getId(), AssetType.MUSIC).stream()
                .filter(Asset::isActive)
                .findFirst()
                .map(a -> Path.of(a.getFilePath()))
                .filter(p -> p.toFile().exists() && p.toFile().length() > 0)
                .orElse(null);
        if (uploaded != null) {
            return uploaded;
        }
        return mediaProcessor.musicPresetPath(episode.getMusicPreset());
    }

    private Path narrate(Episode episode, Scene scene, String voice) {
        List<VoiceSegmentInput> segments = readVoiceSegments(scene);
        if (segments.isEmpty()) {
            segments = List.of(new VoiceSegmentInput(
                    "Narrator", scene.getNarration() == null ? "" : scene.getNarration().trim(),
                    voice == null || voice.isBlank() ? defaultVoice : voice,
                    1.0, 1.0, scene.getEmotion(), 0, 0));
        }

        List<byte[]> audioParts = new ArrayList<>();
        for (VoiceSegmentInput segment : segments) {
            if (segment == null || segment.text() == null || segment.text().isBlank()) {
                continue;
            }
            maybeInsertBreath(audioParts, segment.pauseBeforeMs());
            double pauseScale = lookupEmotionProsody(segment.emotion()).pauseScale();
            appendSilence(audioParts, scaledPause(segment.pauseBeforeMs(), pauseScale));
            for (String part : splitPauseMarkers(insertPunctuationPauses(segment.text(), pauseScale))) {
                if (part.startsWith("\u0000PAUSE:")) {
                    appendSilence(audioParts, Integer.parseInt(part.substring("\u0000PAUSE:".length())));
                    continue;
                }
                String text = part.trim();
                if (text.isEmpty()) continue;
                Prosody prosody = prosody(segment);
                TextToSpeechProvider.TtsResult result;
                String selectedVoice = segment.voice() == null || segment.voice().isBlank() ? defaultVoice : segment.voice();
                if (selectedVoice.startsWith("profile:")) {
                    try {
                        UUID profileId = UUID.fromString(selectedVoice.substring("profile:".length()));
                        var profile = voiceProfileRepository.findById(profileId)
                                .orElseThrow(() -> new IllegalArgumentException("Voice profile not found: " + profileId));
                        result = gateway.synthesizeWithVoice(profile.getProvider(), profile.getVoiceName(), text,
                                episode.getLanguage(), prosody.speed(), prosody.pitch(), segment.emotion(),
                                segment.emotionIntensity() == null ? 0.5 : segment.emotionIntensity(), segment.delivery(),
                                segment.emphasis() == null ? List.of() : segment.emphasis(), segment.breath(),
                                segment.paralinguisticEvent(), segment.actingDirection(), profile.getReferenceTranscript());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalStateException("Invalid saved voice selection: " + selectedVoice, e);
                    }
                } else {
                    result = gateway.synthesize(new TextToSpeechProvider.TtsRequest(
                            text, selectedVoice, episode.getLanguage(), prosody.speed(), prosody.pitch()));
                }
                audioParts.add(result.audioBytes());
            }
            appendSilence(audioParts, scaledPause(segment.pauseAfterMs(), pauseScale));
        }

        if (audioParts.isEmpty()) {
            TextToSpeechProvider.TtsResult result = gateway.synthesize(
                    new TextToSpeechProvider.TtsRequest(" ", defaultVoice, episode.getLanguage(), 1.0, 1.0));
            audioParts.add(result.audioBytes());
        }

        byte[] combined = concatenateWav(audioParts);
        combined = mediaProcessor.humanizeVoice(combined);
        Path path = storage.store(
                relativePath(episode, "audio/scene-" + scene.getSceneNumber() + ".wav"), combined);
        double duration = probeWavDuration(combined);
        if (duration > 0) {
            scene.setNarrationSeconds(duration);
            scenes.save(scene);
        }
        saveAsset(episode, scene.getId(), AssetType.AUDIO_NARRATION, path, "tts-multi-voice");
        return path;
    }

    private List<VoiceSegmentInput> readVoiceSegments(Scene scene) {
        if (scene.getVoiceSegmentsJson() == null || scene.getVoiceSegmentsJson().isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(scene.getVoiceSegmentsJson(), new TypeReference<List<VoiceSegmentInput>>() {});
        } catch (Exception e) {
            log.warn("Ignoring invalid voice segment JSON for scene {}: {}", scene.getId(), e.getMessage());
            return new ArrayList<>();
        }
    }

    private record Prosody(double speed, double pitch, double pauseScale) {}

    private static final Map<String, Prosody> EMOTION_PROSODY = Map.ofEntries(
            Map.entry("happy", new Prosody(1.06, 1.06, 0.85)),
            Map.entry("joyful", new Prosody(1.06, 1.06, 0.85)),
            Map.entry("cheerful", new Prosody(1.06, 1.06, 0.85)),
            Map.entry("excited", new Prosody(1.14, 1.12, 0.65)),
            Map.entry("thrilled", new Prosody(1.14, 1.12, 0.65)),
            Map.entry("sad", new Prosody(0.90, 0.94, 1.35)),
            Map.entry("melancholy", new Prosody(0.90, 0.94, 1.35)),
            Map.entry("sorrowful", new Prosody(0.90, 0.94, 1.35)),
            Map.entry("calm", new Prosody(0.92, 0.97, 1.25)),
            Map.entry("gentle", new Prosody(0.92, 0.97, 1.25)),
            Map.entry("peaceful", new Prosody(0.92, 0.97, 1.25)),
            Map.entry("angry", new Prosody(1.10, 1.05, 0.75)),
            Map.entry("furious", new Prosody(1.14, 1.08, 0.65)),
            Map.entry("scared", new Prosody(1.12, 1.10, 0.70)),
            Map.entry("afraid", new Prosody(1.12, 1.10, 0.70)),
            Map.entry("fearful", new Prosody(1.12, 1.10, 0.70)),
            Map.entry("whisper", new Prosody(0.90, 0.92, 1.15)),
            Map.entry("nervous", new Prosody(1.08, 1.06, 0.80)),
            Map.entry("anxious", new Prosody(1.08, 1.06, 0.80)),
            Map.entry("surprised", new Prosody(1.10, 1.10, 0.75)),
            Map.entry("shocked", new Prosody(1.12, 1.12, 0.70)),
            Map.entry("tender", new Prosody(0.90, 0.98, 1.30)),
            Map.entry("loving", new Prosody(0.92, 0.98, 1.20)),
            Map.entry("tired", new Prosody(0.86, 0.92, 1.30)),
            Map.entry("sleepy", new Prosody(0.84, 0.90, 1.35)),
            Map.entry("confident", new Prosody(1.02, 1.02, 0.90)),
            Map.entry("determined", new Prosody(1.04, 1.02, 0.85)),
            Map.entry("mysterious", new Prosody(0.90, 0.95, 1.30)),
            Map.entry("curious", new Prosody(1.02, 1.04, 0.90)),
            Map.entry("worried", new Prosody(1.02, 1.02, 1.10))
    );

    /** Exact then fuzzy contains-match; blank guarded before the fuzzy loop
     *  since "" is a substring of every key. Same table as
     *  ProductionPipelineService's - duplicated rather than shared, per
     *  this class's existing pattern of keeping its own independent copy. */
    private Prosody lookupEmotionProsody(String emotion) {
        String e = emotion == null ? "" : emotion.toLowerCase(Locale.ROOT).trim();
        if (e.isEmpty()) {
            return new Prosody(1.0, 1.0, 1.0);
        }
        Prosody exact = EMOTION_PROSODY.get(e);
        if (exact != null) {
            return exact;
        }
        for (var entry : EMOTION_PROSODY.entrySet()) {
            if (e.contains(entry.getKey()) || entry.getKey().contains(e)) {
                return entry.getValue();
            }
        }
        return new Prosody(1.0, 1.0, 1.0);
    }

    private Integer scaledPause(Integer ms, double scale) {
        return ms == null ? null : (int) Math.round(ms * scale);
    }

    private Prosody prosody(VoiceSegmentInput s) {
        double speed = s.speed() == null || s.speed() <= 0 ? 1.0 : s.speed();
        double pitch = s.pitch() == null || s.pitch() <= 0 ? 1.0 : s.pitch();
        Prosody p = lookupEmotionProsody(s.emotion());
        speed *= p.speed();
        pitch *= p.pitch();
        double jitter = 1.0 + (java.util.concurrent.ThreadLocalRandom.current().nextDouble() - 0.5) * 0.04;
        speed *= jitter;
        return new Prosody(Math.max(0.6, Math.min(1.6, speed)), Math.max(0.6, Math.min(1.5, pitch)), p.pauseScale());
    }

    /** Splits [pause:500] markers into real silence without sending the marker to TTS. */
    private List<String> splitPauseMarkers(String text) {
        List<String> parts = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[pause\\s*:\s*(\\d{1,5})\\s*ms?\\]", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) parts.add(text.substring(last, m.start()));
            parts.add("\u0000PAUSE:" + m.group(1));
            last = m.end();
        }
        if (last < text.length()) parts.add(text.substring(last));
        return parts.isEmpty() ? List.of(text) : parts;
    }

    private static final java.util.regex.Pattern PUNCTUATION_PAUSE_PATTERN =
            java.util.regex.Pattern.compile("(\\.\\.\\.|\u2026)|([.!?]+)|([,;:])|(--|\u2014)");

    /** Same punctuation-based auto-pause logic as ProductionPipelineService's
     *  version (spec: pauses at commas/periods/etc.) - duplicated rather than
     *  shared since this class already keeps its own independent copy of
     *  appendSilence/splitPauseMarkers/concatenateWav. */
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
                ms = 600;
            } else if (m.group(2) != null) {
                int i = m.start();
                boolean decimal = m.group(2).equals(".") && i > 0 && java.lang.Character.isDigit(text.charAt(i - 1))
                        && i + 1 < text.length() && java.lang.Character.isDigit(text.charAt(i + 1));
                ms = decimal ? 0 : 450;
            } else if (m.group(3) != null) {
                ms = 220;
            } else {
                ms = 350;
            }
            if (ms > 0) {
                ms = (int) Math.round(ms * pauseScale);
                out.append(" [pause:").append(ms).append("ms]");
            }
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    private void appendSilence(List<byte[]> parts, Integer milliseconds) {
        int ms = milliseconds == null ? 0 : Math.max(0, Math.min(10000, milliseconds));
        if (ms == 0) return;
        AudioFormat f = new AudioFormat(22050, 16, 1, true, false);
        byte[] silence = new byte[(int) (f.getSampleRate() * (ms / 1000.0) * f.getFrameSize())];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(silence), f, silence.length / f.getFrameSize()),
                    AudioFileFormat.Type.WAVE, out);
            parts.add(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create narration pause", e);
        }
    }

    private static volatile byte[] breathSampleCache;

    /** Same breath sample/rules as ProductionPipelineService's version (spec
     *  section 31) - duplicated rather than shared since this class already
     *  keeps its own copy of appendSilence/concatenateWav independently. */
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

    private void maybeInsertBreath(List<byte[]> parts, Integer pauseBeforeMs) {
        if (pauseBeforeMs == null || pauseBeforeMs < 400) {
            return;
        }
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() > 0.35) {
            return;
        }
        byte[] breath = loadBreathSample();
        if (breath != null) {
            parts.add(breath);
        }
    }

    private byte[] concatenateWav(List<byte[]> wavs) {
        AudioFormat target = new AudioFormat(22050, 16, 1, true, false);
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        try {
            for (byte[] wav : wavs) {
                try (AudioInputStream source = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
                     AudioInputStream converted = AudioSystem.isConversionSupported(target, source.getFormat())
                             ? AudioSystem.getAudioInputStream(target, source) : source) {
                    converted.transferTo(pcm);
                }
            }
            byte[] data = pcm.toByteArray();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (AudioInputStream combined = new AudioInputStream(new ByteArrayInputStream(data), target, data.length / target.getFrameSize())) {
                AudioSystem.write(combined, AudioFileFormat.Type.WAVE, out);
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not combine multi-voice narration", e);
        }
    }

    private double probeWavDuration(byte[] wav) {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            return ais.getFrameLength() / ais.getFormat().getFrameRate();
        } catch (Exception e) {
            return -1;
        }
    }

    private double estimateStoryboardDuration(String narration, List<VoiceSegmentInput> segments) {
        int words = 0;
        if (segments != null) {
            for (VoiceSegmentInput s : segments) {
                if (s != null && s.text() != null && !s.text().isBlank()) words += wordCount(s.text());
            }
        }
        if (words == 0) words = wordCount(narration);
        if (words == 0) return 3.0;
        double pauses = 0.0;
        if (segments != null) {
            for (VoiceSegmentInput s : segments) {
                if (s == null) continue;
                pauses += Math.max(0, s.pauseBeforeMs() == null ? 0 : s.pauseBeforeMs()) / 1000.0;
                pauses += Math.max(0, s.pauseAfterMs() == null ? 0 : s.pauseAfterMs()) / 1000.0;
            }
        }
        // ~150 WPM is a natural story narration baseline. Add a small visual tail
        // but cap at the 10-second H3 profile limit used by this 16GB deployment.
        return clampSceneDuration((words / 2.5) + pauses + NARRATION_TAIL_SECONDS);
    }

    private int wordCount(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }

    private double clampSceneDuration(double seconds) {
        return Math.max(3.0, Math.min(10.0, Math.round(seconds * 10.0) / 10.0));
    }

    private double sceneDuration(Scene scene) {
        if (scene.getImageDurationSeconds() != null && scene.getImageDurationSeconds() > 0) {
            return scene.getImageDurationSeconds();
        }
        if (scene.getNarrationSeconds() != null && scene.getNarrationSeconds() > 0) {
            return scene.getNarrationSeconds() + NARRATION_TAIL_SECONDS;
        }
        return DEFAULT_SCENE_SECONDS;
    }

    // ---- helpers -----------------------------------------------------------

    private String validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("That image file is empty.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "Unsupported image format. Use " + String.join(", ", ALLOWED_IMAGE_EXTENSIONS) + ".");
        }
        return extension;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded image", e);
        }
    }

    /**
     * Storage paths are built from IDs and a scene number, never from the
     * uploaded filename - so a crafted filename cannot escape the episode
     * directory.
     */
    private String imageRelativePath(Episode episode, Scene scene, String extension) {
        return relativePath(episode, "images/scene-" + scene.getSceneNumber() + "." + extension);
    }

    private String relativePath(Episode episode, String suffix) {
        return "episodes/" + episode.getId() + "/" + suffix;
    }

    private void saveAsset(Episode episode, UUID sceneId, AssetType type, Path path, String provider) {
        Asset asset = new Asset();
        asset.setEpisodeId(episode.getId());
        asset.setSceneId(sceneId);
        asset.setAssetType(type);
        asset.setFilePath(path.toString());
        asset.setProvider(provider);
        asset.setActive(true);
        assets.save(asset);
    }

    private Episode requireEpisode(UUID episodeId) {
        return episodes.findById(episodeId).orElseThrow(() ->
                new NoSuchElementException("No episode " + episodeId));
    }

    private Scene requireScene(UUID episodeId, UUID sceneId) {
        return scenes.findById(sceneId)
                .filter(s -> s.getEpisodeId().equals(episodeId))
                .orElseThrow(() -> new NoSuchElementException("No scene " + sceneId + " in this episode"));
    }
}
