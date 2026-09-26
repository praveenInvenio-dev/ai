package com.aistorystudio.videoeditor.controller;

import com.aistorystudio.service.JobEventService;
import com.aistorystudio.videoeditor.config.VideoEditorProperties;
import com.aistorystudio.videoeditor.domain.*;
import com.aistorystudio.videoeditor.domain.enums.*;
import com.aistorystudio.videoeditor.service.EditPlannerService;
import com.aistorystudio.videoeditor.service.ProgressReporter;
import com.aistorystudio.videoeditor.service.RenderJobStateService;
import com.aistorystudio.videoeditor.service.VideoAnalysisService;
import com.aistorystudio.videoeditor.service.VideoEditorProjectService;
import com.aistorystudio.videoeditor.service.VideoEditorStorageService;
import com.aistorystudio.videoeditor.service.VideoRenderService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Video editor REST API.
 *
 * {@code @ConditionalOnProperty} means VIDEO_EDITOR_ENABLED=false removes these
 * endpoints entirely rather than leaving them returning errors - the brief asks
 * for the module to be fully disableable so editor problems cannot affect the
 * story pipeline.
 *
 * Analyse / plan / render endpoints run as real asynchronous jobs. Analysis
 * produces measurements, AI Edit turns those measurements into a renderable
 * timeline, and Render produces the final MP4. The frontend can chain these
 * stages into one one-click production flow while the individual endpoints
 * remain available for manual refinement.
 */
@RestController
@RequestMapping("/api/video-editor")
@CrossOrigin
@ConditionalOnProperty(name = "studio.video-editor.enabled", havingValue = "true", matchIfMissing = true)
public class VideoEditorController {

    private final VideoEditorProjectService service;
    private final VideoEditorStorageService storage;
    private final VideoEditorProperties properties;
    private final JobEventService jobEvents;
    private final VideoAnalysisService analysisService;
    private final EditPlannerService plannerService;
    private final VideoRenderService renderService;
    private final ProgressReporter progress;
    private final RenderJobStateService jobState;
    private final Executor editorExecutor;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public VideoEditorController(VideoEditorProjectService service,
                                 VideoEditorStorageService storage,
                                 VideoEditorProperties properties,
                                 JobEventService jobEvents,
                                 VideoAnalysisService analysisService,
                                 EditPlannerService plannerService,
                                 VideoRenderService renderService,
                                 ProgressReporter progress,
                                 RenderJobStateService jobState,
                                 @Qualifier("videoEditorExecutor") Executor editorExecutor) {
        this.service = service;
        this.storage = storage;
        this.properties = properties;
        this.jobEvents = jobEvents;
        this.analysisService = analysisService;
        this.plannerService = plannerService;
        this.renderService = renderService;
        this.progress = progress;
        this.jobState = jobState;
        this.editorExecutor = editorExecutor;
    }

    // ---- capability discovery ---------------------------------------------

    /**
     * What this build can actually do.
     *
     * The frontend uses this to disable controls whose backend stage is not
     * implemented yet, instead of offering buttons that fail when pressed.
     */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        // LinkedHashMap, not Map.of: Map.of has overloads for at most 10 pairs
        // and this needs 11, which fails to compile with an overload-resolution
        // error that never mentions the limit. LinkedHashMap also keeps the
        // JSON field order stable, which makes the response easier to read.
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("maxClips", properties.getMaxClips());
        capabilities.put("maxUploadMb", properties.getMaxUploadMb());
        capabilities.put("allowedVideoExtensions", properties.allowedVideoExtensions());
        capabilities.put("allowedAudioExtensions", properties.allowedAudioExtensions());
        capabilities.put("previewResolution", properties.getPreviewResolution());
        capabilities.put("defaultResolution", properties.getDefaultResolution());
        capabilities.put("categories", VideoCategory.values());
        capabilities.put("styles", EditingStyle.values());
        capabilities.put("intensities", EditIntensity.values());
        capabilities.put("aspectRatios", Arrays.stream(AspectRatio.values())
                .map(r -> {
                    Map<String, Object> ratio = new LinkedHashMap<>();
                    ratio.put("id", r.name());
                    ratio.put("width", r.width());
                    ratio.put("height", r.height());
                    return ratio;
                })
                .toList());

        // Which backend stages exist in this build. The frontend disables the
        // controls whose stage is false rather than offering a button that 501s.
        Map<String, Boolean> implemented = new LinkedHashMap<>();
        implemented.put("upload", true);
        implemented.put("analysis", true);
        implemented.put("planning", true);
        implemented.put("preview", true);
        implemented.put("render", true);
        // Whisper transcription and beat detection are not built yet; the UI
        // keeps those two options disabled rather than offering them.
        implemented.put("captions", false);
        implemented.put("music", true);
        capabilities.put("implemented", implemented);

        return capabilities;
    }

    // ---- projects ----------------------------------------------------------

    @PostMapping("/projects")
    public ResponseEntity<ProjectView> create(@RequestBody VideoEditorProjectService.CreateProjectRequest request) {
        VideoEditorProject project = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectView.of(project));
    }

    @GetMapping("/projects")
    public List<ProjectView> list() {
        return service.list().stream().map(ProjectView::of).toList();
    }

    @GetMapping("/projects/{id}")
    public ProjectDetailView get(@PathVariable UUID id) {
        return new ProjectDetailView(
                ProjectView.of(service.get(id)),
                service.listClips(id).stream().map(ClipView::of).toList(),
                service.timeline(id).stream().map(TimelineClipView::of).toList(),
                service.currentPlan(id).map(EditingPlan::getRationale).orElse(null));
    }

    @DeleteMapping("/projects/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Partial settings update. Also mapped as POST /save because the brief's API
     * list names that path and the frontend may well be written against it -
     * cheaper to accept both than to have one of them 404.
     */
    @PatchMapping("/projects/{id}")
    public ProjectView update(@PathVariable UUID id,
                              @RequestBody VideoEditorProjectService.CreateProjectRequest request) {
        return ProjectView.of(service.updateSettings(id, request));
    }

    @PostMapping("/projects/{id}/save")
    public ProjectView save(@PathVariable UUID id,
                            @RequestBody VideoEditorProjectService.CreateProjectRequest request) {
        return ProjectView.of(service.updateSettings(id, request));
    }

    // ---- clips -------------------------------------------------------------

    @PostMapping(value = "/projects/{id}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<ClipView> upload(@PathVariable UUID id, @RequestParam("files") List<MultipartFile> files) {
        List<ClipView> added = new ArrayList<>();
        for (MultipartFile file : files) {
            added.add(ClipView.of(service.addClip(id, file)));
        }
        return added;
    }

    @PostMapping("/projects/{id}/upload/complete")
    public ProjectView completeUpload(@PathVariable UUID id) {
        service.finishUploading(id);
        return ProjectView.of(service.get(id));
    }

    @GetMapping("/projects/{id}/clips")
    public List<ClipView> clips(@PathVariable UUID id) {
        return service.listClips(id).stream().map(ClipView::of).toList();
    }

    @DeleteMapping("/projects/{id}/clips/{clipId}")
    public ResponseEntity<Void> removeClip(@PathVariable UUID id, @PathVariable UUID clipId) {
        service.removeClip(id, clipId);
        return ResponseEntity.noContent().build();
    }

    public record ReorderRequest(List<UUID> clipIds) {}

    @PostMapping("/projects/{id}/clips/reorder")
    public List<ClipView> reorder(@PathVariable UUID id, @RequestBody ReorderRequest request) {
        service.reorderClips(id, request.clipIds());
        return service.listClips(id).stream().map(ClipView::of).toList();
    }

    public record RenameRequest(String name) {}

    @PatchMapping("/projects/{id}/clips/{clipId}")
    public ClipView rename(@PathVariable UUID id, @PathVariable UUID clipId,
                           @RequestBody RenameRequest request) {
        return ClipView.of(service.renameClip(id, clipId, request.name()));
    }

    /** Serves a stored file. Paths are resolved through the storage service's
     *  containment check, never built from the request. */
    @GetMapping("/projects/{id}/clips/{clipId}/file")
    public ResponseEntity<Resource> sourceFile(@PathVariable UUID id, @PathVariable UUID clipId) {
        return service.listClips(id).stream()
                .filter(c -> c.getId().equals(clipId) && c.getStoredFilename() != null)
                .findFirst()
                .map(c -> storage.resolveWithin(storage.uploadsDir(id), c.getStoredFilename()))
                .map(file -> serveVideo(file, MediaType.parseMediaType("video/mp4")))
                .orElseGet(() -> ResponseEntity.<Resource>notFound().build());
    }

    @GetMapping("/projects/{id}/clips/{clipId}/thumbnail")
    public ResponseEntity<Resource> thumbnail(@PathVariable UUID id, @PathVariable UUID clipId) {
        return service.listClips(id).stream()
                .filter(c -> c.getId().equals(clipId))
                .findFirst()
                .map(VideoClip::getThumbnailFilename)
                .map(name -> serve(storage.resolveWithin(storage.thumbnailsDir(id), name), MediaType.IMAGE_JPEG))
                .orElseGet(() -> ResponseEntity.<Resource>notFound().build());
    }

    // ---- timeline ----------------------------------------------------------

    @GetMapping("/projects/{id}/timeline")
    public List<TimelineClipView> timeline(@PathVariable UUID id) {
        return service.timeline(id).stream().map(TimelineClipView::of).toList();
    }

    public record TimelineRequest(List<VideoEditorProjectService.TimelineEntry> timeline) {}

    /**
     * Replaces the timeline. Whole-list rather than per-row: trimming one clip
     * shifts everything after it, so a partial update would need the client to
     * send the consequences anyway, and undo/redo is far simpler when each
     * history entry is a complete timeline.
     */
    @PutMapping("/projects/{id}/timeline")
    public List<TimelineClipView> replaceTimeline(@PathVariable UUID id,
                                                  @RequestBody TimelineRequest request) {
        return service.replaceTimeline(id, request.timeline()).stream()
                .map(TimelineClipView::of).toList();
    }

    // ---- version history -----------------------------------------------------

    public record VersionView(UUID id, String planner, String rationale, int shotCount, java.time.Instant createdAt) {}

    @GetMapping("/projects/{id}/versions")
    public List<VersionView> versions(@PathVariable UUID id) {
        return service.versions(id).stream()
                .map(p -> new VersionView(p.getId(), p.getPlanner(), p.getRationale(),
                        countShots(p.getEdlJson()), p.getCreatedAt()))
                .toList();
    }

    @PostMapping("/projects/{id}/versions/{planId}/restore")
    public List<TimelineClipView> restoreVersion(@PathVariable UUID id, @PathVariable UUID planId) {
        return service.restoreVersion(id, planId).stream()
                .map(TimelineClipView::of).toList();
    }

    private int countShots(String edlJson) {
        try {
            return objectMapper.readTree(edlJson).path("timeline").size();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- audio and captions ------------------------------------------------

    @PostMapping(value = "/projects/{id}/music", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AudioTrackView addMusic(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        return AudioTrackView.of(service.addAudio(id, file, AudioTrackKind.MUSIC));
    }

    @PostMapping(value = "/projects/{id}/voiceover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AudioTrackView addVoiceover(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        return AudioTrackView.of(service.addAudio(id, file, AudioTrackKind.VOICEOVER));
    }

    @GetMapping("/projects/{id}/audio")
    public List<AudioTrackView> audio(@PathVariable UUID id) {
        return service.audioTracks(id).stream().map(AudioTrackView::of).toList();
    }

    @DeleteMapping("/projects/{id}/audio/{trackId}")
    public ResponseEntity<Void> removeAudio(@PathVariable UUID id, @PathVariable UUID trackId) {
        service.removeAudio(id, trackId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/projects/{id}/captions")
    public CaptionView captions(@PathVariable UUID id,
                                @RequestBody VideoEditorProjectService.CaptionSettings settings) {
        return CaptionView.of(service.updateCaptions(id, settings));
    }

    // ---- progress ----------------------------------------------------------

    /** Render job status. Polling fallback for clients that cannot use SSE. */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<RenderJobView> job(@PathVariable UUID jobId) {
        return service.renderJob(jobId)
                .map(RenderJobView::of)
                .map(view -> ResponseEntity.<RenderJobView>ok(view))
                .orElseGet(() -> ResponseEntity.<RenderJobView>notFound().build());
    }


    /**
     * SSE progress for a render job, reusing the story pipeline's emitter
     * registry. It is keyed by UUID and carries no status type, so it works for
     * editor jobs without modification.
     */
    @GetMapping(value = "/jobs/{jobId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@PathVariable UUID jobId) {
        return jobEvents.subscribe(jobId);
    }

    // ---- pipeline ----------------------------------------------------------
    //
    // All four return immediately with a job id. The work runs on the editor's
    // own executor and reports over SSE at /jobs/{id}/progress - a render is
    // minutes of CPU, so holding the HTTP request open for it would time out at
    // the proxy long before the video was ready.

    @PostMapping("/projects/{id}/analyze")
    public Map<String, Object> analyze(@PathVariable UUID id) {
        // Be tolerant of clients that uploaded successfully but did not call
        // the explicit completion endpoint. This keeps Analyse idempotent from
        // the user's perspective and prevents a stale UPLOADING state from
        // blocking the editor.
        VideoEditorProject current = service.get(id);
        if (current.getState() == VideoEditorState.DRAFT
                || current.getState() == VideoEditorState.UPLOADING) {
            service.finishUploading(id);
        }
        service.transition(id, VideoEditorState.ANALYZING);
        RenderJob job = renderService.createJob(id, RenderKind.PREVIEW);
        editorExecutor.execute(() -> {
            try {
                analysisService.analyzeProject(id, job.getId());
                jobState.completeOperation(job.getId());
                progress.finish(job.getId(), "Analysis complete");
            } catch (Exception e) {
                service.fail(id, message(e), e);
                progress.fail(job.getId(), message(e));
            }
        });
        return Map.of("jobId", job.getId(), "status", "ANALYZING");
    }

    public record AiEditRequest(Boolean useAiDirector) {}

    @PostMapping("/projects/{id}/ai-edit")
    public Map<String, Object> aiEdit(@PathVariable UUID id,
                                      @RequestBody(required = false) AiEditRequest request) {
        service.transition(id, VideoEditorState.PLANNING);
        RenderJob job = renderService.createJob(id, RenderKind.PREVIEW);
        // Defaults to true, but the rule engine runs either way - this only
        // decides whether the LLM gets to reorder its output.
        boolean useLlm = request == null || request.useAiDirector() == null || request.useAiDirector();
        editorExecutor.execute(() -> {
            try {
                plannerService.plan(id, job.getId(), useLlm);
                jobState.completeOperation(job.getId());
                progress.finish(job.getId(), "Edit plan ready");
            } catch (Exception e) {
                service.fail(id, message(e), e);
                progress.fail(job.getId(), message(e));
            }
        });
        return Map.of("jobId", job.getId(), "status", "PLANNING");
    }

    @PostMapping("/projects/{id}/preview")
    public Map<String, Object> preview(@PathVariable UUID id) {
        return startRender(id, RenderKind.PREVIEW);
    }

    @PostMapping("/projects/{id}/render")
    public Map<String, Object> render(@PathVariable UUID id) {
        return startRender(id, RenderKind.FINAL);
    }

    private Map<String, Object> startRender(UUID id, RenderKind kind) {
        // Validate/claim the project state BEFORE creating the async job.
        // Previously the job was created first and the state transition happened
        // inside the worker. After a failed render the project remained FAILED,
        // so a retry created a new job and then crashed with:
        // "Cannot go from FAILED to RENDERING".
        //
        // Claiming the state synchronously also makes double-clicks safe: the
        // second request sees an active state and is rejected before a second
        // render job is created.
        VideoEditorProject project = service.get(id);
        if (timelineForProject(id).isEmpty()) {
            throw new IllegalStateException("There is no timeline to render. Run AI Edit or add clips to the timeline first.");
        }
        VideoEditorState target = kind == RenderKind.PREVIEW
                ? VideoEditorState.PREVIEW_RENDERING
                : VideoEditorState.RENDERING;
        service.transition(id, target);

        RenderJob job = renderService.createJob(id, kind);
        editorExecutor.execute(() -> renderService.render(id, job.getId(), kind));
        return Map.of("jobId", job.getId(), "kind", kind.name(), "status", target.name());
    }

    private List<TimelineClip> timelineForProject(UUID id) {
        return service.timeline(id);
    }

    /** Streams a rendered file. Range support so the browser can scrub a
     *  preview without downloading the whole thing first. */
    @GetMapping("/projects/{id}/renders/{jobId}/file")
    public ResponseEntity<Resource> renderedFile(@PathVariable UUID id, @PathVariable UUID jobId) {
        return service.renderJob(jobId)
                .filter(j -> j.getProjectId().equals(id) && j.getOutputFilename() != null)
                .<ResponseEntity<Resource>>map(j -> {
                    Path dir = j.getKind() == RenderKind.PREVIEW
                            ? storage.previewsDir(id) : storage.rendersDir(id);
                    Path file = storage.resolveWithin(dir, j.getOutputFilename());
                    if (!Files.exists(file)) {
                        return ResponseEntity.<Resource>notFound().build();
                    }
                    return ResponseEntity.<Resource>ok()
                            .contentType(MediaType.parseMediaType("video/mp4"))
                            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "inline; filename=\"" + j.getKind().name().toLowerCase() + ".mp4\"")
                            .body((Resource) new FileSystemResource(file));
                })
                .orElseGet(() -> ResponseEntity.<Resource>notFound().build());
    }

    /** Explicit download endpoint. The inline file endpoint is for browser
     *  playback/scrubbing; this one forces a Save/Download response. */
    @GetMapping("/projects/{id}/renders/{jobId}/download")
    public ResponseEntity<Resource> downloadRenderedFile(@PathVariable UUID id, @PathVariable UUID jobId) {
        return service.renderJob(jobId)
                .filter(j -> j.getProjectId().equals(id) && j.getOutputFilename() != null)
                .<ResponseEntity<Resource>>map(j -> {
                    Path dir = j.getKind() == RenderKind.PREVIEW
                            ? storage.previewsDir(id) : storage.rendersDir(id);
                    Path file = storage.resolveWithin(dir, j.getOutputFilename());
                    if (!Files.exists(file)) {
                        return ResponseEntity.<Resource>notFound().build();
                    }
                    String filename = j.getKind() == RenderKind.FINAL
                            ? "ai-story-studio-final.mp4" : "ai-story-studio-preview.mp4";
                    return ResponseEntity.<Resource>ok()
                            .contentType(MediaType.parseMediaType("video/mp4"))
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                            .body((Resource) new FileSystemResource(file));
                })
                .orElseGet(() -> ResponseEntity.<Resource>notFound().build());
    }

    private ResponseEntity<Resource> serveVideo(Path file, MediaType mediaType) {
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(new FileSystemResource(file));
    }

    private String message(Exception e) {
        return e.getMessage() == null ? "The operation failed. See the backend logs." : e.getMessage();
    }

    // ---- error handling ----------------------------------------------------

    /**
     * Maps exceptions to readable messages. Stack traces stay server-side
     * (brief §38); the service layer already logged the technical detail.
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    private ResponseEntity<Resource> serve(Path path, MediaType type) {
        if (!Files.exists(path)) {
            return ResponseEntity.<Resource>notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                .body(new FileSystemResource(path));
    }

    // ---- view models -------------------------------------------------------
    // Explicit DTOs rather than returning entities: stored filenames are an
    // internal detail and should not appear in API responses at all.

    public record ProjectView(UUID id, String name, VideoCategory category, EditingStyle editingStyle,
                              EditIntensity intensity, AspectRatio aspectRatio, Integer targetDurationSec,
                              String customInstructions, VideoEditorState state, String errorMessage,
                              boolean smartCuts, boolean beatSync, boolean smartTransitions,
                              boolean autoCaptions, boolean audioEnhancement, boolean smartReframing) {
        static ProjectView of(VideoEditorProject p) {
            return new ProjectView(p.getId(), p.getName(), p.getCategory(), p.getEditingStyle(),
                    p.getIntensity(), p.getAspectRatio(), p.getTargetDurationSec(),
                    p.getCustomInstructions(), p.getState(), p.getErrorMessage(),
                    p.isSmartCuts(), p.isBeatSync(), p.isSmartTransitions(),
                    p.isAutoCaptions(), p.isAudioEnhancement(), p.isSmartReframing());
        }
    }

    public record ClipView(UUID id, String displayName, int sortOrder, Long sizeBytes,
                           Double durationSec, Integer width, Integer height, Double fps,
                           String videoCodec, String audioCodec, boolean hasAudio,
                           boolean analyzed, boolean portrait) {
        static ClipView of(VideoClip c) {
            return new ClipView(c.getId(), c.getDisplayName(), c.getSortOrder(), c.getSizeBytes(),
                    c.getDurationSec(), c.getWidth(), c.getHeight(), c.getFps(),
                    c.getVideoCodec(), c.getAudioCodec(), c.isHasAudio(),
                    c.isAnalyzed(), c.isPortrait());
        }
    }

    public record TimelineClipView(UUID id, UUID clipId, int sortOrder, double sourceStartSec,
                                   double sourceEndSec, String techniqueIn, String techniqueOut,
                                   Double transitionSec, double speed, double volume,
                                   boolean muted, boolean locked, String reason, double outputDurationSec) {
        static TimelineClipView of(TimelineClip t) {
            return new TimelineClipView(t.getId(), t.getClipId(), t.getSortOrder(),
                    t.getSourceStartSec(), t.getSourceEndSec(), t.getTechniqueIn(), t.getTechniqueOut(),
                    t.getTransitionSec(), t.getSpeed(), t.getVolume(), t.isMuted(), t.isLocked(), t.getReason(),
                    t.outputDurationSec());
        }
    }

    public record AudioTrackView(UUID id, AudioTrackKind kind, String displayName,
                                 double gain, boolean duckUnderSpeech, Double bpm) {
        static AudioTrackView of(AudioTrack t) {
            return new AudioTrackView(t.getId(), t.getKind(), t.getDisplayName(),
                    t.getGain(), t.isDuckUnderSpeech(), t.getBpm());
        }
    }

    public record CaptionView(String stylePreset, boolean burnIn, boolean hasTranscript) {
        static CaptionView of(CaptionTrack t) {
            return new CaptionView(t.getStylePreset(), t.isBurnIn(), t.getTranscriptJson() != null);
        }
    }

    public record RenderJobView(UUID id, UUID projectId, RenderKind kind, VideoEditorState status,
                                int progressPercent, String stage, String errorMessage) {
        static RenderJobView of(RenderJob j) {
            return new RenderJobView(j.getId(), j.getProjectId(), j.getKind(), j.getStatus(),
                    j.getProgressPercent(), j.getStage(), j.getErrorMessage());
        }
    }

    public record ProjectDetailView(ProjectView project, List<ClipView> clips,
                                    List<TimelineClipView> timeline, String rationale) {}
}
