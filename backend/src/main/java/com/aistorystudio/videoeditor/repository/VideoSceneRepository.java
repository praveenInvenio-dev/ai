package com.aistorystudio.videoeditor.repository;

import com.aistorystudio.videoeditor.domain.VideoScene;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VideoSceneRepository extends JpaRepository<VideoScene, UUID> {
    List<VideoScene> findByClipIdOrderBySceneIndexAsc(UUID clipId);
    void deleteByClipId(UUID clipId);
}
