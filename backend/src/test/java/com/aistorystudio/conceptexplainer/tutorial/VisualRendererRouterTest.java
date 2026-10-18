package com.aistorystudio.conceptexplainer.tutorial;

import com.aistorystudio.conceptexplainer.ConceptExplainerJob;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualRendererRouterTest {
    private ConceptExplainerJob job() {
        return new ConceptExplainerJob(UUID.randomUUID(), "Java", "", "English", "short", "beginner", "", "", "", "programming", false, "");
    }
    private ConceptExplainerJob.Scene scene(String title, String narration, String visual, String code, String plan) {
        var s = new ConceptExplainerJob.Scene();
        s.setTitle(title); s.setNarration(narration); s.setVisualDescription(visual); s.setCode(code); s.setPlanJson(plan);
        return s;
    }
    @Test void codeAndProcessLessonsUseTheNativeDiagramRenderer() {
        var route = VisualRendererRouter.route(job(), scene("Java Threads", "Create a new thread", "Process workflow", "thread.start()", "{}"), false);
        assertEquals("NATIVE_DIAGRAM", route.engine());
    }
    @Test void spatialScenesUseThreeJs() {
        var route = VisualRendererRouter.route(job(), scene("Solar system", "Planets orbit the sun", "3D model", "", "{}"), false);
        assertEquals("THREE_JS", route.engine());
    }
    @Test void riveIsOnlySelectedWhenAnAssetIsExplicitlyConfigured() {
        var route = VisualRendererRouter.route(job(), scene("Character", "The character waves", "Animated character", "", "{\"riveAsset\":\"rive-assets/0b1f2c3d-aaaa-bbbb-cccc-1234567890ab.riv\"}"), false);
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
        assertEquals("NATIVE_DIAGRAM", route.engine());
    }

    @Test void generalExplanationsDoNotDefaultToUnavailableMotionCanvas() {
        var route = VisualRendererRouter.route(job(), scene("Introduction", "Welcome to today's topic", "A teacher introduces the lesson", "", "{}"), false);
        assertEquals("NATIVE_DIAGRAM", route.engine());
    }

    @Test void aPlanThatNamesAnArbitraryPathNeverRoutesToRive() {
        var route = VisualRendererRouter.route(job(), scene("Character", "The character waves", "Animated character", "", "{\"riveAsset\":\"/etc/passwd\"}"), false);
        assertEquals("NATIVE_DIAGRAM", route.engine());
    }

    @Test void aLessonMixesEnginesAndKeepsDepthForAMinority() {
        var job = job();
        job.getScenes().add(scene("Two clerks", "Two clerks update one balance.", "bank counter", "", "{}"));
        job.getScenes().add(scene("Planets orbit", "The planets orbit the sun.", "solar system", "", "{}"));
        job.getScenes().add(scene("The race condition", "Two threads read the counter.", "", "count++", "{}"));
        job.getScenes().add(scene("Moons orbit", "Moons orbit their planets.", "orbit", "", "{}"));
        job.getScenes().add(scene("Satellites orbit", "Satellites orbit the earth.", "orbit", "", "{}"));
        job.getScenes().add(scene("Galaxy", "Stars orbit the galaxy centre.", "galaxy", "", "{}"));
        var routes = VisualRendererRouter.planJob(job, new com.aistorystudio.conceptexplainer.tutorial.EngineScorer.Capabilities(false, true, true, false));
        long three = routes.stream().filter(r -> r.engine().equals("THREE_JS")).count();
        assertTrue(three >= 1 && three <= 2, "3D must be a minority, was " + three);
        assertTrue(routes.stream().anyMatch(r -> r.engine().equals("NATIVE_DIAGRAM")));
    }
}
