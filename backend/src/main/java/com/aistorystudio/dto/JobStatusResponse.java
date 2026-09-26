package com.aistorystudio.dto;

import com.aistorystudio.domain.enums.JobStatus;

import java.util.List;
import java.util.UUID;

public record JobStatusResponse(
        UUID jobId,
        UUID episodeId,
        JobStatus status,
        int progressPercent,
        String errorMessage,
        List<StepDto> steps
) {
    public record StepDto(String stepName, String status, int retryCount, String errorMessage, Long durationMs) {}
}
