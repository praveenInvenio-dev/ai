package com.aistorystudio.conceptexplainer.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aistorystudio.conceptexplainer.tutorial.EngineScorer.Capabilities;
import com.aistorystudio.conceptexplainer.tutorial.EngineScorer.Engine;
import com.aistorystudio.conceptexplainer.tutorial.EngineScorer.Signals;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The engine is chosen from the scene's topic, not by habit. */
class EngineScorerTest {

    private static EngineScorer.Decision pick(String title, String narration, String visual, String code, String domain, Set<String> types,
                                              boolean manim, boolean rive, int kids, Capabilities caps) {
        return EngineScorer.decide(new Signals(title, narration, visual, code, domain, "", types, manim, false, rive, kids), caps);
    }

    private static EngineScorer.Decision pick(String title, String narration, String visual, String domain) {
        return pick(title, narration, visual, "", domain, Set.of(), false, false, 0, Capabilities.all());
    }

    @Test void depthTopicsGoToThreeJsWithTheRightTemplate() {
        var solar = pick("How the planets orbit the sun", "The planets orbit the sun and the moon orbits Earth.", "solar system", "astronomy");
        assertEquals(Engine.THREE_JS, solar.engine()); assertEquals("orbit", solar.threeKind());
        assertEquals("molecule", pick("Water is a molecule", "Two hydrogen atoms bond to one oxygen atom.", "molecule model", "chemistry").threeKind());
        assertEquals("layers", pick("Layers of the Earth", "The crust sits on the mantle. Cut a cross-section to see them.", "", "geography").threeKind());
        assertEquals("axes", pick("Rotating a cube in 3D", "A cube turns around the x y z axes in three-dimensional space.", "", "math").threeKind());
    }

    @Test void processAndCodeScenesStayOnTheNativeRenderer() {
        assertEquals(Engine.NATIVE_DIAGRAM, pick("The race condition", "Two threads read the counter, add one and write back.", "count++", "technology").engine());
        assertEquals(Engine.NATIVE_DIAGRAM, pick("An API request", "The browser sends a request and the server queries the database.", "", "technology").engine());
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Compound interest", "Interest earns interest.", "growth chart", "finance").engine());
    }

    @Test void matchingIsWholeWordSoSubstringsDoNotTrigger() {
        var d = pick("A rapid answer", "The capital block clocked a rapid answer.", "", "general");
        assertEquals(Engine.NATIVE_DIAGRAM, d.engine());
        assertTrue(d.hits().isEmpty());
    }

    @Test void manimNeedsASpecAndRiveNeedsAFile() {
        assertEquals(Engine.MANIM, pick("Pythagoras", "The squares add up.", "triangle", "", "math", Set.of("formula"), true, false, 0, Capabilities.all()).engine());
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Pythagoras", "The squares add up.", "triangle", "", "math", Set.of("formula"), true, false, 0, Capabilities.nativeOnly()).engine());
        assertEquals(Engine.RIVE, pick("Meet Rusty the mascot", "Our character waves.", "mascot", "", "technology", Set.of(), false, true, 0, Capabilities.all()).engine());
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Meet Rusty the mascot", "Our character waves.", "mascot", "", "technology", Set.of(), false, false, 0, Capabilities.all()).engine());
    }

    @Test void anEngineThatIsDownIsNeverChosen() {
        var caps = new Capabilities(false, false, false, false);
        assertEquals(Engine.NATIVE_DIAGRAM, pick("How the planets orbit the sun", "The planets orbit the sun.", "solar system", "", "astronomy", Set.of(), false, false, 0, caps).engine());
    }

    @Test void kidsLessonsAndPlanDevicesKeepTheNativeRenderer() {
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Planets for kids", "The planets go round the sun.", "planets", "", "astronomy", Set.of(), false, false, 1, Capabilities.all()).engine());
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Sorting", "Compare neighbours step by step in a loop.", "", "for(i...)", "technology", Set.of("datastruct"), false, false, 0, Capabilities.all()).engine());
    }

    @Test void commonPlanDevicesDoNotForceNative() {
        // photo / actor / analogy / layers / note appear on nearly every scene of a real lesson; they must not decide the engine
        var d = pick("Enter Multithreading", "We hire a team! This is multithreading. Instead of one worker, we've three working in parallel.", "", "",
                "Java / Programming", Set.of("analogy", "tile", "photo", "actor"), false, false, 0, Capabilities.all());
        assertEquals(Engine.THREE_JS, d.engine());
        assertEquals("lanes", d.threeKind());
    }

    @Test void aLayeredStackGetsTheLayersTemplate() {
        var d = pick("The Java Thread Model", "Under the hood, Java manages this using a layered approach. Your app sits at the top and the JVM handles the threads.", "", "",
                "Java / Programming", Set.of("layers"), false, false, 0, Capabilities.all());
        assertEquals(Engine.THREE_JS, d.engine());
        assertEquals("layers", d.threeKind());
    }

    @Test void aSpecialisedDeviceStillKeepsTheNativeRenderer() {
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Coding the Team", "We create a class that extends Thread and call start to run it in parallel.", "", "class A extends Thread {}",
                "Java / Programming", Set.of("code", "button"), false, false, 0, Capabilities.all()).engine());
    }
}
