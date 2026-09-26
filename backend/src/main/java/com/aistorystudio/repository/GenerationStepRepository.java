package com.aistorystudio.repository;

import com.aistorystudio.domain.GenerationStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GenerationStepRepository extends JpaRepository<GenerationStep, UUID> {
    List<GenerationStep> findByJobIdOrderByCreatedAtAsc(UUID jobId);
}
