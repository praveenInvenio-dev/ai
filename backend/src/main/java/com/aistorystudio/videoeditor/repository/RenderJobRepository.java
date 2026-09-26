package com.aistorystudio.videoeditor.repository;

import com.aistorystudio.videoeditor.domain.RenderJob;
import com.aistorystudio.videoeditor.domain.enums.RenderKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RenderJobRepository extends JpaRepository<RenderJob, UUID> {
    List<RenderJob> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    Optional<RenderJob> findFirstByProjectIdAndKindOrderByCreatedAtDesc(UUID projectId, RenderKind kind);
}
