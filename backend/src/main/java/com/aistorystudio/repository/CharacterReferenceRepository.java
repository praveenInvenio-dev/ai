package com.aistorystudio.repository;

import com.aistorystudio.domain.CharacterReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CharacterReferenceRepository extends JpaRepository<CharacterReference, UUID> {
    List<CharacterReference> findByCharacterId(UUID characterId);
}
