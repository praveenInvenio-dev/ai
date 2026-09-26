package com.aistorystudio.videoeditor.domain;

import com.aistorystudio.videoeditor.domain.enums.AudioTrackKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audio_track")
@Getter
@Setter
public class AudioTrack {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AudioTrackKind kind = AudioTrackKind.MUSIC;

    @Column(name = "stored_filename", nullable = false, columnDefinition = "TEXT")
    private String storedFilename;

    @Column(name = "display_name", columnDefinition = "TEXT")
    private String displayName;

    @Column(nullable = false)
    private double gain = 1.0;

    @Column(name = "duck_under_speech", nullable = false)
    private boolean duckUnderSpeech = true;

    private Double bpm;

    /** Beat timestamps in seconds, JSON array. Null until analysed. */
    @Column(name = "beats_json", columnDefinition = "TEXT")
    private String beatsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
