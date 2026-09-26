package com.aistorystudio.videoeditor.repository;

import com.aistorystudio.videoeditor.domain.AudioTrack;
import com.aistorystudio.videoeditor.domain.enums.AudioTrackKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AudioTrackRepository extends JpaRepository<AudioTrack, UUID> {
    List<AudioTrack> findByProjectId(UUID projectId);
    Optional<AudioTrack> findFirstByProjectIdAndKind(UUID projectId, AudioTrackKind kind);
}
