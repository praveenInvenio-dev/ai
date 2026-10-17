package com.aistorystudio.videoeditor.editing;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimelineFinisherTest {
    @Test
    void sanitizesAnimationAndCustomPositionWhileKeepingLegacyDefaults() {
        String input = "[{\"type\":\"text\",\"start\":1,\"end\":4,\"text\":\"JVM\",\"position\":\"custom\",\"xPercent\":72,\"yPercent\":35,\"animation\":\"float\"},{\"type\":\"text\",\"start\":4,\"end\":6,\"text\":\"Legacy\"}]";
        String sanitized = TimelineFinisher.sanitize(input);
        assertTrue(sanitized.contains("\"position\":\"custom\""), sanitized);
        assertTrue(sanitized.contains("\"animation\":\"float\""), sanitized);
        assertTrue(sanitized.contains("\"xPercent\":72.0"), sanitized);
        assertTrue(sanitized.contains("\"yPercent\":35.0"), sanitized);
        assertTrue(sanitized.contains("\"animation\":\"fade\""), sanitized);
    }

    @Test
    void preservesBehindPersonTextAsAValidatedOverlayType() {
        String input = "[{\"type\":\"text_behind_person\",\"start\":2,\"end\":5,\"text\":\"JVM\",\"position\":\"custom\",\"xPercent\":55,\"yPercent\":42,\"animation\":\"float\"}]";
        String sanitized = TimelineFinisher.sanitize(input);
        assertTrue(sanitized.contains("\"type\":\"text_behind_person\""), sanitized);
        assertTrue(sanitized.contains("\"animation\":\"float\""), sanitized);
        assertEquals(1, TimelineFinisher.parse(sanitized).size());
        assertEquals("text_behind_person", TimelineFinisher.parse(sanitized).get(0).type());
    }

    @Test
    void rejectsUnknownAnimationAndClampsCustomCoordinates() {
        String input = "[{\"type\":\"text\",\"start\":0,\"end\":2,\"text\":\"Safe\",\"position\":\"custom\",\"xPercent\":150,\"yPercent\":-20,\"animation\":\"raw_ffmpeg\"}]";
        String sanitized = TimelineFinisher.sanitize(input);
        assertTrue(sanitized.contains("\"animation\":\"fade\""), sanitized);
        assertTrue(sanitized.contains("\"xPercent\":100.0"), sanitized);
        assertTrue(sanitized.contains("\"yPercent\":0.0"), sanitized);
    }
}
