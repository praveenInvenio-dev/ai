package com.aistorystudio.videoeditor.repository;

import com.aistorystudio.videoeditor.domain.VideoClip;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VideoClipRepository extends JpaRepository<VideoClip, UUID> {
    List<VideoClip> findByProjectIdOrderBySortOrderAsc(UUID projectId);
    long countByProjectId(UUID projectId);
    void deleteByProjectId(UUID projectId);
}
