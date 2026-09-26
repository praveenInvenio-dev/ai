package com.aistorystudio.repository;

import com.aistorystudio.domain.ProviderConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProviderConfigurationRepository extends JpaRepository<ProviderConfiguration, UUID> {
}
