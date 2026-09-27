package com.aistorystudio.service;

import com.aistorystudio.domain.GenerationJob;
import com.aistorystudio.domain.GenerationStep;
import com.aistorystudio.domain.enums.JobStatus;
import com.aistorystudio.domain.enums.StepStatus;
import com.aistorystudio.dto.JobStatusResponse;
import com.aistorystudio.repository.GenerationJobRepository;
import com.aistorystudio.repository.GenerationStepRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class JobService {

    private final GenerationJobRepository jobRepository;
    private final GenerationStepRepository stepRepository;
    private final JobEventService jobEventService;

    public JobService(GenerationJobRepository jobRepository, GenerationStepRepository stepRepository,
                       JobEventService jobEventService) {
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
        this.jobEventService = jobEventService;
    }

    public GenerationJob createJob(UUID episodeId) {
        GenerationJob job = new GenerationJob();
        job.setEpisodeId(episodeId);
        job.setStatus(JobStatus.QUEUED);
        job = jobRepository.save(job);
        publish(job);
        return job;
    }

    public GenerationJob updateStatus(UUID jobId, JobStatus status, int progressPercent) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        job.setStatus(status);
        job.setProgressPercent(progressPercent);
        job = jobRepository.save(job);
        publish(job);
        return job;
    }

    public void fail(UUID jobId, String message) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobRepository.save(job);
        publish(job);
    }

    public GenerationStep startStep(UUID jobId, UUID sceneId, String stepName, String provider, String model) {
        GenerationStep step = new GenerationStep();
        step.setJobId(jobId);
        step.setSceneId(sceneId);
        step.setStepName(stepName);
        step.setProvider(provider);
        step.setModel(model);
        step.setStatus(StepStatus.RUNNING);
        step = stepRepository.save(step);
        publishJob(jobId);
        return step;
    }

    public void completeStep(UUID stepId, long durationMs) {
        GenerationStep step = stepRepository.findById(stepId).orElseThrow();
        step.setStatus(StepStatus.SUCCEEDED);
        step.setDurationMs(durationMs);
        step = stepRepository.save(step);
        publishJob(step.getJobId());
    }

    public void failStep(UUID stepId, String error, int retryCount) {
        GenerationStep step = stepRepository.findById(stepId).orElseThrow();
        step.setStatus(StepStatus.FAILED);
        step.setErrorMessage(error);
        step.setRetryCount(retryCount);
        step = stepRepository.save(step);
        publishJob(step.getJobId());
    }

    /** Non-fatal - call this on a step that otherwise succeeds (a TTS
     *  fallback fired but the step still completed). */
    public void setStepWarning(UUID stepId, String warning) {
        if (warning == null || warning.isBlank()) return;
        GenerationStep step = stepRepository.findById(stepId).orElseThrow();
        step.setWarningMessage(warning);
        step = stepRepository.save(step);
        publishJob(step.getJobId());
    }

    public JobStatusResponse getStatus(UUID jobId) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        List<GenerationStep> steps = stepRepository.findByJobIdOrderByCreatedAtAsc(jobId);
        List<JobStatusResponse.StepDto> stepDtos = steps.stream()
                .map(s -> new JobStatusResponse.StepDto(s.getStepName(), s.getStatus().name(), s.getRetryCount(), s.getErrorMessage(), s.getWarningMessage(), s.getDurationMs()))
                .toList();
        return new JobStatusResponse(job.getId(), job.getEpisodeId(), job.getStatus(), job.getProgressPercent(), job.getErrorMessage(), stepDtos);
    }

    public List<GenerationJob> listForEpisode(UUID episodeId) {
        return jobRepository.findByEpisodeIdOrderByCreatedAtDesc(episodeId);
    }

    /** Used when the Production Dashboard is opened directly (e.g. a bookmark/refresh)
     * rather than navigated to right after approving, so there's no job id in hand yet. */
    public java.util.Optional<JobStatusResponse> getLatestForEpisode(UUID episodeId) {
        List<GenerationJob> jobs = listForEpisode(episodeId);
        if (jobs.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(getStatus(jobs.get(0).getId()));
    }

    private void publish(GenerationJob job) {
        jobEventService.publish(job.getId(), getStatus(job.getId()));
        if (job.getStatus() == JobStatus.COMPLETED || job.getStatus() == JobStatus.FAILED
                || job.getStatus() == JobStatus.CANCELLED) {
            jobEventService.complete(job.getId());
        }
    }

    private void publishJob(UUID jobId) {
        jobEventService.publish(jobId, getStatus(jobId));
    }
}
