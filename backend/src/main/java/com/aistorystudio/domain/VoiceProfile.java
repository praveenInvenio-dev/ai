package com.aistorystudio.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A reusable, named voice - built-in (Piper) or cloned (ChatterBox, later
 * CosyVoice) - usable across any story/character rather than re-entered per
 * episode. See VoiceProfileService for the recording/upload/validation flow
 * and VoiceProfileController for the REST surface.
 */
@Entity
@Table(name = "voice_profile")
@Getter
@Setter
public class VoiceProfile {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 20)
    private String language;

    /** piper | chatterbox | (cosyvoice, once that provider exists) */
    @Column(nullable = false, length = 20)
    private String provider;

    /** Null for a built-in Piper voice - nothing was uploaded, voiceName IS
     *  the Piper voice id. Set for a cloned voice: filename (no extension)
     *  under the shared voice-profile-audio volume. */
    @Column(name = "reference_audio_key", length = 80)
    private String referenceAudioKey;

    @Column(name = "voice_name", nullable = false, length = 120)
    private String voiceName;

    @Column(columnDefinition = "TEXT")
    private String personality;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "duration_seconds")
    private Double durationSeconds;

    @Column(name = "sample_rate")
    private Integer sampleRate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
