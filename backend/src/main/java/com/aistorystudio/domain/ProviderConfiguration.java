package com.aistorystudio.domain;

import com.aistorystudio.domain.enums.ProviderType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provider_configuration")
@Getter
@Setter
public class ProviderConfiguration {
    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false)
    private ProviderType providerType;

    @Column(name = "provider_name", nullable = false)
    private String providerName;

    @Column(name = "is_default")
    private boolean isDefault = false;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}
