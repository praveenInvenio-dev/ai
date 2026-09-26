package com.aistorystudio.repository;

import com.aistorystudio.domain.SoundEffectAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SoundEffectAssetRepository extends JpaRepository<SoundEffectAsset, UUID> {
}
