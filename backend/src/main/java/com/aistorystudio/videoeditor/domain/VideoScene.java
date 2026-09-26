package com.aistorystudio.videoeditor.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** A detected shot within a clip, with the measurements the planner ranks on. */
@Entity
@Table(name = "video_scene")
@Getter
@Setter
public class VideoScene {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "clip_id", nullable = false)
    private UUID clipId;

    @Column(name = "scene_index", nullable = false)
    private int sceneIndex;

    @Column(name = "start_sec", nullable = false)
    private double startSec;

    @Column(name = "end_sec", nullable = false)
    private double endSec;

    @Column(name = "motion_score")
    private Double motionScore;

    private Double brightness;

    @Column(name = "blur_score")
    private Double blurScore;

    @Column(name = "audio_rms")
    private Double audioRms;

    /**
     * Composite 0..1 score, persisted rather than derived on read.
     *
     * Storing it means a plan stays reproducible after the scoring weights are
     * tuned - otherwise reopening an old project would silently reorder its
     * footage preferences and the timeline would no longer match the render.
     */
    @Column(name = "quality_score")
    private Double qualityScore;

    @Column(name = "shot_type", length = 32)
    private String shotType;

    @Column(name = "has_speech", nullable = false)
    private boolean hasSpeech = false;

    public double durationSec() {
        return endSec - startSec;
    }
}
