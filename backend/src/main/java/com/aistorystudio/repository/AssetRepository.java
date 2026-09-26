package com.aistorystudio.repository;

import com.aistorystudio.domain.Asset;
import com.aistorystudio.domain.enums.AssetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {
    List<Asset> findByEpisodeId(UUID episodeId);
    List<Asset> findBySceneId(UUID sceneId);
    Optional<Asset> findFirstBySceneIdAndAssetTypeAndActiveTrueOrderByVersionDesc(UUID sceneId, AssetType assetType);
    List<Asset> findByEpisodeIdAndAssetType(UUID episodeId, AssetType assetType);
}
