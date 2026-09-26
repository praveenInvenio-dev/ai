package com.aistorystudio.repository;

import com.aistorystudio.domain.Episode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EpisodeRepository extends JpaRepository<Episode, UUID> {
    List<Episode> findByProjectId(UUID projectId);
    List<Episode> findByUniverseIdOrderByEpisodeNumberAsc(UUID universeId);
}
