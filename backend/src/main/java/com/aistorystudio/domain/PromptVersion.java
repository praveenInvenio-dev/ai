package com.aistorystudio.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "prompt_version")
@Getter
@Setter
public class PromptVersion {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "scene_id", nullable = false)
    private UUID sceneId;

    private int version;

    @Column(name = "prompt_type", nullable = false)
    private String promptType; // IMAGE, NEGATIVE, NARRATION

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    private Instant createdAt = Instant.now();
}
