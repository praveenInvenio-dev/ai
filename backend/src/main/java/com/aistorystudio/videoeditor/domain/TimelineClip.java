package com.aistorystudio.videoeditor.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One segment on the editable timeline.
 *
 * {@code techniqueIn} and {@code techniqueOut} hold technique IDs from
 * TechniqueLibrary ("j_cut", "cross_dissolve") and never FFmpeg fragments. An
 * ID absent from the library fails validation before rendering, which is what
 * keeps LLM output from reaching the encoder as anything but an enum lookup.
 */
@Entity
@Table(name = "timeline_clip")
@Getter
@Setter
public class TimelineClip {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "clip_id", nullable = false)
    private UUID clipId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "source_start_sec", nullable = false)
    private double sourceStartSec;

    @Column(name = "source_end_sec", nullable = false)
    private double sourceEndSec;

    @Column(name = "technique_in", length = 64)
    private String techniqueIn;

    @Column(name = "technique_out", length = 64)
    private String techniqueOut;

    @Column(name = "transition_sec")
    private Double transitionSec;

    @Column(nullable = false)
    private double speed = 1.0;

    @Column(nullable = false)
    private double volume = 1.0;

    @Column(nullable = false)
    private boolean muted = false;

    /** A locked shot is preserved as-is across AI Edit regeneration - the
     *  planner replans everything else around it instead of discarding it. */
    @Column(nullable = false)
    private boolean locked = false;

    /** Why the planner chose this cut, shown in the "Why this edit?" panel. */
    @Column(columnDefinition = "TEXT")
    private String reason;

    public double outputDurationSec() {
        double raw = sourceEndSec - sourceStartSec;
        return speed > 0 ? raw / speed : raw;
    }
}
