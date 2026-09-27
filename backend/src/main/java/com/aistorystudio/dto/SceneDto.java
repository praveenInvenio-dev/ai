package com.aistorystudio.dto;

import java.util.List;
import java.util.UUID;

public record SceneDto(
        UUID id,
        int sceneNumber,
        String purpose,
        String narration,
        String location,
        String action,
        String emotion,
        String camera,
        String lighting,
        String imagePrompt,
        String negativePrompt,
        String motionPrompt,
        String motionNegativePrompt,
        String visualSpecJson,
        Double narrationSeconds,
        Double imageDurationSeconds,
        String cameraMovement,
        String importance,
        boolean locked,
        boolean narrationLocked,
        String animationMode,
        List<VoiceSegmentDto> voiceSegments,
        List<String> characterNames
) {
    public record VoiceSegmentDto(
            String character, String text, String voice, Double speed, Double pitch,
            String emotion, Integer pauseBeforeMs, Integer pauseAfterMs,
            Double emotionIntensity, String delivery, List<String> emphasis,
            Boolean breath, String paralinguisticEvent, String actingDirection) {}
}
