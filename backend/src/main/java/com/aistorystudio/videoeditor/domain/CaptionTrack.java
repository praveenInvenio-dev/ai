package com.aistorystudio.videoeditor.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "caption_track")
@Getter
@Setter
public class CaptionTrack {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false, unique = true)
    private UUID projectId;

    @Column(name = "style_preset", nullable = false, length = 32)
    private String stylePreset = "CLEAN";

    @Column(name = "burn_in", nullable = false)
    private boolean burnIn = true;

    @Column(name = "srt_filename", columnDefinition = "TEXT")
    private String srtFilename;

    @Column(name = "ass_filename", columnDefinition = "TEXT")
    private String assFilename;

    /** Whisper output with word timings, kept so captions can be restyled
     *  without re-running transcription (the slowest stage on CPU). */
    @Column(name = "transcript_json", columnDefinition = "TEXT")
    private String transcriptJson;
}
