package com.aistorystudio.repository;

import com.aistorystudio.domain.GenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {
    List<GenerationJob> findByEpisodeIdOrderByCreatedAtDesc(UUID episodeId);
}
