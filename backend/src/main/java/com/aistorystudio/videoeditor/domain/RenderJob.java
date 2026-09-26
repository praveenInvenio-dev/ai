package com.aistorystudio.videoeditor.domain;

import com.aistorystudio.videoeditor.domain.enums.RenderKind;
import com.aistorystudio.videoeditor.domain.enums.VideoEditorState;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An asynchronous render.
 *
 * Progress is a real percentage parsed from FFmpeg's own output, not a timer.
 * {@code stage} carries the current step name so the UI can show which phase is
 * running rather than a single undifferentiated bar.
 */
@Entity
@Table(name = "render_job")
@Getter
@Setter
public class RenderJob {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "plan_id")
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RenderKind kind = RenderKind.PREVIEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VideoEditorState status = VideoEditorState.RENDERING;

    @Column(name = "progress_percent", nullable = false)
    private int progressPercent = 0;

    @Column(length = 64)
    private String stage;

    @Column(name = "output_filename", columnDefinition = "TEXT")
    private String outputFilename;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Post-render validation result (§33 of the brief), stored as JSON. */
    @Column(name = "quality_report_json", columnDefinition = "TEXT")
    private String qualityReportJson;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
