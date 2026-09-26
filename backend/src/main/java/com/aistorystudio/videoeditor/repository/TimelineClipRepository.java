package com.aistorystudio.videoeditor.repository;

import com.aistorystudio.videoeditor.domain.TimelineClip;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TimelineClipRepository extends JpaRepository<TimelineClip, UUID> {
    List<TimelineClip> findByProjectIdOrderBySortOrderAsc(UUID projectId);
    void deleteByProjectId(UUID projectId);
}
