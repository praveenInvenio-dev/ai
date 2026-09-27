package com.aistorystudio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Payload from the "What would you like to create?" screen. */
public record CreateStoryRequest(
        @NotNull UUID projectId,
        UUID universeId,
        @NotBlank String prompt,
        Integer durationSeconds,
        String targetAge,
        String genre,
        String tone,
        String visualStyle,
        String language,
        List<UUID> characterIds,
        /** Name of a model currently installed on the Ollama server (see GET /api/models/ollama). Null = server default. */
        String ollamaModel,
        /** FAST | BALANCED | QUALITY (Phase 4) - null/blank falls back to Episode's own BALANCED default. */
        String qualityProfile
) {}
