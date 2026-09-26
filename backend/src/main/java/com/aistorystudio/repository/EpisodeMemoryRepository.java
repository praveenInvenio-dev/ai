package com.aistorystudio.repository;

import com.aistorystudio.domain.EpisodeMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EpisodeMemoryRepository extends JpaRepository<EpisodeMemory, UUID> {
    List<EpisodeMemory> findByUniverseIdOrderByCreatedAtDesc(UUID universeId);
}
