package com.aistorystudio.videoeditor.editing;

import com.aistorystudio.videoeditor.domain.TimelineClip;
import com.aistorystudio.videoeditor.domain.VideoClip;
import com.aistorystudio.videoeditor.domain.VideoScene;
import com.aistorystudio.videoeditor.domain.enums.EditIntensity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * The deterministic edit planner.
 *
 * <h2>Why the rule engine, not the LLM, is the primary planner</h2>
 *
 * A local 8B model returning malformed JSON is a routine event, not an
 * exception. If the LLM were the only planner, every such event would mean no
 * timeline at all. So the rule engine always runs and always produces a
 * complete, renderable plan; the LLM's job (see EditPlannerService) is to
 * reorder and re-annotate within those candidates, and its failure degrades the
 * result rather than breaking it.
 *
 * The rules encode the parts of editing that are genuinely mechanical:
 *
 * <ul>
 *   <li>prefer shots that scored well - a soft or blown-out shot cannot be
 *       rescued downstream, so it should not be chosen in the first place;</li>
 *   <li>trim every shot into the template's shot-length window, because "one
 *       clip = one shot" produces a slideshow of whatever lengths the camera
 *       happened to record;</li>
 *   <li>pick a transition with a reason - matching motion suggests cut on
 *       action, a change of clip suggests a dissolve, and everything else is a
 *       hard cut, which is what professional editing mostly is.</li>
 * </ul>
 *
 * Transitions are never chosen at random, and never chosen because they look
 * interesting. Every one carries a {@code reason} string that surfaces in the
 * "Why this edit?" panel, which also makes wrong choices auditable.
 */
@Component
public class EditRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(EditRuleEngine.class);

    /** Above this motion score a shot counts as moving, for cut-on-action. */
    private static final double MOTION_THRESHOLD = 0.12;

    /** Below this quality score a shot is skipped when alternatives exist. */
    private static final double POOR_QUALITY = 0.35;

    /** Shots with audio above this are candidates for J/L cuts. */
    private static final double AUDIBLE = 0.15;

    private final TechniqueLibrary library;

    public EditRuleEngine(TechniqueLibrary library) {
        this.library = library;
    }

    /** One analysed shot, with the clip it came from. */
    public record Candidate(VideoClip clip, VideoScene scene) {
        double duration() { return scene.getEndSec() - scene.getStartSec(); }
        double quality() { return scene.getQualityScore() == null ? 0.5 : scene.getQualityScore(); }
        double motion()  { return scene.getMotionScore() == null ? 0.0 : scene.getMotionScore(); }
        double audio()   { return scene.getAudioRms() == null ? 0.0 : scene.getAudioRms(); }
    }

    public record Plan(List<TimelineClip> timeline, String rationale) {}

    public Plan plan(UUID projectId,
                     List<Candidate> candidates,
                     TechniqueLibrary.Template template,
                     EditIntensity intensity,
                     Integer targetDurationSec) {

        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No analysed shots to work with. Run analysis before planning.");
        }

        List<Candidate> usable = selectUsable(candidates, template);
        List<Candidate> chosen = fitToDuration(usable, template, targetDurationSec);

        List<TimelineClip> timeline = new ArrayList<>(chosen.size());
        StringBuilder rationale = new StringBuilder();

        for (int i = 0; i < chosen.size(); i++) {
            Candidate current = chosen.get(i);
            Candidate next = i + 1 < chosen.size() ? chosen.get(i + 1) : null;

            double[] trim = trimToWindow(current, template);

            TimelineClip row = new TimelineClip();
            row.setProjectId(projectId);
            row.setClipId(current.clip().getId());
            row.setSortOrder(i);
            row.setSourceStartSec(trim[0]);
            row.setSourceEndSec(trim[1]);

            // The first shot fades up from black rather than starting abruptly.
            row.setTechniqueIn(i == 0 ? pick("dip_to_black", template, intensity) : null);

            Decision decision = chooseOutgoing(current, next, template, intensity);
            row.setTechniqueOut(decision.techniqueId());
            row.setTransitionSec(decision.durationSeconds());
            row.setReason(decision.reason());
            row.setSpeed(1.0);
            row.setVolume(1.0);
            row.setMuted(current.audio() < AUDIBLE);

            timeline.add(row);

            if (decision.techniqueId() != null && next != null) {
                rationale.append("Shot ").append(i + 1).append(" to ").append(i + 2)
                         .append(": ").append(decision.techniqueName())
                         .append(" - ").append(decision.reason()).append('\n');
            }
        }

        log.info("Rule engine planned {} shot(s) from {} candidate(s) [{}]",
                timeline.size(), candidates.size(), template.id());
        return new Plan(timeline, rationale.toString().trim());
    }

    // ---- selection ---------------------------------------------------------

    /**
     * Drops unusable shots, but only while enough remain.
     *
     * The guard matters: on a badly-shot clip every shot may score below the
     * threshold, and returning nothing would be worse than returning the best
     * of a bad set. A short blurry video is still the video the user asked for.
     */
    private List<Candidate> selectUsable(List<Candidate> all, TechniqueLibrary.Template template) {
        List<Candidate> good = all.stream()
                .filter(c -> c.quality() >= POOR_QUALITY)
                .filter(c -> c.duration() >= Math.min(template.minShotSeconds(), 0.5))
                .toList();

        if (good.size() >= Math.max(2, all.size() / 3)) {
            return good;
        }
        log.info("Only {}/{} shots cleared the quality bar; keeping all rather than "
                 + "returning an almost-empty timeline", good.size(), all.size());
        return all;
    }

    /**
     * Chooses which shots make the target duration.
     *
     * Trimming shots rather than speeding everything up is the brief's explicit
     * requirement, and it is also the only approach that preserves the footage's
     * own rhythm. Selection is by quality but keeps the ORIGINAL ORDER, because
     * reordering by score would scramble whatever story the clips tell.
     */
    private List<Candidate> fitToDuration(List<Candidate> usable,
                                           TechniqueLibrary.Template template,
                                           Integer targetDurationSec) {
        if (targetDurationSec == null || targetDurationSec <= 0) {
            return usable;   // "Auto" - use everything worth using
        }

        double perShot = Math.min(template.maxShotSeconds(),
                Math.max(template.minShotSeconds(),
                         targetDurationSec / (double) Math.max(usable.size(), 1)));
        int wanted = (int) Math.max(1, Math.round(targetDurationSec / perShot));

        if (wanted >= usable.size()) {
            return usable;
        }

        Set<Candidate> keep = new HashSet<>(usable.stream()
                .sorted(Comparator.comparingDouble(Candidate::quality).reversed())
                .limit(wanted)
                .toList());

        return usable.stream().filter(keep::contains).toList();
    }

    /** Trims a shot into the template's window, taking from the middle where
     *  the action usually is rather than from a possibly-unstable start. */
    private double[] trimToWindow(Candidate c, TechniqueLibrary.Template template) {
        double start = c.scene().getStartSec();
        double end = c.scene().getEndSec();
        double duration = end - start;

        if (duration <= template.maxShotSeconds()) {
            return new double[]{start, end};
        }
        double keep = template.maxShotSeconds();
        double centre = start + duration / 2.0;
        return new double[]{Math.max(start, centre - keep / 2.0),
                            Math.min(end, centre + keep / 2.0)};
    }

    // ---- transition choice -------------------------------------------------

    private record Decision(String techniqueId, String techniqueName,
                            Double durationSeconds, String reason) {}

    /**
     * Picks the outgoing transition. Ordered by specificity: the most
     * justified choice wins, and a hard cut is the honest default rather than
     * a fallback nobody meant.
     */
    private Decision chooseOutgoing(Candidate current, Candidate next,
                                     TechniqueLibrary.Template template,
                                     EditIntensity intensity) {
        if (next == null) {
            String id = pick("dip_to_black", template, intensity);
            return new Decision(id, "dip to black", durationOf(id, null),
                    "Closes the video on a deliberate beat rather than stopping mid-frame.");
        }

        boolean bothMoving = current.motion() > MOTION_THRESHOLD && next.motion() > MOTION_THRESHOLD;
        boolean sameClip = current.clip().getId().equals(next.clip().getId());
        boolean nextAudible = next.audio() > AUDIBLE;
        boolean currentAudible = current.audio() > AUDIBLE;

        // Both shots moving: the movement carries across the join and hides it.
        if (bothMoving) {
            String whip = pick("whip_pan", template, intensity);
            if (whip != null && intensity == EditIntensity.AGGRESSIVE) {
                return new Decision(whip, "whip pan", durationOf(whip, null),
                        "Both shots carry strong motion, so a fast wipe matches their energy.");
            }
            String action = pick("cut_on_action", template, intensity);
            if (action != null) {
                return new Decision(action, "cut on action", null,
                        "Both shots contain movement, so cutting mid-motion carries the eye across.");
            }
        }

        // Speech starting in the next shot: lead its audio in under this picture.
        if (nextAudible && !currentAudible) {
            String j = pick("j_cut", template, intensity);
            if (j != null) {
                return new Decision(j, "J-cut", null,
                        "Audio begins in the next shot, so it starts early to pull the viewer forward.");
            }
        }

        // Speech ending here: carry it over the next picture.
        if (currentAudible && !nextAudible) {
            String l = pick("l_cut", template, intensity);
            if (l != null) {
                return new Decision(l, "L-cut", null,
                        "This shot's audio continues over the next picture to hold continuity.");
            }
        }

        // A different clip usually means a different place or time.
        if (!sameClip) {
            String dissolve = pick("cross_dissolve", template, intensity);
            if (dissolve != null) {
                return new Decision(dissolve, "cross dissolve", durationOf(dissolve, null),
                        "The next shot comes from a different clip, so a short dissolve marks the change of place.");
            }
        }

        String cut = pick("hard_cut", template, intensity);
        return new Decision(cut, "hard cut", null,
                "Nothing here calls for a transition, so a straight cut keeps it invisible.");
    }

    /**
     * Resolves a technique, honouring the template's avoid-list and the
     * intensity ceiling.
     *
     * This is where KIDS_STORY's exclusion of whip pans and punch zooms actually
     * bites: the rule above may prefer a whip pan, and this returns null for it,
     * so the caller falls through to the next-best justified choice.
     */
    private String pick(String id, TechniqueLibrary.Template template, EditIntensity intensity) {
        if (template.avoidTechniques().contains(id)) {
            return null;
        }
        var technique = library.find(id).orElse(null);
        if (technique == null) {
            return null;
        }
        if (!intensityAllows(technique.intensity(), intensity)) {
            return null;
        }
        return id;
    }

    /** A technique may be used when its own intensity is at or below the
     *  project's. SUBTLE therefore never gets a punch zoom, whatever the rules
     *  would otherwise prefer. */
    private boolean intensityAllows(String techniqueIntensity, EditIntensity projectIntensity) {
        int techniqueRank = switch (techniqueIntensity == null ? "subtle" : techniqueIntensity) {
            case "aggressive" -> 3;
            case "dynamic" -> 2;
            case "balanced" -> 1;
            default -> 0;
        };
        int projectRank = switch (projectIntensity) {
            case AGGRESSIVE -> 3;
            case DYNAMIC -> 2;
            case BALANCED -> 1;
            case SUBTLE -> 0;
        };
        return techniqueRank <= projectRank;
    }

    private Double durationOf(String techniqueId, Double requested) {
        if (techniqueId == null) {
            return null;
        }
        return library.find(techniqueId)
                .map(t -> t.param("durationSeconds", requested))
                .filter(d -> d > 0)
                .orElse(null);
    }
}
