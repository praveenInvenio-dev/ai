package com.aistorystudio.videoeditor.repository;

import com.aistorystudio.videoeditor.domain.VideoAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VideoAnalysisRepository extends JpaRepository<VideoAnalysis, UUID> {
    Optional<VideoAnalysis> findByClipId(UUID clipId);
    void deleteByClipId(UUID clipId);
}
