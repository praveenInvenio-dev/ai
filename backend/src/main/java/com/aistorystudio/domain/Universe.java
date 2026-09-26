package com.aistorystudio.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "universe")
@Getter
@Setter
public class Universe {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "visual_style_json", columnDefinition = "TEXT")
    private String visualStyleJson;

    @Column(name = "world_rules_json", columnDefinition = "TEXT")
    private String worldRulesJson;

    @Column(name = "color_palette_json", columnDefinition = "TEXT")
    private String colorPaletteJson;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}
