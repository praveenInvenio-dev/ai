package com.aistorystudio.conceptexplainer.tutorial;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the engine-aware lesson-generation contract so prompt edits keep using installed renderers. */
class ConceptVideoEnginePromptTest {
    @Test void conceptPitchNamesAvailableEngineFamiliesAndTheirTeachingJobs() {
        String prompt = TutorialPrompts.conceptsSystemPrompt();
        assertTrue(prompt.contains("MANIM"));
        assertTrue(prompt.contains("THREE_JS"));
        assertTrue(prompt.contains("MOTION_CANVAS"));
        assertTrue(prompt.contains("Do NOT use WAN, ComfyUI, generated still images, or image-to-video for Concept Explainer lessons."));
    }

    @Test void lessonOutlinePreservesEngineActivatingScienceAndProcessDevices() {
        String prompt = TutorialPrompts.outlineUserPrompt("JEE projectile motion", "English", "JEE", "", "", TutorialPrompts.shortSize());
        assertTrue(prompt.contains("ENGINE-AWARE VISUAL PLAN"));
        assertTrue(prompt.contains("forces, wave, lens, triangle, numberline, formula or plot"));
        assertTrue(prompt.contains("sequence, flowchart, datastruct, layers, lanes, steps or code"));
    }

    @Test void sceneWriterDoesNotCollapseSpecialisedVisualsIntoGenericCards() {
        String prompt = TutorialPrompts.writeSystemPrompt();
        assertTrue(prompt.contains("Preserve the outline's concrete science/math device"));
        assertTrue(prompt.contains("existing Motion Canvas router"));
        assertTrue(prompt.contains("Three.js"));
        assertTrue(prompt.contains("validated Manim spec"));
    }
}
