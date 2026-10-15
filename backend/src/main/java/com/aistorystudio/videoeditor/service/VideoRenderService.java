package com.aistorystudio.videoeditor.service;

import com.aistorystudio.videoeditor.config.VideoEditorProperties;
import com.aistorystudio.videoeditor.domain.*;
import com.aistorystudio.videoeditor.domain.enums.AudioTrackKind;
import com.aistorystudio.videoeditor.domain.enums.RenderKind;
import com.aistorystudio.videoeditor.domain.enums.VideoEditorState;
import com.aistorystudio.videoeditor.editing.TechniqueLibrary;
import com.aistorystudio.videoeditor.editing.TimelineRenderer;
import com.aistorystudio.videoeditor.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Phase 7: renders a timeline to MP4.
 *
 * <h2>Two things worth knowing</h2>
 *
 * <b>Renders are serialised.</b> {@code maxConcurrentRenders} defaults to 1
 * because on a 12-core box two simultaneous x264 encodes - possibly alongside a
 * ComfyUI job - finish later than if they had queued, and the machine becomes
 * unusable meanwhile. This is the same discipline the image provider already
 * applies.
 *
 * <b>Previews are cached by plan hash.</b> The output file is named after the
 * timeline's content hash, so undo/redo and any round trip back to a previous
 * arrangement resolves to an existing file instead of re-encoding. That is the
 * common case while someone is adjusting cuts, and it is the difference between
 * a preview feeling instant and feeling broken.
 */
@Service
public class VideoRenderService {

    private static final Logger log = LoggerFactory.getLogger(VideoRenderService.class);

    /** Free space required before starting, so a render fails early and
     *  clearly rather than dying mid-encode with an FFmpeg I/O error. */
    private static final long MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024;

    private final VideoEditorProperties properties;
    private final VideoEditorProjectService projectService;
    private final VideoClipRepository clips;
    private final TimelineClipRepository timeline;
    private final EditingPlanRepository plans;
    private final RenderJobRepository renderJobs;
    private final AudioTrackRepository audioTracks;
    private final VideoEditorStorageService storage;
    private final TimelineRenderer renderer;
    private final ProgressReporter progress;
    private final RenderJobStateService jobState;
    private final TechniqueLibrary library;
    private final Semaphore slot;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AutoCaptionService autoCaptions;

    public VideoRenderService(VideoEditorProperties properties,
                              VideoEditorProjectService projectService,
                              VideoClipRepository clips,
                              TimelineClipRepository timeline,
                              EditingPlanRepository plans,
                              RenderJobRepository renderJobs,
                              AudioTrackRepository audioTracks,
                              VideoEditorStorageService storage,
                              TimelineRenderer renderer,
                              ProgressReporter progress,
                              RenderJobStateService jobState,
                              TechniqueLibrary library) {
        this.properties = properties;
        this.projectService = projectService;
        this.clips = clips;
        this.timeline = timeline;
        this.plans = plans;
        this.renderJobs = renderJobs;
        this.audioTracks = audioTracks;
        this.storage = storage;
        this.renderer = renderer;
        this.progress = progress;
        this.jobState = jobState;
        this.library = library;
        this.slot = new Semaphore(properties.getMaxConcurrentRenders(), true);
    }

    /** Creates the job row up front so the client has an id to subscribe to
     *  before any slow work begins. Delegated so the write is a real, short
     *  transaction rather than a self-call the proxy never sees. */
    public RenderJob createJob(UUID projectId, RenderKind kind) {
        return jobState.create(projectId, kind);
    }

    public void render(UUID projectId, UUID jobId, RenderKind kind) {
        boolean acquired = false;
        try {
            progress.report(jobId, "Queued", 0, "Waiting for a free render slot");
            acquired = slot.tryAcquire(2, TimeUnit.HOURS);
            if (!acquired) {
                throw new IllegalStateException(
                        "Timed out waiting for a render slot. Another render has been running "
                                + "for over two hours - check the logs for the video editor.");
            }
            doRender(projectId, jobId, kind);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markFailed(jobId, projectId, "Render was interrupted.");
        } catch (Exception e) {
            log.error("Render failed for project {}", projectId, e);
            markFailed(jobId, projectId, userMessage(e));
        } finally {
            if (acquired) {
                slot.release();
            }
        }
    }

    /**
     * Deliberately NOT @Transactional. This runs for minutes; holding a pooled
     * database connection open across an FFmpeg encode would exhaust the pool
     * and stall the rest of the application. Every write it needs is a short
     * transaction on another bean.
     */
    /** The user's look wins; "auto"/unknown falls back to the template's own grade. */
    private String effectiveGrade(VideoEditorProject project) {
        String look = project.getLook();
        if (look != null && (look.equals("none") || TimelineRenderer.LOOKS.containsKey(look))) return look;
        return library.templateFor(project.getCategory().name()).colorGrade();
    }

    private void doRender(UUID projectId, UUID jobId, RenderKind kind) {
        VideoEditorProject project = projectService.get(projectId);
        List<TimelineClip> rows = timeline.findByProjectIdOrderBySortOrderAsc(projectId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("There is no timeline to render. Create an edit first.");
        }

        if (storage.usableSpaceBytes() < MIN_FREE_BYTES) {
            throw new IllegalStateException(
                    "Not enough free disk space to render (needs at least 2 GB free).");
        }

        projectService.transition(projectId,
                kind == RenderKind.PREVIEW ? VideoEditorState.PREVIEW_RENDERING : VideoEditorState.RENDERING);

        // the preview cache key must change with look / stabilise (same cuts, different pixels)
        String hash = VideoEditorProjectService.hashTimeline(rows)
                + Integer.toHexString((String.valueOf(project.getLook()) + project.isStabilize() + project.getEffects()).hashCode());
        Path output = kind == RenderKind.PREVIEW
                ? storage.resolveWithin(storage.previewsDir(projectId), hash + ".mp4")
                : storage.resolveWithin(storage.rendersDir(projectId), jobId + ".mp4");

        // Cache hit: an identical timeline has already been previewed.
        if (kind == RenderKind.PREVIEW && Files.exists(output)) {
            log.info("Preview cache hit for plan {}", hash);
            finish(jobId, projectId, output, kind, "Reused the existing preview for this timeline");
            return;
        }

        // PREVIEW reads the 480p proxies; FINAL reads the originals. Using
        // proxies for the final output would ship a 480p video.
        Map<UUID, VideoClip> byId = new HashMap<>();
        clips.findByProjectIdOrderBySortOrderAsc(projectId).forEach(c -> byId.put(c.getId(), c));

        List<TimelineRenderer.Segment> segments = new ArrayList<>(rows.size());
        for (TimelineClip row : rows) {
            VideoClip clip = byId.get(row.getClipId());
            if (clip == null) {
                throw new IllegalStateException(
                        "The timeline references a clip that is no longer in this project. "
                                + "Re-create the edit.");
            }
            Path source = kind == RenderKind.PREVIEW && clip.getProxyFilename() != null
                    ? storage.resolveWithin(storage.proxiesDir(projectId), clip.getProxyFilename())
                    : storage.resolveWithin(storage.uploadsDir(projectId), clip.getStoredFilename());
            if (!Files.exists(source)) {
                throw new IllegalStateException(
                        "The source file for \"" + clip.getDisplayName() + "\" is missing.");
            }
            segments.add(new TimelineRenderer.Segment(row, source));
        }

        Optional<AudioTrack> music = audioTracks.findFirstByProjectIdAndKind(projectId, AudioTrackKind.MUSIC);
        Path musicFile = music
                .map(t -> storage.resolveWithin(storage.audioDir(projectId), t.getStoredFilename()))
                .filter(Files::exists)
                .orElse(null);

        int height = kind == RenderKind.PREVIEW
                ? properties.getPreviewResolution()
                : properties.getDefaultResolution();

        var request = new TimelineRenderer.RenderRequest(
                segments,
                project.getAspectRatio(),
                height,
                // ultrafast for previews: they are judged on timing and cut
                // placement, not on compression quality.
                kind == RenderKind.PREVIEW ? "ultrafast" : properties.getX264Preset(),
                kind == RenderKind.PREVIEW ? 30 : properties.getCrf(),
                musicFile,
                music.map(AudioTrack::getGain).orElse(0.25),
                output,
                properties.getAnalysisThreads(),
                project.isAudioEnhancement(),
                project.isSmartReframing(),
                effectiveGrade(project),
                project.isStabilize(),
                project.getEffects());

        Path result = renderer.render(request,
                (percent, stage) -> progress.report(jobId, stage, percent, null));

        String note = null;
        // Title text (CapCut "Text"): animated title over the first seconds of the final cut
        if (project.getTitleText() != null && !project.getTitleText().isBlank() && kind == RenderKind.FINAL && autoCaptions != null) {
            try {
                autoCaptions.addTitle(projectId, result, project.getTitleText(), String.valueOf(project.getAspectRatio()).contains("9_16"));
            } catch (Exception e) {
                log.warn("Title overlay failed for project {}: {}", projectId, e.getMessage());
                note = "Rendered without the title (" + e.getMessage() + ")";
            }
        }
        // Auto captions (Higgsfield/CapCut style): transcribe the finished cut, burn in word-highlight captions.
        if (project.isAutoCaptions() && kind == RenderKind.FINAL && autoCaptions != null) {
            progress.report(jobId, "Captions", 96, "Transcribing speech and adding captions");
            try {
                note = autoCaptions.apply(projectId, result, String.valueOf(project.getAspectRatio()).contains("9_16"));
            } catch (Exception e) {
                log.warn("Auto captions failed for project {}: {}", projectId, e.getMessage());
                note = "Rendered without captions (captions failed: " + e.getMessage() + ")";
            }
        }
        finish(jobId, projectId, result, kind, note);
    }

    private void finish(UUID jobId, UUID projectId, Path output, RenderKind kind, String note) {
        jobState.succeed(jobId, output.getFileName().toString(), validate(output));
        projectService.transition(projectId,
                kind == RenderKind.PREVIEW ? VideoEditorState.PREVIEW_READY : VideoEditorState.COMPLETED);
        progress.finish(jobId, note != null ? note : "Render complete");
    }

    /**
     * Post-render validation (brief §33). Cheap checks only - the point is to
     * catch an output that exists but is empty or unplayable, which otherwise
     * only surfaces when the user tries to watch it.
     */
    private String validate(Path output) {
        try {
            long size = Files.size(output);
            double duration = renderer.probeDuration(output);
            boolean ok = size > 1024 && duration > 0.3;
            return String.format(Locale.ROOT,
                    "{\"exists\":true,\"sizeBytes\":%d,\"durationSec\":%.2f,\"playable\":%b}",
                    size, duration, ok);
        } catch (Exception e) {
            return "{\"exists\":false,\"error\":\"could not inspect the rendered file\"}";
        }
    }

    private void markFailed(UUID jobId, UUID projectId, String message) {
        jobState.fail(jobId, message);
        projectService.fail(projectId, message, null);
        progress.fail(jobId, message);
    }

    /** Turns an exception into something a user can act on. The technical
     *  detail is already in the log; the UI gets the actionable sentence. */
    private String userMessage(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("Could not start FFmpeg")) {
            return "FFmpeg is not available in the backend container.";
        }
        if (message.contains("No space") || message.contains("disk space")) {
            return "Not enough disk space to finish the render.";
        }
        if (message.contains("timed out")) {
            return "The render took too long and was stopped.";
        }
        return message.isBlank() ? "Rendering failed. See the backend logs for details." : message;
    }
}
