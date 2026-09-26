package com.aistorystudio.repository;

import com.aistorystudio.domain.MusicAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MusicAssetRepository extends JpaRepository<MusicAsset, UUID> {
}
