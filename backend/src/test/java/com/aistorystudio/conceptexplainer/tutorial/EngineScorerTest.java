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


    @Test void stackLessonsUseARealThreeDimensionalStackTemplate() {
        var d = pick("Real World LIFO", "Imagine a stack of plates. Push adds an item to the top and pop removes the top item. Last in, first out.", "", "",
                "Java / Programming", Set.of("datastruct", "analogy", "tile"), false, false, 0, Capabilities.all());
        assertEquals(Engine.THREE_JS, d.engine());
        assertEquals("stack", d.threeKind());
    }

    @Test void aLayeredStackGetsTheLayersTemplate() {
        var d = pick("The Java Thread Model", "Under the hood, Java manages this using a layered approach. Your app sits at the top and the JVM handles the threads.", "", "",
                "Java / Programming", Set.of("layers"), false, false, 0, Capabilities.all());
        assertEquals(Engine.THREE_JS, d.engine());
        assertEquals("layers", d.threeKind());
    }

    @Test void aSpecialisedDeviceStillKeepsTheNativeRenderer() {
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Coding the Team", "We create a class that extends Thread and call start to run it in parallel.", "", "class A extends Thread {}",
                "Java / Programming", Set.of("code", "button"), false, false, 0, Capabilities.all()).engine());   // a button next to the code: not a pure code scene
    }

    @Test void orderedMovingStepsGoToMotionCanvasWhenItIsAvailable() {
        String text = "This is exactly how a standard Java program works. It follows a strict sequence. First, it takes the order, then it makes the chai, and finally, it collects the payment. If step two takes ten minutes, step three simply doesn't happen yet.";
        var d = pick("How Java Normally Works", text, "", "", "Java / Programming", Set.of("sequence"), false, false, 0, Capabilities.all());
        assertEquals(Engine.MOTION_CANVAS, d.engine());
        assertEquals("flow", d.threeKind());
        assertEquals(Engine.NATIVE_DIAGRAM, pick("How Java Normally Works", text, "", "", "Java / Programming", Set.of("sequence"), false, false, 0, new Capabilities(true, true, true, false)).engine());
    }

    @Test void aMovingStepFlowNeverReplacesACodeWindow() {
        String text = "First we write the class, then we call start, and finally the thread runs.";
        // with animated boards available the code window is TYPED IN (kind "code"), never turned into a step flow
        var d = pick("Coding", text, "", "class A extends Thread { }", "Java / Programming", Set.of("code"), false, false, 0, Capabilities.all());
        assertEquals("code", d.threeKind());
        // without them it stays on the native renderer (flow animation must not replace a code window)
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Coding", text, "", "class A extends Thread { }", "Java / Programming", Set.of("code"), false, false, 0, new Capabilities(true, true, true, true)).engine());
    }

    @Test void aLibraryAnimationIsUsedWhenItsTopicMatches() {
        var d = EngineScorer.decide(new Signals("The app freezes", "The app seems to freeze while it waits.", "", "", "technology", "", Set.of(), false, false, false, 0, true), Capabilities.all());
        assertEquals(Engine.RIVE, d.engine());
        assertEquals(Engine.NATIVE_DIAGRAM, EngineScorer.decide(new Signals("The app freezes", "The app seems to freeze while it waits.", "", "", "technology", "", Set.of(), false, false, false, 0, true), new Capabilities(true, false, true, true)).engine());
    }

    @Test void aStepperOrFlowchartDeviceMeansOrderedStepsSoMotionCanvasFitsWhenAvailable() {
        assertEquals(Engine.MOTION_CANVAS, pick("Launching a Thread", "To launch a thread, extend the Thread class, override the run method, then call start.", "", "",
                "technology", Set.of("journey"), false, false, 0, Capabilities.all()).engine());
        assertEquals(Engine.MOTION_CANVAS, pick("The CPU Scheduler", "The OS scheduler hands each thread a turn on the CPU core.", "", "",
                "technology", Set.of("flowchart"), false, false, 0, Capabilities.all()).engine());
        // without the animated-board capability, bars stay on the native renderer
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Performance Boost", "Wait time drops from 30 seconds to 12.", "", "", "technology", Set.of("bars"), false, false, 0, new Capabilities(true, true, true, true)).engine());
    }

    @Test void cardsCompareAndBarsBecomeAnimatedBoardsWhenTheRendererCanDoIt() {
        var d = pick("Performance Boost", "Wait time drops from 30 seconds to 12.", "", "", "technology", Set.of("bars"), false, false, 0, Capabilities.all());
        assertEquals(Engine.MOTION_CANVAS, d.engine());
        assertEquals("board", d.threeKind());
        assertEquals(Engine.MOTION_CANVAS, pick("The rule", "Cheaper to use means we use more.", "", "", "economics", Set.of("card", "compare"), false, false, 0, Capabilities.all()).engine());
    }

    @Test void aBoardNeedsOnlyBoardDevicesAndNeverReplacesKidsIllustrations() {
        // a code window next to cards is still a code scene, but a data structure next to cards keeps the scene native
        assertEquals("code", pick("Coding", "Here is the code and what it prints.", "", "", "technology", Set.of("card", "code"), false, false, 0, Capabilities.all()).threeKind());
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Arrays", "Here is the array.", "", "", "technology", Set.of("card", "datastruct"), false, false, 0, Capabilities.all()).engine());
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Planets", "Cards about the planets.", "", "", "science", Set.of("card"), false, false, 1, Capabilities.all()).engine());
    }

    @Test void aCodeWindowIsTypedInAsAnAnimatedClipWhenTheRendererCanDoIt() {
        var d = pick("Coding the Team", "We create a class that extends Thread and call start.", "", "class A extends Thread {}", "Java / Programming", Set.of("code"), false, false, 0, Capabilities.all());
        assertEquals(Engine.MOTION_CANVAS, d.engine());
        assertEquals("code", d.threeKind());
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Coding the Team", "We create a class that extends Thread and call start.", "", "class A extends Thread {}", "Java / Programming", Set.of("code"), false, false, 0, new Capabilities(true, true, true, true)).engine());
    }

    @Test void tilesActorsAndArrowsBecomeAnAnimatedDiagram() {
        var d = pick("What happens when you tap", "You tap the app, the app asks the server, the server asks the database.", "", "", "technology", Set.of("actor", "tile", "arrow"), false, false, 0, Capabilities.all());
        assertEquals(Engine.MOTION_CANVAS, d.engine());
        assertEquals("diagram", d.threeKind());
        // not for kids, not without the capability, and a terminal (not drawable) keeps the scene native
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Tap", "You tap.", "", "", "technology", Set.of("actor", "tile", "arrow"), false, false, 0, new Capabilities(true, true, true, true)).engine());
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Tap", "You tap.", "", "", "technology", Set.of("actor", "tile", "terminal"), false, false, 0, Capabilities.all()).engine());
    }

    @Test void formulasAndChartsGetTheirOwnAnimatedScenes() {
        var f = pick("Gravity", "The pull falls off with the square of the distance.", "", "", "physics", Set.of("formula", "label"), false, false, 0, Capabilities.all());
        assertEquals(Engine.MOTION_CANVAS, f.engine()); assertEquals("formula", f.threeKind());
        var c = pick("The trend", "Usage keeps climbing every year.", "", "", "technology", Set.of("chart", "note"), false, false, 0, Capabilities.all());
        assertEquals(Engine.MOTION_CANVAS, c.engine()); assertEquals("chart", c.threeKind());
        // a chart next to a tile is a mixed scene: native (the chart then draws on the native bars device)
        assertEquals(Engine.NATIVE_DIAGRAM, pick("Mixed", "A chart and a server.", "", "", "technology", Set.of("chart", "tile"), false, false, 0, Capabilities.all()).engine());
        assertEquals(Engine.NATIVE_DIAGRAM, pick("The trend", "Usage keeps climbing.", "", "", "technology", Set.of("chart"), false, false, 0, new Capabilities(true, true, true, true)).engine());
    }

    @Test void aSpinningEarthIsThe3DPictureForKidsButAQuizAboutItStaysAQuiz() {
        var earth = pick("Spinning Earth", "The sun does not go anywhere. The Earth is spinning, like a slow merry-go-round.", "", "", "science", Set.of("tile", "arrow"), false, false, 2, Capabilities.all());
        assertEquals(Engine.THREE_JS, earth.engine()); assertEquals("earth", earth.threeKind());
        var quiz = pick("Your turn", "What makes day and night? Yes! The Earth spins.", "", "", "science", Set.of("quiz"), false, false, 2, Capabilities.all());
        assertEquals(Engine.MOTION_CANVAS, quiz.engine()); assertEquals("quiz", quiz.threeKind());
    }

    @Test void aMascotWithSpeechBecomesATalkingMascotScene() {
        var d = pick("A big question", "Hello friends! Why does the sun go away at night?", "", "", "science", Set.of("mascot", "speech"), false, false, 2, Capabilities.all());
        assertEquals(Engine.MOTION_CANVAS, d.engine()); assertEquals("mascot", d.threeKind());
    }
}
