package com.aistorystudio.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "story_character")
@Getter
@Setter
public class Character {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "universe_id")
    private UUID universeId;

    @Column(nullable = false)
    private String name;

    private String species;
    private String age;

    @Column(columnDefinition = "TEXT")
    private String personality;

    @Column(name = "canonical_description", nullable = false, columnDefinition = "TEXT")
    private String canonicalDescription;

    @Column(name = "negative_constraints", columnDefinition = "TEXT")
    private String negativeConstraints;

    @Column(name = "prompt_template", columnDefinition = "TEXT")
    private String promptTemplate;

    @Column(name = "attributes_json", columnDefinition = "TEXT")
    private String attributesJson;

    private boolean locked = false;
    private int version = 1;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}
