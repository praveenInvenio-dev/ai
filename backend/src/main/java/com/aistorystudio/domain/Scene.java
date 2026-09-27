package com.aistorystudio.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scene")
@Getter
@Setter
public class Scene {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "episode_id", nullable = false)
    private UUID episodeId;

    @Column(name = "scene_number", nullable = false)
    private int sceneNumber;

    @Column(columnDefinition = "TEXT")
    private String purpose;

    @Column(columnDefinition = "TEXT")
    private String narration;

    @Column(name = "characters_json", columnDefinition = "TEXT")
    private String charactersJson;

    @Column(columnDefinition = "TEXT")
    private String location;

    @Column(columnDefinition = "TEXT")
    private String action;

    @Column(columnDefinition = "TEXT")
    private String emotion;

    @Column(columnDefinition = "TEXT")
    private String camera;

    @Column(columnDefinition = "TEXT")
    private String lighting;

    @Column(name = "visual_style", columnDefinition = "TEXT")
    private String visualStyle;

    @Column(name = "continuity_json", columnDefinition = "TEXT")
    private String continuityJson;

    @Column(name = "image_prompt", columnDefinition = "TEXT")
    private String imagePrompt;

    @Column(name = "negative_prompt", columnDefinition = "TEXT")
    private String negativePrompt;

    @Column(name = "motion_prompt", columnDefinition = "TEXT")
    private String motionPrompt;

    @Column(name = "motion_negative_prompt", columnDefinition = "TEXT")
    private String motionNegativePrompt;

    /** Structured cinematic scene spec (Phase 2) - see SceneVisualSpec. Raw
     *  JSON here, not a mapped entity, so a partially-populated or malformed
     *  LLM response degrades to "field absent" rather than a save failure. */
    @Column(name = "visual_spec_json", columnDefinition = "TEXT")
    private String visualSpecJson;

    @Column(name = "voice_segments_json", columnDefinition = "TEXT")
    private String voiceSegmentsJson;

    @Column(name = "narration_seconds")
    private Double narrationSeconds;

    @Column(name = "image_duration_seconds")
    private Double imageDurationSeconds;

    @Column(name = "camera_movement")
    private String cameraMovement;

    @Column(name = "transition_in")
    private String transitionIn = "crossfade";

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    /** NORMAL, IMPORTANT, or HERO - how much animation/production budget this
     *  scene should get. Set by the story engine's own classification of the
     *  scene text (climax, emotional peak, opening hook, ending, magical
     *  moment - see StoryEngineService), not a separate model call. */
    @Column(nullable = false)
    private String importance = "NORMAL";

    /** A locked scene's image is never touched by bulk regeneration or a
     *  future "regenerate all" - only an explicit per-scene force-regenerate
     *  can replace it, and even that should be blocked in the service layer
     *  while locked (see EpisodeController). */
    @Column(nullable = false)
    private boolean locked = false;

    /** Same idea as {@link #locked}, independently, for the narration audio
     *  rather than the image - a user keeping a scene's picture but asking
     *  for the voice line redone (or vice versa) is a normal edit, not an
     *  all-or-nothing choice. */
    @Column(nullable = false)
    private boolean narrationLocked = false;

    /** AUTO (default - AnimationDecisionService picks), STATIC (no camera
     *  motion, just a still for the scene's duration), or TWO_POINT_FIVE_D
     *  (force the full camera+parallax treatment). See migration V11. */
    @Column(nullable = false)
    private String animationMode = "AUTO";

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}
