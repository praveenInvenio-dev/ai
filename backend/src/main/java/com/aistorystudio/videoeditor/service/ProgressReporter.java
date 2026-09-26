package com.aistorystudio.videoeditor.service;

import com.aistorystudio.service.JobEventService;
import com.aistorystudio.videoeditor.repository.RenderJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Progress for long editor operations: persisted to {@code render_job} and
 * pushed over SSE.
 *
 * Both, deliberately. SSE alone loses everything if the browser tab reloads
 * mid-render, and the DB alone gives no live updates. Writing to both means a
 * reconnecting client can poll {@code GET /jobs/{id}} for where things got to.
 *
 * Percentages come from real work - clips completed, FFmpeg's own frame counter -
 * never a timer. A bar that advances on a schedule while nothing happens is
 * worse than no bar, because it removes the one signal that something is stuck.
 */
@Service
public class ProgressReporter {

    private static final Logger log = LoggerFactory.getLogger(ProgressReporter.class);

    private final RenderJobRepository renderJobs;
    private final JobEventService events;

    public ProgressReporter(RenderJobRepository renderJobs, JobEventService events) {
        this.renderJobs = renderJobs;
        this.events = events;
    }

    /**
     * REQUIRES_NEW: progress must be visible to a polling client while the
     * surrounding render transaction is still open. Joining that transaction
     * would hold every update invisible until the whole render committed,
     * which defeats the purpose.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void report(UUID jobId, String stage, int percent, String detail) {
        int clamped = Math.max(0, Math.min(100, percent));

        if (jobId != null) {
            renderJobs.findById(jobId).ifPresent(job -> {
                job.setStage(stage);
                job.setProgressPercent(clamped);
                renderJobs.save(job);
            });
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        payload.put("percent", clamped);
        if (detail != null) {
            payload.put("detail", detail);
        }
        if (jobId != null) {
            events.publish(jobId, payload);
        }
        log.debug("[{}] {} {}% {}", jobId, stage, clamped, detail == null ? "" : detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(UUID jobId, String detail) {
        report(jobId, "Complete", 100, detail);
        if (jobId != null) {
            events.complete(jobId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, String userFacingMessage) {
        if (jobId != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stage", "Failed");
            payload.put("error", userFacingMessage);
            events.publish(jobId, payload);
            events.complete(jobId);
        }
    }
}
