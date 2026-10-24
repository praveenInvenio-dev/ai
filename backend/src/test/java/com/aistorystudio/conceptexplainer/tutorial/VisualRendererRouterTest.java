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
        assertTrue(three >= 1 && three <= 3, "3D must be a meaningful minority, was " + three);
        assertTrue(routes.stream().anyMatch(r -> r.engine().equals("NATIVE_DIAGRAM")));
    }


    @Test void stackLessonGetsSeveralThreeDimensionalScenesWhenTheyFit() {
        var job = job();
        job.getScenes().add(scene("The Power of One", "Constant time operations stay fast.", "", "", "{}"));
        job.getScenes().add(scene("Real World LIFO", "Imagine a stack of plates. The last plate added leaves first.", "3D stack of plates", "", "{\"elements\":[{\"type\":\"datastruct\"}]}"));
        job.getScenes().add(scene("The Undo Problem", "Undo removes the most recent action from a stack.", "3D stack showing undo", "", "{\"elements\":[{\"type\":\"datastruct\"}]}"));
        job.getScenes().add(scene("The Java Stack", "Java uses push and pop on a stack. LIFO means last in, first out.", "3D stack data structure", "", "{\"elements\":[{\"type\":\"datastruct\"}]}"));
        job.getScenes().add(scene("The Logic Flow", "A user types, then pushes a character, then pops it on undo.", "ordered sequence", "", "{\"elements\":[{\"type\":\"sequence\"}]}"));
        job.getScenes().add(scene("Java Implementation", "Here is the code for push and pop.", "", "stack.push(value);", "{\"elements\":[{\"type\":\"code\"}]}"));
        job.getScenes().add(scene("Stack vs Queue", "Compare a stack and a queue.", "comparison", "", "{\"elements\":[{\"type\":\"compare\"}]}"));
        job.getScenes().add(scene("Use Cases", "The browser history and call stack use LIFO.", "3D stack", "", "{\"elements\":[{\"type\":\"datastruct\"}]}"));
        job.getScenes().add(scene("Final Recap", "LIFO means last in first out.", "recap", "", "{}"));
        var routes = VisualRendererRouter.planJob(job, new com.aistorystudio.conceptexplainer.tutorial.EngineScorer.Capabilities(false, false, true, true, true));
        long three = routes.stream().filter(r -> r.engine().equals("THREE_JS")).count();
        assertTrue(three >= 2, "stack lesson should use several relevant 3D scenes, was " + three);
        assertTrue(routes.stream().anyMatch(r -> "stack".equals(r.sceneKind())), "stack template should be selected");
        assertTrue(routes.stream().anyMatch(r -> r.engine().equals("NATIVE_DIAGRAM")), "code and recap should retain native rendering");
    }

    @Test void anAvailableAnimationEngineIsUsedAtLeastOnceInALongerLesson() {
        var job = job();
        job.getScenes().add(scene("Intro", "Welcome to the lesson about queues.", "teacher", "", "{}"));
        job.getScenes().add(scene("The line", "The cashier takes the order, then the payment, then hands over the receipt.", "shop", "", "{}"));
        job.getScenes().add(scene("Why it matters", "Customers wait when one step is slow.", "queue", "", "{}"));
        job.getScenes().add(scene("A fix", "Add a second cashier.", "two cashiers", "", "{}"));
        job.getScenes().add(scene("Recap", "One line, one cashier, slow service.", "recap", "", "{}"));
        var routes = VisualRendererRouter.planJob(job, new com.aistorystudio.conceptexplainer.tutorial.EngineScorer.Capabilities(false, false, true, true));
        assertTrue(routes.stream().anyMatch(r -> r.engine().equals("MOTION_CANVAS")), "expected one Motion Canvas scene");
        var off = VisualRendererRouter.planJob(job, new com.aistorystudio.conceptexplainer.tutorial.EngineScorer.Capabilities(false, false, true, false));
        assertTrue(off.stream().noneMatch(r -> r.engine().equals("MOTION_CANVAS")));
    }
}
