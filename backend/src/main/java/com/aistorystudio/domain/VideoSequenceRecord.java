package com.aistorystudio.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "video_sequence_record")
@Getter
@Setter
public class VideoSequenceRecord {
    @Id
    private UUID id;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "episode_id")
    private UUID episodeId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String manifestJson;
}
