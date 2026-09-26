package com.aistorystudio.videogen;

import java.time.Instant;
import java.util.UUID;

/**
 * A single standalone video-generation request submitted from the dedicated
 * "Video generation" page (see VideoGenerationController), independent of any
 * story/episode/scene.
 *
 * Deliberately NOT a JPA entity like {@code GenerationJob}. That entity is
 * modelled around an episode (it has a non-null episodeId FK and is meant to
 * track the multi-step story pipeline); a one-off "animate this image" test
 * has no episode to attach to, and forcing one in would mean either a schema
 * migration to make episodeId nullable or a fake placeholder episode - both
 * worse than just keeping this run's bookkeeping in memory. It is not
 * persisted across a backend restart, which is fine: a job is a single
 * ComfyUI call lasting at most studio.animation.local-ai.timeout-seconds, not
 * something meant to be resumed hours later.
 */
public class VideoGenJob {

    private final UUID id;
    private final Instant createdAt;
    private volatile VideoGenJobStatus status;
    private volatile String errorMessage;
    private volatile String startingImagePath;
    private volatile String resultVideoPath;
    private volatile String fileExtension;
    private volatile Long seedUsed;
    private volatile String workflowUsed;

    public VideoGenJob(UUID id) {
        this.id = id;
        this.createdAt = Instant.now();
        this.status = VideoGenJobStatus.QUEUED;
    }

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public VideoGenJobStatus getStatus() {
        return status;
    }

    public void setStatus(VideoGenJobStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getStartingImagePath() {
        return startingImagePath;
    }

    public void setStartingImagePath(String startingImagePath) {
        this.startingImagePath = startingImagePath;
    }

    public String getResultVideoPath() {
        return resultVideoPath;
    }

    public void setResultVideoPath(String resultVideoPath) {
        this.resultVideoPath = resultVideoPath;
    }

    public String getFileExtension() {
        return fileExtension;
    }

    public void setFileExtension(String fileExtension) {
        this.fileExtension = fileExtension;
    }

    public Long getSeedUsed() {
        return seedUsed;
    }

    public void setSeedUsed(Long seedUsed) {
        this.seedUsed = seedUsed;
    }

    public String getWorkflowUsed() {
        return workflowUsed;
    }

    public void setWorkflowUsed(String workflowUsed) {
        this.workflowUsed = workflowUsed;
    }
}
