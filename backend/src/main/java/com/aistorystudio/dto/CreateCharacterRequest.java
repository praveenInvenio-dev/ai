package com.aistorystudio.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CreateCharacterRequest(
        UUID universeId,
        /** Set this OR universeId, not both in practice - a character scoped
         *  to one standalone episode rather than shared across a universe.
         *  Previously the DTO had no way to set this at all despite the
         *  schema/entity/read-path (charactersForEpisode's fallback) all
         *  already supporting it - this was dead capability until now. */
        UUID episodeId,
        @NotBlank String name,
        String species,
        String age,
        String personality,
        @NotBlank String canonicalDescription,
        String negativeConstraints,
        String attributesJson
) {}
