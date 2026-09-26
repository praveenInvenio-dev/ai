package com.aistorystudio.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "character_reference")
@Getter
@Setter
public class CharacterReference {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "character_id", nullable = false)
    private UUID characterId;

    @Column(name = "image_path", nullable = false, columnDefinition = "TEXT")
    private String imagePath;

    @Column(name = "image_hash")
    private String imageHash;

    private String source = "GENERATED";

    @Column(name = "is_primary")
    private boolean primary = false;

    private Instant createdAt = Instant.now();
}
