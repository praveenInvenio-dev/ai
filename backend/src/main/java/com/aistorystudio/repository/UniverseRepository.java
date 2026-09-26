package com.aistorystudio.repository;

import com.aistorystudio.domain.Universe;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UniverseRepository extends JpaRepository<Universe, UUID> {
    List<Universe> findByProjectId(UUID projectId);
}
