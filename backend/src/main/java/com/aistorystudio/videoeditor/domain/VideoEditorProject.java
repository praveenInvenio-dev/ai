package com.aistorystudio.videoeditor.domain;

import com.aistorystudio.videoeditor.domain.enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "video_editor_project")
@Getter
@Setter
public class VideoEditorProject {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VideoCategory category = VideoCategory.REEL;

    @Enumerated(EnumType.STRING)
    @Column(name = "editing_style", nullable = false)
    private EditingStyle editingStyle = EditingStyle.CINEMATIC;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EditIntensity intensity = EditIntensity.BALANCED;

    @Enumerated(EnumType.STRING)
    @Column(name = "aspect_ratio", nullable = false)
    private AspectRatio aspectRatio = AspectRatio.VERTICAL_9_16;

    /**
     * Null means "Auto". Kept nullable rather than using 0 as a sentinel so the
     * planner can tell "the user didn't care" from "the user asked for zero",
     * and so a bad request cannot silently become an auto-length edit.
     */
    @Column(name = "target_duration_sec")
    private Integer targetDurationSec;

    /** Free-text style instruction for CUSTOM, passed to the planner verbatim. */
    @Column(name = "custom_instructions", columnDefinition = "TEXT")
    private String customInstructions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VideoEditorState state = VideoEditorState.DRAFT;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "smart_cuts", nullable = false)
    private boolean smartCuts = true;

    @Column(name = "beat_sync", nullable = false)
    private boolean beatSync = true;

    @Column(name = "smart_transitions", nullable = false)
    private boolean smartTransitions = true;

    /** Off by default: captions need Whisper, which is the slowest stage on CPU. */
    @Column(name = "auto_captions", nullable = false)
    private boolean autoCaptions = false;

    @Column(name = "audio_enhancement", nullable = false)
    private boolean audioEnhancement = true;

    @Column(name = "smart_reframing", nullable = false)
    private boolean smartReframing = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
