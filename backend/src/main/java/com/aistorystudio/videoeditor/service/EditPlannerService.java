package com.aistorystudio.videoeditor.service;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.videoeditor.domain.*;
import com.aistorystudio.videoeditor.domain.enums.VideoEditorState;
import com.aistorystudio.videoeditor.editing.EditRuleEngine;
import com.aistorystudio.videoeditor.editing.TechniqueLibrary;
import com.aistorystudio.videoeditor.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Phase 6: turns analysed shots into a timeline.
 *
 * <h2>Order of authority</h2>
 *
 * <ol>
 *   <li>The rule engine plans. It always produces a complete, renderable
 *       timeline from the measurements.</li>
 *   <li>The LLM may then REORDER those rows and rewrite their reasons. It cannot
 *       introduce a shot, invent a timecode, or name a technique that does not
 *       exist - anything it returns is validated against the rule engine's own
 *       output before use.</li>
 *   <li>If the LLM is unreachable, slow, or returns unparseable JSON - all
 *       routine for a local 8B model - the rule engine's plan is used unchanged
 *       and the plan records PLANNER=RULE_ENGINE so the difference is visible
 *       rather than mysterious.</li>
 * </ol>
 *
 * This is what makes "never allow arbitrary FFmpeg from the LLM" structural:
 * the model's entire vocabulary is a permutation of row indices plus free text
 * that only ever lands in a {@code reason} field.
 */
@Service
public class EditPlannerService {

    private static final Logger log = LoggerFactory.getLogger(EditPlannerService.class);

    /** A local model that has not answered by now is not going to help. */
    private static final int LLM_MAX_ORDER_ENTRIES = 60;

    private final VideoClipRepository clips;
    private final VideoSceneRepository scenes;
    private final TimelineClipRepository timeline;
    private final EditingPlanRepository plans;
    private final EditRuleEngine ruleEngine;
    private final TechniqueLibrary library;
    private final ProviderGateway gateway;
    private final VideoEditorProjectService projectService;
    private final ProgressReporter progress;
    private final BeatSyncService beatSync;
    private final ObjectMapper mapper = new ObjectMapper();

    public EditPlannerService(VideoClipRepository clips,
                              VideoSceneRepository scenes,
                              TimelineClipRepository timeline,
                              EditingPlanRepository plans,
                              EditRuleEngine ruleEngine,
                              TechniqueLibrary library,
                              ProviderGateway gateway,
                              VideoEditorProjectService projectService,
                              ProgressReporter progress,
                              BeatSyncService beatSync) {
        this.clips = clips;
        this.scenes = scenes;
        this.timeline = timeline;
        this.plans = plans;
        this.ruleEngine = ruleEngine;
        this.library = library;
        this.gateway = gateway;
        this.projectService = projectService;
        this.progress = progress;
        this.beatSync = beatSync;
    }

    @Transactional
    public EditingPlan plan(UUID projectId, UUID jobId, boolean useLlm) {
        VideoEditorProject project = projectService.get(projectId);
        progress.report(jobId, "Planning", 10, "Collecting analysed shots");

        TechniqueLibrary.Template template = library.templateFor(project.getCategory().name());

        // Locked rows are captured BEFORE anything else runs: they survive
        // this regeneration untouched, and the candidates feeding the rule
        // engine exclude their footage so the same moment isn't picked twice.
        List<TimelineClip> lockedRows = timeline.findByProjectIdOrderBySortOrderAsc(projectId)
                .stream().filter(TimelineClip::isLocked).toList();

        List<EditRuleEngine.Candidate> candidates = collectCandidates(projectId, template);
        candidates = excludeLockedRanges(candidates, lockedRows);
        if (candidates.isEmpty() && lockedRows.isEmpty()) {
            throw new IllegalStateException(
                    "No analysed shots found. Run analysis before creating an edit.");
        }

        List<TimelineClip> rows;
        String rationale;
        String planner;
        if (candidates.isEmpty()) {
            // Every remaining shot is locked; nothing left for the rule engine
            // to plan. That's a valid, if unusual, outcome - not an error.
            rows = new ArrayList<>();
            rationale = "Every shot on the timeline is locked, so nothing was replanned.";
            planner = EditingPlan.PLANNER_RULE_ENGINE;
        } else {
            progress.report(jobId, "Planning", 40, "Applying editing rules");
            EditRuleEngine.Plan plan = ruleEngine.plan(projectId, candidates, template,
                    project.getIntensity(), project.getTargetDurationSec());

            planner = EditingPlan.PLANNER_RULE_ENGINE;
            rows = plan.timeline();
            rationale = plan.rationale();

            if (useLlm) {
                progress.report(jobId, "Planning", 65, "Asking the story director for an order");
                Optional<List<TimelineClip>> reordered = refineWithLlm(project, rows, template);
                if (reordered.isPresent()) {
                    rows = reordered.get();
                    planner = EditingPlan.PLANNER_RULE_ENGINE_LLM;
                    rationale = "Shot order chosen by the story director; transitions by the editing rules.\n"
                            + rationale;
                }
            }
        }

        if (!lockedRows.isEmpty()) {
            rows = mergeLockedRows(lockedRows, rows);
            rationale = lockedRows.size() + " locked shot(s) kept in place.\n" + rationale;
        }
        if (project.isBeatSync() && !rows.isEmpty()) {
            progress.report(jobId, "Planning", 80, "Finding the beat of the music");
            String note = beatSync.apply(projectId, rows);
            if (note != null && !note.isBlank()) rationale = note + "\n" + rationale;
        }
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setSortOrder(i);
        }

        progress.report(jobId, "Planning", 90, "Saving timeline");
        timeline.deleteByProjectId(projectId);
        List<TimelineClip> saved = timeline.saveAll(rows);

        EditingPlan record = new EditingPlan();
        record.setProjectId(projectId);
        record.setEdlJson(serialise(saved));
        record.setPlanner(planner);
        record.setRationale(rationale);
        record.setPlanHash(VideoEditorProjectService.hashTimeline(saved));
        EditingPlan stored = plans.save(record);

        projectService.transition(projectId, VideoEditorState.PLAN_READY);
        // 95, not 100: this fires (REQUIRES_NEW, commits immediately) before
        // this method's own @Transactional save of the timeline rows has
        // committed. The frontend treats percent>=100 as "job done" and
        // immediately re-fetches the timeline - at 100 here that read can
        // land before the save is visible and come back empty. The real
        // 100/"Complete" signal comes from the controller's progress.finish()
        // call, which only runs after this method returns and commits.
        progress.report(jobId, "Planning", 95, saved.size() + " shot(s) on the timeline");
        return stored;
    }

    // ---- locked shots --------------------------------------------------------

    /** Drops any fresh candidate whose footage overlaps a locked row's own
     *  [clipId, start, end] range, so regeneration doesn't select the same
     *  moment a locked shot already covers. */
    private List<EditRuleEngine.Candidate> excludeLockedRanges(List<EditRuleEngine.Candidate> candidates,
                                                                List<TimelineClip> lockedRows) {
        if (lockedRows.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .filter(c -> lockedRows.stream().noneMatch(locked ->
                        locked.getClipId().equals(c.clip().getId())
                                && locked.getSourceStartSec() < c.scene().getEndSec()
                                && locked.getSourceEndSec() > c.scene().getStartSec()))
                .toList();
    }

    /**
     * Re-inserts locked rows back into a freshly planned sequence at their
     * previous position (clamped to the new, possibly different, length).
     *
     * Clones each locked row into a new, unsaved TimelineClip rather than
     * reusing the live JPA entity: {@code timeline.deleteByProjectId} below
     * removes every existing row for this project - including these locked
     * ones, since they still belong to the project until this method
     * returns - and re-saving an entity Hibernate has already marked removed
     * within the same persistence context fails. A plain copy has no
     * persistence history to collide with.
     *
     * This is a position-based approximation, not a full replan around fixed
     * points: transitions at the two shots now adjacent to an inserted locked
     * row keep whichever technique they already had rather than being
     * re-reasoned about their new neighbour. That's a real, visible
     * limitation - the join is still a valid technique, just not necessarily
     * the rule engine's best choice for the new pairing - and is far better
     * than the alternative of a lock that regeneration silently ignores.
     */
    private List<TimelineClip> mergeLockedRows(List<TimelineClip> lockedRows, List<TimelineClip> freshRows) {
        List<TimelineClip> merged = new ArrayList<>(freshRows);
        List<TimelineClip> byOriginalOrder = lockedRows.stream()
                .sorted(Comparator.comparingInt(TimelineClip::getSortOrder))
                .toList();
        for (TimelineClip locked : byOriginalOrder) {
            int pos = Math.max(0, Math.min(locked.getSortOrder(), merged.size()));
            merged.add(pos, clone(locked));
        }
        return merged;
    }

    private TimelineClip clone(TimelineClip source) {
        TimelineClip copy = new TimelineClip();
        copy.setProjectId(source.getProjectId());
        copy.setClipId(source.getClipId());
        copy.setSourceStartSec(source.getSourceStartSec());
        copy.setSourceEndSec(source.getSourceEndSec());
        copy.setTechniqueIn(source.getTechniqueIn());
        copy.setTechniqueOut(source.getTechniqueOut());
        copy.setTransitionSec(source.getTransitionSec());
        copy.setSpeed(source.getSpeed());
        copy.setVolume(source.getVolume());
        copy.setMuted(source.isMuted());
        copy.setLocked(true);
        copy.setReason(source.getReason());
        return copy;
    }

    // ---- candidates --------------------------------------------------------

    /** A scene longer than this multiple of the template's max shot length is
     *  treated as continuous footage with no internal cuts (typical of a
     *  single handheld take) and gets split into several sampled sub-shots
     *  rather than rendered as one shot cropped down to maxShotSeconds. */
    private static final double SPLIT_THRESHOLD_MULTIPLIER = 1.5;
    private static final int MIN_SUB_SHOTS = 3;
    private static final int MAX_SUB_SHOTS = 8;

    private List<EditRuleEngine.Candidate> collectCandidates(UUID projectId, TechniqueLibrary.Template template) {
        List<EditRuleEngine.Candidate> out = new ArrayList<>();
        for (VideoClip clip : clips.findByProjectIdOrderBySortOrderAsc(projectId)) {
            if (!clip.isAnalyzed()) {
                continue;
            }
            for (VideoScene scene : scenes.findByClipIdOrderBySceneIndexAsc(clip.getId())) {
                if (scene.durationSec() > template.maxShotSeconds() * SPLIT_THRESHOLD_MULTIPLIER) {
                    out.addAll(sampleSubShots(clip, scene, template));
                } else {
                    out.add(new EditRuleEngine.Candidate(clip, scene));
                }
            }
        }
        return out;
    }

    /**
     * Turns one long, cut-free scene into several shorter sampled shots
     * spread across its full length, instead of the rule engine cropping it
     * down to a single maxShotSeconds-long window in the middle.
     *
     * Without this, a 60-second continuous take that PySceneDetect reports as
     * one scene (nothing in the shot changed enough to register a cut) always
     * rendered as just the ~3-8 seconds nearest its centre - the rest of the
     * footage was silently discarded regardless of how long the source was.
     * Sampling several shots spread across the scene is what actually makes
     * "AI edit" mean something for a single continuous clip: it picks
     * moments across the whole take rather than one arbitrary slice.
     *
     * Quality/motion/audio scores are copied from the parent scene since we
     * only have per-scene, not per-frame, measurements - an approximation,
     * but a far better one than discarding the footage outright.
     */
    private List<EditRuleEngine.Candidate> sampleSubShots(VideoClip clip, VideoScene scene,
                                                           TechniqueLibrary.Template template) {
        double total = scene.durationSec();
        double shotLen = Math.min(template.maxShotSeconds(), total / MIN_SUB_SHOTS);
        int subShots = (int) Math.max(MIN_SUB_SHOTS,
                Math.min(MAX_SUB_SHOTS, Math.round(total / (template.maxShotSeconds() * 2))));

        List<EditRuleEngine.Candidate> out = new ArrayList<>(subShots);
        double usable = total - shotLen;
        for (int i = 0; i < subShots; i++) {
            // Evenly spaced start points across the scene, including both ends,
            // so a highlight reel samples the beginning, middle and end of the
            // take rather than clustering around the centre.
            double start = scene.getStartSec() + (subShots == 1 ? 0
                    : usable * i / (double) (subShots - 1));
            VideoScene sub = new VideoScene();
            sub.setClipId(scene.getClipId());
            sub.setSceneIndex(scene.getSceneIndex() * 100 + i);
            sub.setStartSec(start);
            sub.setEndSec(Math.min(scene.getEndSec(), start + shotLen));
            sub.setMotionScore(scene.getMotionScore());
            sub.setBrightness(scene.getBrightness());
            sub.setBlurScore(scene.getBlurScore());
            sub.setAudioRms(scene.getAudioRms());
            sub.setQualityScore(scene.getQualityScore());
            sub.setShotType(scene.getShotType());
            sub.setHasSpeech(scene.isHasSpeech());
            out.add(new EditRuleEngine.Candidate(clip, sub));
        }
        log.info("Scene {} on clip {} is {}s with no internal cuts; sampled {} sub-shot(s) across it",
                scene.getSceneIndex(), clip.getId(), String.format(Locale.ROOT, "%.1f", total), subShots);
        return out;
    }

    // ---- LLM refinement ----------------------------------------------------

    /**
     * Asks the model for a shot ORDER, nothing else.
     *
     * The response contract is a JSON array of indices into the rule engine's
     * own rows. That is deliberately the smallest useful surface: the model
     * cannot express a timecode, a technique, a filter or a file, so there is
     * nothing to sanitise beyond checking the indices are a permutation.
     *
     * Returns empty on any problem, and the caller keeps the rule engine's plan.
     */
    private Optional<List<TimelineClip>> refineWithLlm(VideoEditorProject project,
                                                        List<TimelineClip> rows,
                                                        TechniqueLibrary.Template template) {
        if (rows.size() < 2 || rows.size() > LLM_MAX_ORDER_ENTRIES) {
            return Optional.empty();
        }
        try {
            // generateStructured, not generate: it constrains the model to emit
            // JSON, which is the difference between "usually parseable" and
            // "usually parseable" plus a much lower rate of prose preamble.
            String response = gateway.llm().generateStructured(
                    "You are a video editor. Reply with JSON only. No prose, no code fences.",
                    buildPrompt(project, rows, template));
            List<Integer> order = parseOrder(response, rows.size());
            if (order.isEmpty()) {
                log.info("Story director returned no usable order; keeping the rule engine's plan");
                return Optional.empty();
            }

            List<TimelineClip> reordered = new ArrayList<>(rows.size());
            for (int i = 0; i < order.size(); i++) {
                TimelineClip row = rows.get(order.get(i));
                row.setSortOrder(i);
                reordered.add(row);
            }
            return Optional.of(reordered);
        } catch (Exception e) {
            // Never fail a plan because the optional refinement failed.
            log.info("Story director unavailable ({}); keeping the rule engine's plan", e.getMessage());
            return Optional.empty();
        }
    }

    private String buildPrompt(VideoEditorProject project, List<TimelineClip> rows,
                               TechniqueLibrary.Template template) {
        StringBuilder shots = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            TimelineClip r = rows.get(i);
            shots.append(i).append(": ")
                 .append(String.format(Locale.ROOT, "%.1fs", r.outputDurationSec()))
                 .append(r.isMuted() ? ", silent" : ", has audio")
                 .append('\n');
        }

        return """
            You are ordering shots for a %s video edited in a %s style.
            %s

            Shots available (index: length, audio):
            %s
            %s
            Reply with ONLY a JSON array of shot indices in the order they should
            appear, using every index exactly once. No prose, no code fences.
            Example: [0,3,1,2]
            """.formatted(
                project.getCategory().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                project.getEditingStyle().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                template.notes(),
                shots,
                project.getCustomInstructions() == null || project.getCustomInstructions().isBlank()
                        ? "" : "Extra direction: " + project.getCustomInstructions() + "\n");
    }

    /**
     * Extracts a permutation from the model's reply.
     *
     * Rejects anything that is not exactly a permutation of 0..n-1. A partial
     * or repeated list would silently drop or duplicate shots, which looks like
     * the editor losing footage rather than the model misbehaving.
     */
    List<Integer> parseOrder(String response, int expectedSize) {
        if (response == null) {
            return List.of();
        }
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            JsonNode array = mapper.readTree(response.substring(start, end + 1));
            if (!array.isArray() || array.size() != expectedSize) {
                return List.of();
            }
            List<Integer> order = new ArrayList<>(expectedSize);
            Set<Integer> seen = new HashSet<>();
            for (JsonNode n : array) {
                if (!n.isInt()) {
                    return List.of();
                }
                int v = n.asInt();
                if (v < 0 || v >= expectedSize || !seen.add(v)) {
                    return List.of();
                }
                order.add(v);
            }
            return order;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---- serialisation -----------------------------------------------------

    private String serialise(List<TimelineClip> rows) {
        try {
            var array = mapper.createArrayNode();
            for (TimelineClip r : rows) {
                var node = array.addObject();
                node.put("clipId", r.getClipId().toString());
                node.put("sourceStart", r.getSourceStartSec());
                node.put("sourceEnd", r.getSourceEndSec());
                node.put("techniqueIn", r.getTechniqueIn());
                node.put("techniqueOut", r.getTechniqueOut());
                node.put("speed", r.getSpeed());
                node.put("muted", r.isMuted());
                node.put("locked", r.isLocked());
            }
            var root = mapper.createObjectNode();
            root.set("timeline", array);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the timeline", e);
        }
    }
}
