package com.aistorystudio.domain;

import com.aistorystudio.domain.enums.JobStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "generation_job")
@Getter
@Setter
public class GenerationJob {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "episode_id", nullable = false)
    private UUID episodeId;

    @Enumerated(EnumType.STRING)
    private JobStatus status = JobStatus.QUEUED;

    @Column(name = "progress_percent")
    private int progressPercent = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}
