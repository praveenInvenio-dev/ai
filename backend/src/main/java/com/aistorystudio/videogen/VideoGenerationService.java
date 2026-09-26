package com.aistorystudio.videogen;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.provider.StorageProvider;
import com.aistorystudio.provider.VideoGenerationProvider;
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
    private final int defaultWidth;
    private final int defaultHeight;
    private final double maxDurationSeconds;

    public VideoGenerationService(
            ProviderGateway providerGateway,
            StorageProvider storage,
            VideoGenJobStore jobStore,
            @Value("${studio.animation.local-ai.width:832}") int defaultWidth,
            @Value("${studio.animation.local-ai.height:480}") int defaultHeight,
            @Value("${studio.animation.local-ai.max-duration-seconds:6.0}") double maxDurationSeconds) {
        this.providerGateway = providerGateway;
        this.storage = storage;
        this.jobStore = jobStore;
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
    public VideoGenJob createJob(MultipartFile image, String prompt) {
        String reason = providerGateway.localAiVideoUnavailableReason();
        if (reason != null) {
            throw new IllegalStateException("Video generation is not available: " + reason);
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("A prompt describing the motion is required.");
        }
        String extension = validateImage(image);

        VideoGenJob job = jobStore.create();
        String relativePath = "video-generation/uploads/" + job.getId() + "." + extension;
        Path stored;
        try {
            stored = storage.store(relativePath, image.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded image", e);
        }
        job.setStartingImagePath(stored.toString());
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
    public void generateAsync(UUID jobId, String prompt, String negativePrompt,
                               double durationSeconds, Long seed) {
        VideoGenJob job = jobStore.get(jobId);
        if (job == null) {
            log.warn("Video generation job {} vanished before it could start (past its retention window?)", jobId);
            return;
        }
        job.setStatus(VideoGenJobStatus.RUNNING);
        try {
            VideoGenerationProvider.VideoGenerationRequest request = new VideoGenerationProvider.VideoGenerationRequest(
                    job.getStartingImagePath(), prompt, negativePrompt,
                    durationSeconds, 0, 0, null, seed);
            VideoGenerationProvider.VideoGenerationResult result = providerGateway.generateVideo(request);

            String relativePath = "video-generation/results/" + jobId + "." + result.fileExtension();
            Path stored = storage.store(relativePath, result.videoBytes());

            job.setResultVideoPath(stored.toString());
            job.setFileExtension(result.fileExtension());
            job.setSeedUsed(result.seedUsed());
            job.setWorkflowUsed(result.workflowUsed());
            job.setStatus(VideoGenJobStatus.SUCCEEDED);
        } catch (Exception e) {
            log.error("Video generation job {} failed", jobId, e);
            job.setErrorMessage(userMessage(e));
            job.setStatus(VideoGenJobStatus.FAILED);
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
