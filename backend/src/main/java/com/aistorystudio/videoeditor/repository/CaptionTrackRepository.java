package com.aistorystudio.videoeditor.repository;

import com.aistorystudio.videoeditor.domain.CaptionTrack;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CaptionTrackRepository extends JpaRepository<CaptionTrack, UUID> {
    Optional<CaptionTrack> findByProjectId(UUID projectId);
}
