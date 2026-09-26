package com.aistorystudio.videoeditor.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A validated edit decision list.
 *
 * {@code planner} records whether the LLM contributed. A local 8B model
 * returning unparseable JSON is a routine event rather than an exception, and
 * the rule engine alone still produces a usable timeline - so when a user asks
 * why a plan looks plain, this column answers it instead of guesswork.
 */
@Entity
@Table(name = "editing_plan")
@Getter
@Setter
public class EditingPlan {

    /** Values for {@link #planner}. */
    public static final String PLANNER_RULE_ENGINE = "RULE_ENGINE";
    public static final String PLANNER_RULE_ENGINE_LLM = "RULE_ENGINE_LLM";
    public static final String PLANNER_MANUAL = "MANUAL";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "edl_json", nullable = false, columnDefinition = "TEXT")
    private String edlJson;

    @Column(nullable = false, length = 32)
    private String planner = PLANNER_RULE_ENGINE;

    /** Human-readable "why this edit?" text surfaced in the UI. */
    @Column(columnDefinition = "TEXT")
    private String rationale;

    /**
     * Hash of the timeline content. Previews are written to a file named by this
     * hash, so an undo/redo round trip lands back on an existing render instead
     * of re-encoding - which is the common case while a user is adjusting cuts.
     */
    @Column(name = "plan_hash", nullable = false, length = 64)
    private String planHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
