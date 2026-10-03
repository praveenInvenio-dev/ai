package com.aistorystudio.videogen;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.domain.VoiceProfile;
import com.aistorystudio.provider.MediaProcessor;
import com.aistorystudio.provider.StorageProvider;
import com.aistorystudio.provider.TextToSpeechProvider;
import com.aistorystudio.provider.VideoGenerationProvider;
import com.aistorystudio.repository.VoiceProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Backs the standalone "Video generation" page: turn one uploaded image plus
 * a motion prompt into a short clip via the existing Wan/ComfyUI pipeline
 * (ProviderGateway -> ComfyUIVideoProvider), without needing a whole
 * story/episode around it first. This is the same provider the per-scene
 * animation pipeline uses (see ProductionPipelineService); this page is a
 * direct front door to it for testing prompts/settings or animating a single
 * still on its own.
 */
@Service
public class VideoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationService.class);
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp");

    private final ProviderGateway providerGateway;
    private final StorageProvider storage;
    private final VideoGenJobStore jobStore;
    private final MediaProcessor mediaProcessor;
    private final VoiceProfileRepository voiceProfileRepository;
    private final com.aistorystudio.service.VoiceProfileService voiceProfileService;
    private final int defaultWidth;
    private final int defaultHeight;
    private final double maxDurationSeconds;

    public VideoGenerationService(
            ProviderGateway providerGateway,
            StorageProvider storage,
            VideoGenJobStore jobStore,
            MediaProcessor mediaProcessor,
            VoiceProfileRepository voiceProfileRepository,
            com.aistorystudio.service.VoiceProfileService voiceProfileService,
            @Value("${studio.animation.local-ai.width:832}") int defaultWidth,
            @Value("${studio.animation.local-ai.height:480}") int defaultHeight,
            @Value("${studio.animation.local-ai.max-duration-seconds:6.0}") double maxDurationSeconds) {
        this.providerGateway = providerGateway;
        this.storage = storage;
        this.jobStore = jobStore;
        this.mediaProcessor = mediaProcessor;
        this.voiceProfileRepository = voiceProfileRepository;
        this.voiceProfileService = voiceProfileService;
        this.defaultWidth = defaultWidth;
        this.defaultHeight = defaultHeight;
        this.maxDurationSeconds = maxDurationSeconds;
    }

    public record StatusView(
            boolean available,
            String reason,
            int defaultWidth,
            int defaultHeight,
            double maxDurationSeconds
    ) {}

    /** Real availability, same as the per-scene pipeline sees - not a
     *  separate/looser check, so this page never offers to do something the
     *  scene pipeline would itself refuse. */
    public StatusView status() {
        return new StatusView(
                providerGateway.isLocalAiVideoAvailable(),
                providerGateway.localAiVideoUnavailableReason(),
                defaultWidth, defaultHeight, maxDurationSeconds);
    }

    public record JobView(
            UUID id,
            VideoGenJobStatus status,
            String errorMessage,
            Long seedUsed,
            String workflowUsed
    ) {}

    /**
     * Validates the request and stores the uploaded image up front, so a bad
     * request (provider unavailable, empty file, wrong format, missing
     * prompt) fails this synchronous call rather than surfacing later as a
     * job the caller only discovers is broken once it starts polling.
     * The generation call itself happens afterwards, in generateAsync().
     */
    /** Image is optional - a job with no image is text-to-video, which
     *  ComfyUIVideoProvider now handles as a real mode rather than an error
     *  (see its doGenerate() for how the two Wan templates get chosen). */
    public VideoGenJob createJob(MultipartFile image, String prompt) {
        String reason = providerGateway.localAiVideoUnavailableReason();
        if (reason != null) {
            throw new IllegalStateException("Video generation is not available: " + reason);
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("A prompt describing the video is required.");
        }

        VideoGenJob job = jobStore.create();
        boolean hasImage = image != null && !image.isEmpty();
        if (hasImage) {
            String extension = validateImage(image);
            String relativePath = "video-generation/uploads/" + job.getId() + "." + extension;
            Path stored;
            try {
                stored = storage.store(relativePath, image.getBytes());
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read the uploaded image", e);
            }
            job.setStartingImagePath(stored.toString());
        }
        return job;
    }

    /** Runs on the dedicated videoGenerationExecutor pool (see
     *  VideoGenerationExecutorConfig) - never on the request thread, since a
     *  Wan call can legitimately take many minutes (studio.animation.local-ai.
     *  timeout-seconds, default 1800s), far longer than an HTTP client
     *  should be left waiting on one request. Must be invoked from a
     *  different bean than this one (e.g. the controller) for @Async to
     *  actually intercept the call - a self-invocation runs synchronously. */
    @Async("videoGenerationExecutor")
    /** narrationText/voiceProfileId are both optional - a silent clip (the
     *  only option before this) is still exactly what you get when neither
     *  is supplied. This adds a NARRATION TRACK, not lip-sync - Wan has no
     *  concept of speech or mouth movement at all, so the character's mouth
     *  will not match the audio. True lip-sync needs a genuinely different,
     *  audio-driven model (e.g. LatentSync, MuseTalk) applied as a further
     *  post-process on top of this - real, separate scope, not done here. */
    public void generateAsync(UUID jobId, String prompt, String negativePrompt,
                               double durationSeconds, Long seed,
                               String narrationText, UUID voiceProfileId, String workflow) {
        VideoGenJob job = jobStore.get(jobId);
        if (job == null) {
            log.warn("Video generation job {} vanished before it could start (past its retention window?)", jobId);
            return;
        }
        job.setStatus(VideoGenJobStatus.RUNNING);
        try {
            String resolvedWorkflow = resolveWorkflow(workflow, job.getStartingImagePath() != null);
            // Voice generation is deliberately kept separate from the video model.
            // Wan and H3 create the visual clip; the selected Voice Lab/Chatterbox
            // voice is synthesized afterwards and muxed as the final narration track.
            String voiceReferenceAudioPath = null;
            // The VoiceProfile is resolved later by muxNarration(). Do not pass its
            // reference WAV into H3: standard TTS/voice cloning owns narration.
            String generationPrompt = prompt;
            boolean nativeH3Audio = false;
            // Do not inject narration into H3's native audio path. H3 is used as
            // a video model here; the app's standard cloned/expressive voice is
            // generated separately so voice selection behaves identically for
            // Wan 2.2 and H3.
            if (nativeH3Audio && narrationText != null && !narrationText.isBlank()) {
                generationPrompt = (prompt == null ? "" : prompt)
                        + "\n\nDialogue / narration: Speaker 1 says naturally: \"" + narrationText.trim() + "\".";
            }
            VideoGenerationProvider.VideoGenerationRequest request = new VideoGenerationProvider.VideoGenerationRequest(
                    job.getStartingImagePath(), generationPrompt, negativePrompt,
                    durationSeconds, 0, 0, resolvedWorkflow, voiceReferenceAudioPath, seed, 0);
            VideoGenerationProvider.VideoGenerationResult result = providerGateway.generateVideo(request);

            byte[] finalVideoBytes = result.videoBytes();
            String finalExtension = result.fileExtension();
            // Both Wan and H3 use the same standalone voice-generation path. The final
            // narration track is therefore deterministic and can use the selected cloned voice.
            if (narrationText != null && !narrationText.isBlank()) {
                finalVideoBytes = muxNarration(jobId, finalVideoBytes, finalExtension, narrationText, voiceProfileId);
                finalExtension = "mp4";
            }

            String relativePath = "video-generation/results/" + jobId + "." + finalExtension;
            Path stored = storage.store(relativePath, finalVideoBytes);

            job.setResultVideoPath(stored.toString());
            job.setFileExtension(finalExtension);
            job.setSeedUsed(result.seedUsed());
            job.setWorkflowUsed(result.workflowUsed());
            job.setStatus(VideoGenJobStatus.SUCCEEDED);
        } catch (Exception e) {
            log.error("Video generation job {} failed", jobId, e);
            job.setErrorMessage(userMessage(e));
            job.setStatus(VideoGenJobStatus.FAILED);
        }
    }

    private String resolveWorkflow(String workflow, boolean hasStartingImage) {
        if (workflow == null || workflow.isBlank()) return hasStartingImage ? "wan-image-to-video" : "wan-ti2v-5b-text-to-video";
        return switch (workflow.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "WAN_2_2" -> hasStartingImage ? "wan-ti2v-5b-image-to-video" : "wan-ti2v-5b-text-to-video";
            case "MINIMAX_H3" -> hasStartingImage ? "minimax-h3-image-to-video" : "minimax-h3-text-to-video";
            default -> throw new IllegalArgumentException("Unsupported video generation workflow: " + workflow);
        };
    }

    /** Synthesizes narrationText (via the given VoiceProfile if one was
     *  picked, else the app-wide default TTS provider) and muxes it onto the
     *  freshly-generated video. Failure here degrades to the SILENT video
     *  rather than failing the whole job - a narration/TTS hiccup losing the
     *  video entirely would be a worse outcome than just not having audio. */
    private byte[] muxNarration(UUID jobId, byte[] videoBytes, String videoExtension,
                                 String narrationText, UUID voiceProfileId) {
        Path videoTemp = null;
        Path outputTemp = null;
        try {
            videoTemp = java.nio.file.Files.createTempFile("video-gen-" + jobId, "." + videoExtension);
            java.nio.file.Files.write(videoTemp, videoBytes);

            TextToSpeechProvider.TtsResult tts;
            if (voiceProfileId != null) {
                VoiceProfile profile = voiceProfileRepository.findById(voiceProfileId).orElse(null);
                tts = profile != null
                        ? providerGateway.synthesizeWithVoice(profile.getProvider(), profile.getVoiceName(), narrationText)
                        : providerGateway.synthesize(new TextToSpeechProvider.TtsRequest(narrationText, null, null, 1.0, 1.0));
            } else {
                tts = providerGateway.synthesize(new TextToSpeechProvider.TtsRequest(narrationText, null, null, 1.0, 1.0));
            }

            outputTemp = java.nio.file.Files.createTempFile("video-gen-muxed-" + jobId, ".mp4");
            java.nio.file.Files.deleteIfExists(outputTemp); // ffmpeg refuses to overwrite by default
            Path muxed = mediaProcessor.addAudioTrack(videoTemp, tts.audioBytes(), outputTemp);
            return java.nio.file.Files.readAllBytes(muxed);
        } catch (Exception e) {
            log.warn("Narration mux failed for video-generation job {} - keeping the silent video instead: {}",
                    jobId, e.getMessage());
            return videoBytes;
        } finally {
            try { if (videoTemp != null) java.nio.file.Files.deleteIfExists(videoTemp); } catch (IOException ignored) { }
            try { if (outputTemp != null) java.nio.file.Files.deleteIfExists(outputTemp); } catch (IOException ignored) { }
        }
    }

    public JobView getStatus(UUID jobId) {
        VideoGenJob job = require(jobId);
        return new JobView(job.getId(), job.getStatus(), job.getErrorMessage(), job.getSeedUsed(), job.getWorkflowUsed());
    }

    /** Absolute path of the finished clip on disk, for the controller to
     *  stream. Throws (mapped to 422 by GlobalExceptionHandler) if the job
     *  hasn't succeeded yet, rather than returning a null/empty response the
     *  browser would render as a broken video player. */
    public Path getResultPath(UUID jobId) {
        VideoGenJob job = require(jobId);
        if (job.getStatus() != VideoGenJobStatus.SUCCEEDED || job.getResultVideoPath() == null) {
            throw new IllegalStateException("Job " + jobId + " has not produced a video yet (status: " + job.getStatus() + ").");
        }
        return Path.of(job.getResultVideoPath());
    }

    private VideoGenJob require(UUID jobId) {
        VideoGenJob job = jobStore.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Video generation job not found: " + jobId);
        }
        return job;
    }

    private String validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("That starting image is empty.");
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

    private String userMessage(Exception e) {
        String message = e.getMessage();
        return message != null ? message : e.getClass().getSimpleName();
    }
}
