package com.aistorystudio.repository;

import com.aistorystudio.domain.StoryBible;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoryBibleRepository extends JpaRepository<StoryBible, UUID> {
    List<StoryBible> findByEpisodeIdOrderByVersionDesc(UUID episodeId);
    Optional<StoryBible> findFirstByEpisodeIdAndActiveTrueOrderByVersionDesc(UUID episodeId);
}
