package com.aistorystudio.domain;

import com.aistorystudio.domain.enums.EpisodeStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "episode")
@Getter
@Setter
public class Episode {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "universe_id")
    private UUID universeId;

    @Column(name = "season_number")
    private Integer seasonNumber;

    @Column(name = "episode_number")
    private Integer episodeNumber;

    private String title;

    @Column(name = "user_prompt", nullable = false, columnDefinition = "TEXT")
    private String userPrompt;

    @Enumerated(EnumType.STRING)
    private EpisodeStatus status = EpisodeStatus.DRAFTING;

    @Column(name = "duration_target_sec")
    private Integer durationTargetSec;

    @Column(name = "target_age", columnDefinition = "TEXT")
    private String targetAge;

    @Column(columnDefinition = "TEXT")
    private String genre;
    @Column(columnDefinition = "TEXT")
    private String tone;

    @Column(name = "visual_style", columnDefinition = "TEXT")
    private String visualStyle;

    /** FAST | BALANCED | QUALITY (Phase 4). Per-episode, not global - see
     *  ProductionPipelineService.qualitySettings() for what each tier
     *  actually changes (steps, resolution). Defaults match the migration's
     *  DB default so an episode created before this field existed still
     *  reads as BALANCED, not null. */
    @Column(name = "quality_profile", nullable = false)
    private String qualityProfile = "BALANCED";

    @Column(columnDefinition = "TEXT")
    private String language = "English";

    /** One of the built-in procedural mood beds ("calm", "adventurous",
     *  "emotional") or null for no preset. A user-uploaded MUSIC asset, if
     *  one exists, always takes priority over this at render time. */
    private String musicPreset;

    /** Locks the episode's music selection - see migration V12. */
    private boolean musicLocked = false;

    @Column(name = "quality_score")
    private Integer qualityScore;

    /** Ollama model chosen for this episode (from whatever's installed locally); null = server default. */
    @Column(name = "ollama_model")
    private String ollamaModel;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}
