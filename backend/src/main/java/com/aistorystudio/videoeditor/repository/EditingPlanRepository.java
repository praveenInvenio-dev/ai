package com.aistorystudio.videoeditor.repository;

import com.aistorystudio.videoeditor.domain.EditingPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EditingPlanRepository extends JpaRepository<EditingPlan, UUID> {
    /** The current plan. Older plans are kept so a preview cached under an
     *  earlier plan_hash is still attributable after further edits. */
    Optional<EditingPlan> findFirstByProjectIdOrderByCreatedAtDesc(UUID projectId);

    /** Full version history, newest first - every plan ever generated or
     *  hand-saved for this project, none of them ever deleted. */
    java.util.List<EditingPlan> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
