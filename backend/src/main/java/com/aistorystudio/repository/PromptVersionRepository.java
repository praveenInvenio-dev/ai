package com.aistorystudio.repository;

import com.aistorystudio.domain.PromptVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PromptVersionRepository extends JpaRepository<PromptVersion, UUID> {
    List<PromptVersion> findBySceneIdOrderByVersionDesc(UUID sceneId);
}
