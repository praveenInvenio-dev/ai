package com.aistorystudio.dto;

import java.util.List;
import java.util.UUID;

public record StoryDraftResponse(
        UUID episodeId,
        String title,
        String logline,
        String fullNarration,
        Integer qualityScore,
        List<String> qualityFeedback,
        List<SceneDto> scenes,
        Double estimatedDurationSeconds
) {}
