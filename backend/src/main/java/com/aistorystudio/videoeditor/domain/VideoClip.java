package com.aistorystudio.videoeditor.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An uploaded source video.
 *
 * Note the split between {@code storedFilename} and {@code displayName}: the
 * stored name is generated from this row's UUID and is the only thing that ever
 * reaches the filesystem, while the user's original filename is kept purely as
 * a label. That is what makes path traversal and shell-metacharacter filenames
 * inexpressible rather than filtered - there is no code path where user text
 * becomes a path segment.
 */
@Entity
@Table(name = "video_clip")
@Getter
@Setter
public class VideoClip {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "stored_filename", nullable = false, columnDefinition = "TEXT")
    private String storedFilename;

    @Column(name = "display_name", nullable = false, columnDefinition = "TEXT")
    private String displayName;

    /** 480p re-encode used for analysis and preview. Null until generated. */
    @Column(name = "proxy_filename", columnDefinition = "TEXT")
    private String proxyFilename;

    @Column(name = "thumbnail_filename", columnDefinition = "TEXT")
    private String thumbnailFilename;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "duration_sec")
    private Double durationSec;

    private Integer width;
    private Integer height;
    private Double fps;

    @Column(name = "video_codec", length = 32)
    private String videoCodec;

    @Column(name = "audio_codec", length = 32)
    private String audioCodec;

    @Column(name = "has_audio", nullable = false)
    private boolean hasAudio = false;

    @Column(nullable = false)
    private boolean analyzed = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public boolean isPortrait() {
        return width != null && height != null && height > width;
    }
}
