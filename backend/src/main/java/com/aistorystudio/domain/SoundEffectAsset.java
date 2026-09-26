package com.aistorystudio.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sound_effect_asset")
@Getter
@Setter
public class SoundEffectAsset {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "file_path", nullable = false, columnDefinition = "TEXT")
    private String filePath;

    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;

    private String license;

    private Instant createdAt = Instant.now();
}
