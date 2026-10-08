package com.aistorystudio.studio;

import java.time.Instant;
import java.util.UUID;

/** One Motion & Effects Studio job (in memory, like Video Generation jobs). */
public class StudioJob {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }
    public enum Tool { MOTION_CONTROL, UPSCALE, REFRAME, BACKGROUND, DUB, ANALYZE }

    private final UUID id = UUID.randomUUID();
    private final Tool tool;
    private final Instant createdAt = Instant.now();
    private volatile Status status = Status.QUEUED;
    private volatile String stage = "Queued";
    private volatile String errorMessage;
    private volatile String resultPath;
    private volatile Double resultSeconds;
    private volatile String summary;
    private volatile String report;
    private volatile String resultExtension = "mp4";

    public StudioJob(Tool tool) { this.tool = tool; }

    public UUID getId() { return id; }
    public Tool getTool() { return tool; }
    public Instant getCreatedAt() { return createdAt; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getResultPath() { return resultPath; }
    public void setResultPath(String resultPath) { this.resultPath = resultPath; }
    public Double getResultSeconds() { return resultSeconds; }
    public void setResultSeconds(Double resultSeconds) { this.resultSeconds = resultSeconds; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    /** Free-text result (ANALYZE report, DUB translated script). */
    public String getReport() { return report; }
    public void setReport(String report) { this.report = report; }
    /** mp4 | webm (transparent video) | png (image cut-out). */
    public String getResultExtension() { return resultExtension; }
    public void setResultExtension(String resultExtension) { this.resultExtension = resultExtension; }
}
