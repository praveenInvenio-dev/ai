package com.aistorystudio.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "story_bible")
@Getter
@Setter
public class StoryBible {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "episode_id", nullable = false)
    private UUID episodeId;

    private int version = 1;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String logline;

    @Column(name = "full_narration", columnDefinition = "TEXT")
    private String fullNarration;

    @Column(name = "characters_json", columnDefinition = "TEXT")
    private String charactersJson;

    @Column(name = "locations_json", columnDefinition = "TEXT")
    private String locationsJson;

    @Column(name = "objects_json", columnDefinition = "TEXT")
    private String objectsJson;

    @Column(name = "relationships_json", columnDefinition = "TEXT")
    private String relationshipsJson;

    @Column(name = "timeline_json", columnDefinition = "TEXT")
    private String timelineJson;

    @Column(name = "visual_style_json", columnDefinition = "TEXT")
    private String visualStyleJson;

    @Column(name = "continuity_rules_json", columnDefinition = "TEXT")
    private String continuityRulesJson;

    @Column(name = "emotional_arc_json", columnDefinition = "TEXT")
    private String emotionalArcJson;

    @Column(name = "story_beats_json", columnDefinition = "TEXT")
    private String storyBeatsJson;

    @Column(name = "is_active")
    private boolean active = true;

    private Instant createdAt = Instant.now();
}
