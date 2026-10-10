package com.aistorystudio.videoeditor.service;

import com.aistorystudio.videoeditor.config.VideoEditorProperties;
import com.aistorystudio.videoeditor.domain.*;
import com.aistorystudio.videoeditor.domain.enums.*;
import com.aistorystudio.videoeditor.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Project CRUD, clip upload and the project state machine.
 *
 * Analysis, planning and rendering live in their own services (phases 4-7);
 * this class owns persistence, validation and legal state transitions so those
 * services never have to reason about whether a transition is allowed.
 */
@Service
public class VideoEditorProjectService {

    private static final Logger log = LoggerFactory.getLogger(VideoEditorProjectService.class);

    /**
     * Legal transitions.
     *
     * Held as an explicit map rather than scattered `if (state == ...)` checks
     * because the editor has 13 states and 6 entry points that mutate them.
     * With ad-hoc checks, "analyse while rendering" is the kind of bug that only
     * shows up once a render is slow enough for a user to click something else -
     * which on this hardware is always.
     */
    private static final Map<VideoEditorState, Set<VideoEditorState>> TRANSITIONS = Map.ofEntries(
            Map.entry(VideoEditorState.DRAFT,             EnumSet.of(VideoEditorState.UPLOADING, VideoEditorState.CANCELLED)),
            Map.entry(VideoEditorState.UPLOADING,         EnumSet.of(VideoEditorState.UPLOADED, VideoEditorState.FAILED, VideoEditorState.CANCELLED)),
            Map.entry(VideoEditorState.UPLOADED,          EnumSet.of(VideoEditorState.UPLOADING, VideoEditorState.ANALYZING, VideoEditorState.CANCELLED)),
            Map.entry(VideoEditorState.ANALYZING,         EnumSet.of(VideoEditorState.ANALYZED, VideoEditorState.FAILED, VideoEditorState.CANCELLED)),
            Map.entry(VideoEditorState.ANALYZED,          EnumSet.of(VideoEditorState.PLANNING, VideoEditorState.UPLOADING, VideoEditorState.ANALYZING, VideoEditorState.CANCELLED)),
            Map.entry(VideoEditorState.PLANNING,          EnumSet.of(VideoEditorState.PLAN_READY, VideoEditorState.FAILED, VideoEditorState.CANCELLED)),
            Map.entry(VideoEditorState.PLAN_READY,        EnumSet.of(VideoEditorState.PREVIEW_RENDERING, VideoEditorState.RENDERING, VideoEditorState.PLANNING, VideoEditorState.CANCELLED)),
            Map.entry(VideoEditorState.PREVIEW_RENDERING, EnumSet.of(VideoEditorState.PREVIEW_READY, VideoEditorState.FAILED, VideoEditorState.CANCELLED)),
            Map.entry(VideoEditorState.PREVIEW_READY,     EnumSet.of(VideoEditorState.RENDERING, VideoEditorState.PREVIEW_RENDERING, VideoEditorState.PLANNING, VideoEditorState.CANCELLED)),
            Map.entry(VideoEditorState.RENDERING,         EnumSet.of(VideoEditorState.COMPLETED, VideoEditorState.FAILED, VideoEditorState.CANCELLED)),
            // FAILED is terminal for the failed operation, not for the project.
            // A user must be able to retry after a transient FFmpeg/disk/input
            // problem without creating a new project.
            Map.entry(VideoEditorState.FAILED,            EnumSet.of(VideoEditorState.ANALYZING, VideoEditorState.PLANNING,
                                                          VideoEditorState.PREVIEW_RENDERING, VideoEditorState.RENDERING,
                                                          VideoEditorState.CANCELLED)),
            // A completed render can be rendered again after a manual edit or
            // simply when the user wants another final export.
            Map.entry(VideoEditorState.COMPLETED,         EnumSet.of(VideoEditorState.RENDERING, VideoEditorState.PREVIEW_RENDERING,
                                                          VideoEditorState.PLANNING, VideoEditorState.ANALYZING,
                                                          VideoEditorState.CANCELLED))
    );

    private final VideoEditorProjectRepository projects;
    private final VideoClipRepository clips;
    private final VideoSceneRepository scenes;
    private final VideoAnalysisRepository analyses;
    private final TimelineClipRepository timeline;
    private final EditingPlanRepository plans;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RenderJobRepository renderJobs;
    private final AudioTrackRepository audioTracks;
    private final CaptionTrackRepository captionTracks;
    private final VideoEditorStorageService storage;
    private final VideoEditorProperties properties;

    public VideoEditorProjectService(VideoEditorProjectRepository projects,
                                     VideoClipRepository clips,
                                     VideoSceneRepository scenes,
                                     VideoAnalysisRepository analyses,
                                     TimelineClipRepository timeline,
                                     EditingPlanRepository plans,
                                     RenderJobRepository renderJobs,
                                     AudioTrackRepository audioTracks,
                                     CaptionTrackRepository captionTracks,
                                     VideoEditorStorageService storage,
                                     VideoEditorProperties properties) {
        this.projects = projects;
        this.clips = clips;
        this.scenes = scenes;
        this.analyses = analyses;
        this.timeline = timeline;
        this.plans = plans;
        this.renderJobs = renderJobs;
        this.audioTracks = audioTracks;
        this.captionTracks = captionTracks;
        this.storage = storage;
        this.properties = properties;
    }

    // ---- projects ----------------------------------------------------------

    private static void applyLookTitleStabilize(VideoEditorProject project, CreateProjectRequest r) {
        if (r.look() != null) {
            String l = r.look().trim().toLowerCase(java.util.Locale.ROOT);
            project.setLook(l.isEmpty() || l.equals("auto") ? null : l);
        }
        if (r.titleText() != null) {
            String t = r.titleText().replaceAll("\\s+", " ").trim();
            project.setTitleText(t.isEmpty() ? null : (t.length() > 120 ? t.substring(0, 120) : t));
        }
        if (r.stabilize() != null) project.setStabilize(r.stabilize());
        if (r.effects() != null) {
            // only known ids survive, in a stable order: the client can never smuggle filter syntax in
            List<String> keep = new ArrayList<>();
            for (String id : r.effects().toLowerCase(java.util.Locale.ROOT).split("[,\\s]+")) {
                if (com.aistorystudio.videoeditor.editing.TimelineRenderer.EFFECTS.containsKey(id) && !keep.contains(id)) keep.add(id);
            }
            project.setEffects(keep.isEmpty() ? null : String.join(",", keep));
        }
    }

    public record CreateProjectRequest(
            String name,
            VideoCategory category,
            EditingStyle editingStyle,
            EditIntensity intensity,
            AspectRatio aspectRatio,
            Integer targetDurationSec,
            String customInstructions,
            Boolean smartCuts,
            Boolean beatSync,
            Boolean smartTransitions,
            Boolean autoCaptions,
            Boolean audioEnhancement,
            Boolean smartReframing,
            String look,
            String titleText,
            Boolean stabilize,
            String effects) {}

    @Transactional
    public VideoEditorProject create(CreateProjectRequest request) {
        VideoEditorProject project = new VideoEditorProject();
        project.setName(request.name() == null || request.name().isBlank()
                ? "Untitled edit" : request.name().trim());
        if (request.category() != null) project.setCategory(request.category());
        if (request.aspectRatio() != null) project.setAspectRatio(request.aspectRatio());
        project.setTargetDurationSec(request.targetDurationSec());
        project.setCustomInstructions(request.customInstructions());

        // Style and intensity have category-aware defaults rather than fixed
        // ones: a KIDS_STORY left on the generic default would come out cut like
        // a trending reel, which is the wrong result for the app's main use.
        project.setEditingStyle(request.editingStyle() != null
                ? request.editingStyle() : defaultStyleFor(project.getCategory()));
        project.setIntensity(request.intensity() != null
                ? request.intensity() : defaultIntensityFor(project.getCategory()));

        if (request.smartCuts() != null) project.setSmartCuts(request.smartCuts());
        if (request.beatSync() != null) project.setBeatSync(request.beatSync());
        if (request.smartTransitions() != null) project.setSmartTransitions(request.smartTransitions());
        if (request.autoCaptions() != null) project.setAutoCaptions(request.autoCaptions());
        if (request.audioEnhancement() != null) project.setAudioEnhancement(request.audioEnhancement());
        if (request.smartReframing() != null) project.setSmartReframing(request.smartReframing());
        applyLookTitleStabilize(project, request);

        return projects.save(project);
    }

    static EditingStyle defaultStyleFor(VideoCategory category) {
        return switch (category) {
            case KIDS_STORY -> EditingStyle.KIDS_STORY;
            case REEL, SHORT, PROMO -> EditingStyle.TRENDING_REEL;
            case BIRTHDAY -> EditingStyle.BIRTHDAY;
            case VLOG -> EditingStyle.VLOG;
            case MUSIC_VIDEO -> EditingStyle.MUSIC_VIDEO;
            case CUSTOM -> EditingStyle.CUSTOM;
            case CINEMATIC, TRAVEL, YOUTUBE -> EditingStyle.CINEMATIC;
        };
    }

    static EditIntensity defaultIntensityFor(VideoCategory category) {
        return switch (category) {
            // Explicitly BALANCED, per the brief: flashing and spinning is both
            // unpleasant and an accessibility concern for young viewers.
            case KIDS_STORY -> EditIntensity.BALANCED;
            case REEL, SHORT, MUSIC_VIDEO, BIRTHDAY -> EditIntensity.DYNAMIC;
            case CINEMATIC -> EditIntensity.SUBTLE;
            default -> EditIntensity.BALANCED;
        };
    }

    @Transactional(readOnly = true)
    public VideoEditorProject get(UUID projectId) {
        return projects.findById(projectId).orElseThrow(() ->
                new NoSuchElementException("No video editor project " + projectId));
    }

    @Transactional(readOnly = true)
    public List<VideoEditorProject> list() {
        return projects.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional
    public void delete(UUID projectId) {
        // Rows cascade via the schema; the files do not, so remove them first
        // and let a file-deletion failure abort before the DB row disappears -
        // an orphaned directory with no project to attribute it to is worse
        // than a row that can be retried.
        storage.deleteProject(projectId);
        projects.deleteById(projectId);
    }

    // ---- state machine -----------------------------------------------------

    @Transactional
    public VideoEditorProject transition(UUID projectId, VideoEditorState target) {
        VideoEditorProject project = get(projectId);
        VideoEditorState current = project.getState();
        if (current == target) {
            return project;
        }
        Set<VideoEditorState> allowed = TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new IllegalStateException(
                    "Cannot go from " + current + " to " + target +
                    ". The project is still processing another operation, or the previous operation failed.\n" +
                    "Retry the failed step or return to the last completed stage.");
        }
        project.setState(target);
        if (target != VideoEditorState.FAILED) {
            project.setErrorMessage(null);
        }
        return projects.save(project);
    }

    @Transactional
    public void fail(UUID projectId, String userFacingMessage, Throwable cause) {
        // Log the technical detail, persist only the readable message: stack
        // traces must not reach the UI (brief §38).
        log.error("Video editor project {} failed: {}", projectId, userFacingMessage, cause);
        projects.findById(projectId).ifPresent(project -> {
            project.setState(VideoEditorState.FAILED);
            project.setErrorMessage(userFacingMessage);
            projects.save(project);
        });
    }

    /** Short-form preset used by "best moments": optional vertical canvas + captions. The timeline is replaced right after. */
    @Transactional
    public void prepareShort(UUID projectId, boolean vertical, boolean captions) {
        VideoEditorProject project = get(projectId);
        if (vertical) project.setAspectRatio(com.aistorystudio.videoeditor.domain.enums.AspectRatio.VERTICAL_9_16);
        if (captions) project.setAutoCaptions(true);
        projects.save(project);
    }

    // ---- clips -------------------------------------------------------------

    /**
     * Stores an uploaded clip and returns the row.
     *
     * Metadata is left null here: ffprobe runs in the analysis phase, and
     * blocking the upload request on it would make a 20-clip drag-and-drop feel
     * broken. The extension allowlist is enforced now, though, because writing
     * a file we already know we will reject is pointless.
     */
    @Transactional
    public VideoClip addClip(UUID projectId, MultipartFile file) {
        VideoEditorProject project = get(projectId);

        long existing = clips.countByProjectId(projectId);
        if (existing >= properties.getMaxClips()) {
            throw new IllegalStateException(
                    "This project already has the maximum of " + properties.getMaxClips() + " clips.");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("That file is empty.");
        }
        if (file.getSize() > properties.getMaxUploadBytes()) {
            throw new IllegalArgumentException(
                    "That file is larger than the " + properties.getMaxUploadMb() + " MB limit.");
        }

        String displayName = sanitiseDisplayName(file.getOriginalFilename());
        String extension = extensionOf(displayName);
        if (!properties.allowedVideoExtensions().contains(extension)) {
            throw new IllegalArgumentException(
                    "Unsupported video format '." + extension + "'. Supported: " +
                    String.join(", ", properties.allowedVideoExtensions()) + ".");
        }

        VideoClip clip = new VideoClip();
        clip.setProjectId(projectId);
        clip.setDisplayName(displayName);
        clip.setSizeBytes(file.getSize());
        clip.setSortOrder((int) existing);
        // The row is saved first so its generated UUID can become the stored
        // filename - that is what keeps user-supplied text out of the filesystem
        // entirely. stored_filename is NOT NULL though, so it needs a value for
        // this first insert; it is overwritten with the real name immediately
        // below, before the transaction commits.
        clip.setStoredFilename("pending");
        clip = clips.save(clip);

        clip.setStoredFilename(storage.storedFilenameFor(clip.getId(), extension));
        Path target = storage.resolveWithin(storage.uploadsDir(projectId), clip.getStoredFilename());
        try (var in = file.getInputStream()) {
            storage.write(target, in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save " + displayName, e);
        }

        if (project.getState() == VideoEditorState.DRAFT) {
            transition(projectId, VideoEditorState.UPLOADING);
        }
        return clips.save(clip);
    }

    @Transactional
    public void finishUploading(UUID projectId) {
        if (clips.countByProjectId(projectId) == 0) {
            throw new IllegalStateException("Add at least one video before continuing.");
        }
        transition(projectId, VideoEditorState.UPLOADED);
    }

    @Transactional(readOnly = true)
    public List<VideoClip> listClips(UUID projectId) {
        return clips.findByProjectIdOrderBySortOrderAsc(projectId);
    }

    @Transactional
    public void removeClip(UUID projectId, UUID clipId) {
        VideoClip clip = clips.findById(clipId)
                .filter(c -> c.getProjectId().equals(projectId))
                .orElseThrow(() -> new NoSuchElementException("No clip " + clipId + " in this project"));

        if (clip.getStoredFilename() != null) {
            storage.deleteQuietly(storage.resolveWithin(storage.uploadsDir(projectId), clip.getStoredFilename()));
        }
        if (clip.getProxyFilename() != null) {
            storage.deleteQuietly(storage.resolveWithin(storage.proxiesDir(projectId), clip.getProxyFilename()));
        }
        scenes.deleteByClipId(clipId);
        analyses.deleteByClipId(clipId);
        clips.delete(clip);

        // Any existing timeline referenced this clip, so it is now invalid.
        // Dropping it is safer than leaving rows that point at a deleted file
        // and failing at render time.
        timeline.deleteByProjectId(projectId);
        resequence(projectId);
    }

    @Transactional
    public void reorderClips(UUID projectId, List<UUID> orderedClipIds) {
        Map<UUID, VideoClip> byId = new LinkedHashMap<>();
        for (VideoClip clip : clips.findByProjectIdOrderBySortOrderAsc(projectId)) {
            byId.put(clip.getId(), clip);
        }
        if (!byId.keySet().containsAll(orderedClipIds) || orderedClipIds.size() != byId.size()) {
            throw new IllegalArgumentException(
                    "The reorder request must list every clip in this project exactly once.");
        }
        int order = 0;
        for (UUID clipId : orderedClipIds) {
            byId.get(clipId).setSortOrder(order++);
        }
        clips.saveAll(byId.values());
    }

    @Transactional
    public VideoClip renameClip(UUID projectId, UUID clipId, String newName) {
        VideoClip clip = clips.findById(clipId)
                .filter(c -> c.getProjectId().equals(projectId))
                .orElseThrow(() -> new NoSuchElementException("No clip " + clipId + " in this project"));
        clip.setDisplayName(sanitiseDisplayName(newName));
        return clips.save(clip);
    }

    private void resequence(UUID projectId) {
        List<VideoClip> remaining = clips.findByProjectIdOrderBySortOrderAsc(projectId);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setSortOrder(i);
        }
        clips.saveAll(remaining);
    }

    /**
     * Updates project settings.
     *
     * Every field is optional: this is a partial update, so the UI can save one
     * changed dropdown without having to send back a whole project it might be
     * holding a stale copy of.
     *
     * Changing anything the planner reads invalidates the existing plan and
     * timeline. That is done here rather than left to the caller because a
     * timeline that was built for a 15-second 9:16 reel is simply wrong once the
     * project becomes a 90-second 16:9 cut, and silently keeping it would render
     * a video that does not match the settings on screen.
     */
    @Transactional
    public VideoEditorProject updateSettings(UUID projectId, CreateProjectRequest request) {
        VideoEditorProject project = get(projectId);
        boolean planAffected = false;

        if (request.name() != null && !request.name().isBlank()) {
            project.setName(request.name().trim());
        }
        if (request.category() != null && request.category() != project.getCategory()) {
            project.setCategory(request.category());
            planAffected = true;
        }
        if (request.editingStyle() != null && request.editingStyle() != project.getEditingStyle()) {
            project.setEditingStyle(request.editingStyle());
            planAffected = true;
        }
        if (request.intensity() != null && request.intensity() != project.getIntensity()) {
            project.setIntensity(request.intensity());
            planAffected = true;
        }
        if (request.aspectRatio() != null && request.aspectRatio() != project.getAspectRatio()) {
            project.setAspectRatio(request.aspectRatio());
            planAffected = true;
        }
        if (!Objects.equals(request.targetDurationSec(), project.getTargetDurationSec())) {
            project.setTargetDurationSec(request.targetDurationSec());
            planAffected = true;
        }
        if (request.customInstructions() != null) {
            project.setCustomInstructions(request.customInstructions());
            planAffected = true;
        }
        if (request.smartCuts() != null) { project.setSmartCuts(request.smartCuts()); planAffected = true; }
        if (request.beatSync() != null) { project.setBeatSync(request.beatSync()); planAffected = true; }
        if (request.smartTransitions() != null) { project.setSmartTransitions(request.smartTransitions()); planAffected = true; }
        if (request.autoCaptions() != null) { project.setAutoCaptions(request.autoCaptions()); }
        if (request.audioEnhancement() != null) { project.setAudioEnhancement(request.audioEnhancement()); }
        if (request.smartReframing() != null) { project.setSmartReframing(request.smartReframing()); planAffected = true; }
        // look / title / stabilise change the pixels only, not the cuts: the plan stays valid, the preview cache key changes
        applyLookTitleStabilize(project, request);

        if (planAffected && hasPlan(projectId)) {
            timeline.deleteByProjectId(projectId);
            // Back to ANALYZED so the UI shows "needs re-planning" rather than
            // PLAN_READY with no timeline behind it.
            if (project.getState() == VideoEditorState.PLAN_READY
                    || project.getState() == VideoEditorState.PREVIEW_READY
                    || project.getState() == VideoEditorState.COMPLETED) {
                project.setState(VideoEditorState.ANALYZED);
            }
        }
        return projects.save(project);
    }

    private boolean hasPlan(UUID projectId) {
        return !timeline.findByProjectIdOrderBySortOrderAsc(projectId).isEmpty();
    }

    // ---- timeline ----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TimelineClip> timeline(UUID projectId) {
        return timeline.findByProjectIdOrderBySortOrderAsc(projectId);
    }

    @Transactional(readOnly = true)
    public Optional<EditingPlan> currentPlan(UUID projectId) {
        return plans.findFirstByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public List<RenderJob> renderJobs(UUID projectId) {
        return renderJobs.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /** One row of a caller-supplied timeline. Techniques are IDs, never filters. */
    public record TimelineEntry(UUID clipId, double sourceStartSec, double sourceEndSec,
                                String techniqueIn, String techniqueOut, Double transitionSec,
                                Double speed, Double volume, Boolean muted, Boolean locked) {}

    /**
     * Replaces the timeline wholesale.
     *
     * Whole-list replacement rather than per-row PATCH because a timeline is only
     * meaningful as an ordered sequence: trimming clip 3 shifts everything after
     * it, so a per-row update would need the client to send the consequences
     * anyway. Replacing also makes undo/redo on the client trivial - each history
     * entry is a complete timeline.
     *
     * Every entry is validated against the project's own clips before anything is
     * written, so a partially-applied timeline is not a reachable state.
     */
    @Transactional
    public List<TimelineClip> replaceTimeline(UUID projectId, List<TimelineEntry> entries) {
        VideoEditorProject project = get(projectId);
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("A timeline needs at least one clip.");
        }

        Map<UUID, VideoClip> owned = new HashMap<>();
        for (VideoClip clip : clips.findByProjectIdOrderBySortOrderAsc(projectId)) {
            owned.put(clip.getId(), clip);
        }

        List<TimelineClip> rows = new ArrayList<>(entries.size());
        int order = 0;
        for (TimelineEntry entry : entries) {
            VideoClip source = owned.get(entry.clipId());
            if (source == null) {
                throw new IllegalArgumentException(
                        "Timeline references a clip that is not in this project.");
            }
            double start = entry.sourceStartSec();
            double end = entry.sourceEndSec();
            if (end <= start) {
                throw new IllegalArgumentException(
                        "Clip \"" + source.getDisplayName() + "\" has an end time at or before its start.");
            }
            // Clamp to the source's real length when known. An out-of-range
            // trim is not an error worth rejecting the whole save for - it
            // happens when a user drags past the end - but passing it to FFmpeg
            // produces a silent black tail, which is worse than a shorter clip.
            if (source.getDurationSec() != null) {
                double max = source.getDurationSec();
                start = Math.max(0, Math.min(start, Math.max(0, max - 0.05)));
                end = Math.min(end, max);
                if (end <= start) {
                    throw new IllegalArgumentException(
                            "The trim for \"" + source.getDisplayName() + "\" falls outside the clip.");
                }
            }

            TimelineClip row = new TimelineClip();
            row.setProjectId(projectId);
            row.setClipId(entry.clipId());
            row.setSortOrder(order++);
            row.setSourceStartSec(start);
            row.setSourceEndSec(end);
            row.setTechniqueIn(entry.techniqueIn());
            row.setTechniqueOut(entry.techniqueOut());
            row.setTransitionSec(entry.transitionSec());
            row.setSpeed(entry.speed() == null || entry.speed() <= 0 ? 1.0 : entry.speed());
            row.setVolume(entry.volume() == null || entry.volume() < 0 ? 1.0 : entry.volume());
            row.setMuted(Boolean.TRUE.equals(entry.muted()));
            row.setLocked(Boolean.TRUE.equals(entry.locked()));
            rows.add(row);
        }

        timeline.deleteByProjectId(projectId);
        List<TimelineClip> saved = timeline.saveAll(rows);

        // A hand-edited timeline is no longer the planner's output, so record
        // that: otherwise the "why this edit?" rationale would describe cuts the
        // user has since replaced.
        EditingPlan plan = new EditingPlan();
        plan.setProjectId(projectId);
        plan.setEdlJson(serialiseTimeline(saved));
        plan.setPlanner(EditingPlan.PLANNER_MANUAL);
        plan.setPlanHash(hashTimeline(saved));
        plans.save(plan);

        if (project.getState() == VideoEditorState.ANALYZED) {
            transition(projectId, VideoEditorState.PLANNING);
            transition(projectId, VideoEditorState.PLAN_READY);
        }
        return saved;
    }

    /**
     * Inserts a source range as a new shot (after {@code afterIndex}, -1 = at the start, null/out of range = at the end).
     * Used by "search what was said -> insert". Goes through replaceTimeline, so it is validated and versioned (undo works).
     */
    @Transactional
    public List<TimelineClip> insertSegment(UUID projectId, UUID clipId, double startSec, double endSec, Integer afterIndex) {
        List<TimelineClip> current = timeline.findByProjectIdOrderBySortOrderAsc(projectId);
        List<TimelineEntry> entries = new ArrayList<>();
        for (TimelineClip r : current) {
            entries.add(new TimelineEntry(r.getClipId(), r.getSourceStartSec(), r.getSourceEndSec(), r.getTechniqueIn(),
                    r.getTechniqueOut(), r.getTransitionSec(), r.getSpeed(), r.getVolume(), r.isMuted(), r.isLocked()));
        }
        TimelineEntry added = new TimelineEntry(clipId, Math.max(0, startSec), endSec, null, null, null, 1.0, 1.0, false, false);
        int at = afterIndex == null || afterIndex >= entries.size() ? entries.size() : Math.max(0, afterIndex + 1);
        entries.add(at, added);
        return replaceTimeline(projectId, entries);
    }

    // ---- versions -----------------------------------------------------------

    /** Every plan ever generated or hand-saved for this project, newest
     *  first. Nothing here is ever deleted, so this is the full history. */
    public List<EditingPlan> versions(UUID projectId) {
        get(projectId);
        return plans.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /**
     * Makes an older version the live timeline again, without touching any
     * other version's row - restoring is itself just another save, so it
     * shows up as a new entry at the top of the history rather than erasing
     * what came after it. That is what makes "restore, didn't like it,
     * restore something else" always safe.
     */
    @Transactional
    public List<TimelineClip> restoreVersion(UUID projectId, UUID planId) {
        EditingPlan version = plans.findById(planId)
                .filter(p -> p.getProjectId().equals(projectId))
                .orElseThrow(() -> new IllegalArgumentException("That version was not found for this project."));

        Map<UUID, VideoClip> owned = new HashMap<>();
        for (VideoClip clip : clips.findByProjectIdOrderBySortOrderAsc(projectId)) {
            owned.put(clip.getId(), clip);
        }

        List<TimelineEntry> entries = new ArrayList<>();
        JsonNode root;
        try {
            root = mapper.readTree(version.getEdlJson());
        } catch (IOException e) {
            throw new IllegalStateException("That version's saved timeline could not be read.", e);
        }
        for (JsonNode row : root.path("timeline")) {
            UUID clipId;
            try {
                clipId = UUID.fromString(row.path("clipId").asText());
            } catch (IllegalArgumentException e) {
                continue; // Skip a row referencing a clip id that isn't parseable.
            }
            if (!owned.containsKey(clipId)) {
                continue; // The clip was since removed from the project; skip its shot.
            }
            entries.add(new TimelineEntry(
                    clipId,
                    row.path("sourceStart").asDouble(0),
                    row.path("sourceEnd").asDouble(0),
                    row.hasNonNull("techniqueIn") ? row.path("techniqueIn").asText() : null,
                    row.hasNonNull("techniqueOut") ? row.path("techniqueOut").asText() : null,
                    row.hasNonNull("transitionSec") ? row.path("transitionSec").asDouble() : null,
                    row.path("speed").asDouble(1.0),
                    row.has("volume") ? row.path("volume").asDouble(1.0) : 1.0,
                    row.path("muted").asBoolean(false),
                    row.path("locked").asBoolean(false)));
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException(
                    "That version's shots no longer exist in this project (their source clips were removed).");
        }
        return replaceTimeline(projectId, entries);
    }

    /**
     * Content hash of a timeline. Previews are stored under this name, so an
     * undo/redo round trip resolves to an existing render instead of re-encoding.
     */
    static String hashTimeline(List<TimelineClip> rows) {
        StringBuilder sb = new StringBuilder();
        for (TimelineClip r : rows) {
            sb.append(r.getClipId()).append('|')
              .append(String.format(Locale.ROOT, "%.3f", r.getSourceStartSec())).append('|')
              .append(String.format(Locale.ROOT, "%.3f", r.getSourceEndSec())).append('|')
              .append(r.getTechniqueIn()).append('|').append(r.getTechniqueOut()).append('|')
              .append(r.getTransitionSec()).append('|')
              .append(String.format(Locale.ROOT, "%.3f", r.getSpeed())).append('|')
              .append(String.format(Locale.ROOT, "%.3f", r.getVolume())).append('|')
              .append(r.isMuted()).append(';');
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String serialiseTimeline(List<TimelineClip> rows) {
        StringBuilder json = new StringBuilder("{\"timeline\":[");
        for (int i = 0; i < rows.size(); i++) {
            TimelineClip r = rows.get(i);
            if (i > 0) json.append(',');
            json.append(String.format(Locale.ROOT,
                    "{\"clipId\":\"%s\",\"sourceStart\":%.3f,\"sourceEnd\":%.3f,"
                    + "\"techniqueIn\":%s,\"techniqueOut\":%s,\"transitionSec\":%s,"
                    + "\"speed\":%.3f,\"volume\":%.3f,\"muted\":%s,\"locked\":%s}",
                    r.getClipId(), r.getSourceStartSec(), r.getSourceEndSec(),
                    quote(r.getTechniqueIn()), quote(r.getTechniqueOut()),
                    r.getTransitionSec() == null ? "null" : String.format(Locale.ROOT, "%.3f", r.getTransitionSec()),
                    r.getSpeed(), r.getVolume(), r.isMuted(), r.isLocked()));
        }
        return json.append("]}").toString();
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "") + "\"";
    }

    // ---- audio and captions ------------------------------------------------

    @Transactional
    public AudioTrack addAudio(UUID projectId, MultipartFile file, AudioTrackKind kind) {
        get(projectId);
        if (file.isEmpty()) {
            throw new IllegalArgumentException("That file is empty.");
        }
        if (file.getSize() > properties.getMaxUploadBytes()) {
            throw new IllegalArgumentException(
                    "That file is larger than the " + properties.getMaxUploadMb() + " MB limit.");
        }
        String displayName = sanitiseDisplayName(file.getOriginalFilename());
        String extension = extensionOf(displayName);
        if (!properties.allowedAudioExtensions().contains(extension)) {
            throw new IllegalArgumentException(
                    "Unsupported audio format '." + extension + "'. Supported: " +
                    String.join(", ", properties.allowedAudioExtensions()) + ".");
        }

        // One track per kind: replacing the music means replacing it, not
        // stacking a second bed underneath the first.
        audioTracks.findFirstByProjectIdAndKind(projectId, kind).ifPresent(existing -> {
            storage.deleteQuietly(storage.resolveWithin(storage.audioDir(projectId), existing.getStoredFilename()));
            audioTracks.delete(existing);
        });

        AudioTrack track = new AudioTrack();
        track.setProjectId(projectId);
        track.setKind(kind);
        track.setDisplayName(displayName);
        track.setStoredFilename("placeholder");
        track = audioTracks.save(track);

        track.setStoredFilename(storage.storedFilenameFor(track.getId(), extension));
        Path target = storage.resolveWithin(storage.audioDir(projectId), track.getStoredFilename());
        try (var in = file.getInputStream()) {
            storage.write(target, in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save " + displayName, e);
        }
        return audioTracks.save(track);
    }

    @Transactional(readOnly = true)
    public List<AudioTrack> audioTracks(UUID projectId) {
        return audioTracks.findByProjectId(projectId);
    }

    @Transactional
    public void removeAudio(UUID projectId, UUID trackId) {
        audioTracks.findById(trackId)
                .filter(t -> t.getProjectId().equals(projectId))
                .ifPresent(track -> {
                    storage.deleteQuietly(
                            storage.resolveWithin(storage.audioDir(projectId), track.getStoredFilename()));
                    audioTracks.delete(track);
                });
    }

    public record CaptionSettings(String stylePreset, Boolean burnIn) {}

    private static final Set<String> CAPTION_PRESETS =
            Set.of("CLEAN", "BOLD_REEL", "KIDS", "CINEMATIC", "MINIMAL", "STACKED", "LINE_REVEAL");

    @Transactional
    public CaptionTrack updateCaptions(UUID projectId, CaptionSettings settings) {
        get(projectId);
        CaptionTrack track = captionTracks.findByProjectId(projectId).orElseGet(() -> {
            CaptionTrack fresh = new CaptionTrack();
            fresh.setProjectId(projectId);
            return fresh;
        });
        if (settings.stylePreset() != null) {
            String preset = settings.stylePreset().toUpperCase(Locale.ROOT);
            if (!CAPTION_PRESETS.contains(preset)) {
                throw new IllegalArgumentException(
                        "Unknown caption preset '" + settings.stylePreset() + "'. Options: " +
                        String.join(", ", CAPTION_PRESETS) + ".");
            }
            track.setStylePreset(preset);
        }
        if (settings.burnIn() != null) {
            track.setBurnIn(settings.burnIn());
        }
        return captionTracks.save(track);
    }

    @Transactional(readOnly = true)
    public Optional<CaptionTrack> captions(UUID projectId) {
        return captionTracks.findByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public Optional<RenderJob> renderJob(UUID jobId) {
        return renderJobs.findById(jobId);
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Cleans a filename for display only.
     *
     * This is not a security control - the value never reaches the filesystem.
     * It exists so a filename cannot smuggle markup or newlines into the UI,
     * and so absurdly long names do not break the layout.
     */
    static String sanitiseDisplayName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "clip";
        }
        String cleaned = raw.replaceAll("[\\p{Cntrl}<>\"']", "").trim();
        // Strip any directory component a browser may have included.
        int slash = Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\'));
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        if (cleaned.isBlank()) {
            return "clip";
        }
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
