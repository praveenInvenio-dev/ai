package com.aistorystudio.repository;

import com.aistorystudio.domain.Scene;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SceneRepository extends JpaRepository<Scene, UUID> {
    List<Scene> findByEpisodeIdOrderByOrderIndexAsc(UUID episodeId);
}
