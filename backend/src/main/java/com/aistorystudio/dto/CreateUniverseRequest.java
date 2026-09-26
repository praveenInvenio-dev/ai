package com.aistorystudio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateUniverseRequest(
        @NotNull UUID projectId,
        @NotBlank String name,
        String description,
        String visualStyle,
        String colorPalette,
        String worldRules
) {}
