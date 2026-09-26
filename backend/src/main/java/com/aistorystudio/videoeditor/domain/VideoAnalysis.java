package com.aistorystudio.videoeditor.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The raw analysis document for a clip: speech segments, face boxes, colour
 * histogram, per-frame samples.
 *
 * Kept as one TEXT blob rather than normalised into tables because nothing
 * queries inside it - the planner reads the whole document for a clip and works
 * in memory. Normalising thousands of per-frame samples would cost a great deal
 * of schema for no query we intend to run.
 */
@Entity
@Table(name = "video_analysis")
@Getter
@Setter
public class VideoAnalysis {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "clip_id", nullable = false, unique = true)
    private UUID clipId;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    /** Which worker version produced this, so stale analyses are identifiable. */
    @Column(length = 64)
    private String analyzer;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
