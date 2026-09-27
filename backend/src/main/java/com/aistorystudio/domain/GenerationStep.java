package com.aistorystudio.domain;

import com.aistorystudio.domain.enums.StepStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "generation_step")
@Getter
@Setter
public class GenerationStep {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "scene_id")
    private UUID sceneId;

    @Column(name = "step_name", nullable = false)
    private String stepName;

    private String provider;
    private String model;

    @Enumerated(EnumType.STRING)
    private StepStatus status = StepStatus.PENDING;

    @Column(name = "retry_count")
    private int retryCount = 0;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Non-fatal - the step still succeeded. Set when a TTS fallback fired
     *  during narration generation (see ProviderGateway/ProductionPipelineService)
     *  so "never silently replace the user's selected voice" actually
     *  reaches the UI instead of staying a backend log line. */
    @Column(name = "warning_message", columnDefinition = "TEXT")
    private String warningMessage;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}
