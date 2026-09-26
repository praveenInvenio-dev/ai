package com.aistorystudio.videoeditor.service;

import com.aistorystudio.videoeditor.domain.RenderJob;
import com.aistorystudio.videoeditor.domain.enums.RenderKind;
import com.aistorystudio.videoeditor.domain.enums.VideoEditorState;
import com.aistorystudio.videoeditor.repository.EditingPlanRepository;
import com.aistorystudio.videoeditor.repository.RenderJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Short transactional writes to {@code render_job}, in their own bean.
 *
 * <h2>Why this is not just methods on VideoRenderService</h2>
 *
 * Spring's {@code @Transactional} works through a proxy, so a method calling
 * another method <em>on itself</em> bypasses it entirely - the annotation is
 * silently ignored. {@code render()} is a long, non-transactional orchestration
 * method, so its calls to {@code doRender}/{@code finish}/{@code markFailed}
 * were getting no transaction at all despite being annotated.
 *
 * Moving these into a separate bean means the calls go through a real proxy.
 *
 * They are also deliberately SHORT and REQUIRES_NEW. A render runs for minutes;
 * wrapping the whole thing in one transaction would pin a database connection
 * for its entire duration, and with the pool exhausted the rest of the app
 * stops responding while a video encodes.
 */
@Service
public class RenderJobStateService {

    private final RenderJobRepository renderJobs;
    private final EditingPlanRepository plans;

    public RenderJobStateService(RenderJobRepository renderJobs, EditingPlanRepository plans) {
        this.renderJobs = renderJobs;
        this.plans = plans;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RenderJob create(UUID projectId, RenderKind kind) {
        RenderJob job = new RenderJob();
        job.setProjectId(projectId);
        job.setKind(kind);
        job.setStatus(VideoEditorState.RENDERING);
        job.setStartedAt(Instant.now());
        plans.findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .ifPresent(plan -> job.setPlanId(plan.getId()));
        return renderJobs.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(UUID jobId, String outputFilename, String qualityReportJson) {
        renderJobs.findById(jobId).ifPresent(job -> {
            job.setStatus(VideoEditorState.COMPLETED);
            job.setProgressPercent(100);
            job.setOutputFilename(outputFilename);
            job.setQualityReportJson(qualityReportJson);
            job.setFinishedAt(Instant.now());
            renderJobs.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeOperation(UUID jobId) {
        renderJobs.findById(jobId).ifPresent(job -> {
            job.setStatus(VideoEditorState.COMPLETED);
            job.setProgressPercent(100);
            job.setFinishedAt(Instant.now());
            renderJobs.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, String message) {
        renderJobs.findById(jobId).ifPresent(job -> {
            job.setStatus(VideoEditorState.FAILED);
            job.setErrorMessage(message);
            job.setFinishedAt(Instant.now());
            renderJobs.save(job);
        });
    }
}
