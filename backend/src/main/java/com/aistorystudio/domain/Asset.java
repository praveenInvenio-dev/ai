package com.aistorystudio.domain;

import com.aistorystudio.domain.enums.AssetType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset")
@Getter
@Setter
public class Asset {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "episode_id", nullable = false)
    private UUID episodeId;

    @Column(name = "scene_id")
    private UUID sceneId;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false)
    private AssetType assetType;

    @Column(name = "file_path", nullable = false, columnDefinition = "TEXT")
    private String filePath;

    private int version = 1;

    @Column(name = "is_active")
    private boolean active = true;

    private String provider;
    private String model;
    private Long seed;
    private String workflow;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "negative_prompt", columnDefinition = "TEXT")
    private String negativePrompt;

    @Column(name = "duration_seconds")
    private Double durationSeconds;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    private Instant createdAt = Instant.now();
}
