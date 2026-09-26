package com.aistorystudio.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "episode_memory")
@Getter
@Setter
public class EpisodeMemory {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "universe_id", nullable = false)
    private UUID universeId;

    @Column(name = "episode_id")
    private UUID episodeId;

    @Column(name = "summary_json", nullable = false, columnDefinition = "TEXT")
    private String summaryJson;

    private Instant createdAt = Instant.now();
}
