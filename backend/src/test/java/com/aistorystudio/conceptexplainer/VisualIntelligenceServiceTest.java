package com.aistorystudio.conceptexplainer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisualIntelligenceServiceTest {
    private final VisualIntelligenceService service = new VisualIntelligenceService();

    @Test
    void particleTopicSuggestsPixiWithSafeFallbacks() {
        var plan = service.plan(new VisualIntelligenceService.PlanRequest("Electric current and moving charges", "school", "physics", "1 minute", "balanced"));
        assertTrue(plan.engines().stream().anyMatch(e -> e.id().equals("PIXIJS")));
        assertTrue(plan.sceneIdeas().stream().anyMatch(s -> s.visualIntent().equals("particle_or_flow_motion") && s.suggestedEngine().equals("NATIVE_DIAGRAM")));
        assertTrue(plan.conceptGraph().nodes().size() >= 3);
        assertTrue(plan.policyNote().contains("Wan/ComfyUI"));
        assertTrue(plan.policyNote().contains("health endpoint"));
    }

    @Test
    void mathTopicSuggestsManimAndSpatialTopicSuggestsThreeJs() {
        var math = service.plan(new VisualIntelligenceService.PlanRequest("Projectile motion equations", "school", "physics", "3 minutes", "balanced"));
        assertTrue(math.engines().stream().anyMatch(e -> e.id().equals("MANIM") && e.score() >= 90));
        var spatial = service.plan(new VisualIntelligenceService.PlanRequest("Solar system and planetary orbits", "school", "science", "3 minutes", "balanced"));
        assertTrue(spatial.engines().stream().anyMatch(e -> e.id().equals("THREE_JS") && e.score() >= 90));
    }

    @Test
    void blankTopicIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.plan(new VisualIntelligenceService.PlanRequest("  ", "school", "science", "1 minute", "balanced")));
    }
}
