package com.aistorystudio.conceptexplainer.tutorial;

import com.aistorystudio.conceptexplainer.ConceptExplainerJob;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VisualRendererRouterTest {
    private ConceptExplainerJob job() {
        return new ConceptExplainerJob(UUID.randomUUID(), "Java", "", "English", "short", "beginner", "", "", "", "programming", false, "");
    }
    private ConceptExplainerJob.Scene scene(String title, String narration, String visual, String code, String plan) {
        var s = new ConceptExplainerJob.Scene();
        s.setTitle(title); s.setNarration(narration); s.setVisualDescription(visual); s.setCode(code); s.setPlanJson(plan);
        return s;
    }
    @Test void codeAndProcessLessonsUseWorkingThreeJsRenderer() {
        var route = VisualRendererRouter.route(job(), scene("Java Threads", "Create a new thread", "Process workflow", "thread.start()", "{}"), false);
        assertEquals("THREE_JS", route.engine());
    }
    @Test void spatialScenesUseThreeJs() {
        var route = VisualRendererRouter.route(job(), scene("Solar system", "Planets orbit the sun", "3D model", "", "{}"), false);
        assertEquals("THREE_JS", route.engine());
    }
    @Test void riveIsOnlySelectedWhenAnAssetIsExplicitlyConfigured() {
        var route = VisualRendererRouter.route(job(), scene("Character", "The character waves", "Animated character", "", "{\"riveAsset\":\"assets/character.riv\"}"), false);
        assertEquals("RIVE", route.engine());
    }
    @Test void uploadedRiveAssetRoutesCharacterScenesToRive() {
        var job = job();
        job.setRiveAssetPath("rive-assets/test-asset.riv");
        var route = VisualRendererRouter.route(job, scene("Mascot introduction", "Our character waves hello", "Animated character on screen", "", "{}"), false);
        assertEquals("RIVE", route.engine());
    }
    @Test void uploadedRiveAssetDoesNotOverrideUnrelatedCodeScenes() {
        var job = job();
        job.setRiveAssetPath("rive-assets/test-asset.riv");
        var route = VisualRendererRouter.route(job, scene("Java Threads", "Create a new thread", "Process workflow", "thread.start()", "{}"), false);
        assertEquals("THREE_JS", route.engine());
    }

    @Test void generalExplanationsDoNotDefaultToUnavailableMotionCanvas() {
        var route = VisualRendererRouter.route(job(), scene("Introduction", "Welcome to today's topic", "A teacher introduces the lesson", "", "{}"), false);
        assertEquals("NATIVE_DIAGRAM", route.engine());
    }
}
