package com.aistorystudio.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CreateCharacterRequest(
        UUID universeId,
        @NotBlank String name,
        String species,
        String age,
        String personality,
        @NotBlank String canonicalDescription,
        String negativeConstraints,
        String attributesJson
) {}
