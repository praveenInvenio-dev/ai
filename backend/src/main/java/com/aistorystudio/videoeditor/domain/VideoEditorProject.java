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

    /** Colour look: null/"auto" = the template's own grade, "none", or an id from TimelineRenderer.LOOKS. */
    @Column(name = "look", length = 32)
    private String look;

    /** Title shown over the first seconds of the final render (null/blank = no title). */
    @Column(name = "title_text", length = 160)
    private String titleText;

    @Column(name = "stabilize", nullable = false)
    private boolean stabilize = false;

    /** Film effects, comma separated ids from TimelineRenderer.EFFECTS (null/blank = none). */
    @Column(name = "effects", length = 160)
    private String effects;

    /** Strength of the colour look, 0 = off .. 1 = full (Cardboard: LUT intensity). */
    @Column(name = "look_intensity", nullable = false)
    private double lookIntensity = 1.0;

    /** Timed overlays on the finished cut: [{"type":"text"|"zoom"|"sfx", "start":s, "end":s, ...}] (see TimelineFinisher). */
    @Column(name = "overlays_json", columnDefinition = "TEXT")
    private String overlaysJson;

    /** Final export: mp4 | webm | mov | mkv (null = mp4), height 480..2160 (null = default), fps 24/25/30/50/60 (null = keep). */
    @Column(name = "export_format", length = 8)
    private String exportFormat;
    @Column(name = "export_height")
    private Integer exportHeight;
    @Column(name = "export_fps")
    private Integer exportFps;

    /** Loudness target for the final render: social (-14 LUFS), podcast (-16), broadcast (-23); null = leave the audio as it is. */
    @Column(name = "loudness", length = 12)
    private String loudness;

    /** A dub: the voiceover replaces the original voice (the original is lowered to about 16 %) instead of only being ducked under it. */
    @Column(name = "voiceover_replace", nullable = false)
    private boolean voiceoverReplace = false;

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
