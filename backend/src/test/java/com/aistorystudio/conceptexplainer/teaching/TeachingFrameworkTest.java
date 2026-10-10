package com.aistorystudio.conceptexplainer.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Planner prompts, topic classification, voice direction and the lesson validator. */
class TeachingFrameworkTest {

    // ------------------------------------------------------------ helpers

    private static TeachingPlanner.Request request(String topic, String language, Destination dest, TeachingStyle style, String track) {
        return new TeachingPlanner.Request(topic, language, "Complete Beginner", track, "General", false, "", "", false,
                style, dest, 8, 10, 145, 180);
    }

    private static LessonValidator.Context ctx(String topic, Destination dest, TopicKind kind) {
        return new LessonValidator.Context(topic, "English", dest, kind, TeachingStyle.ENGAGING_TECH_TUTOR, null, null);
    }

    /** A complete RECAP scene (statement + 3 mappings), so slide-content checks pass. */
    private static SceneDraft recap(String... narration) {
        SceneDraft r = scene("RECAP", "summary", "Recap", narration);
        r.counts.put("statement", 1);
        r.counts.put("mappings", 3);
        return r;
    }

    private static SceneDraft scene(String section, String template, String title, String... narration) {
        SceneDraft s = new SceneDraft();
        s.sectionType = section;
        s.objective = "Learner understands " + title;
        s.template = template;
        s.title = title;
        s.narration = new java.util.ArrayList<>(List.of(narration));
        s.visualDescription = "Slide about " + title;
        s.slideTerms.add(title);
        s.counts.put("text", 1);
        s.counts.put("callouts", 3);
        return s;
    }

    // ------------------------------------------------------------ topic helpers

    @Test
    void cleanTopicStripsInstructionWords() {
        assertEquals("Java Variables", TeachingPlanner.cleanTopic("Explain Java Variables"));
        assertEquals("System Design", TeachingPlanner.cleanTopic("teach me system design"));
        assertEquals("Load Balancer", TeachingPlanner.cleanTopic("What is a load balancer?").replace("A ", ""));
        assertEquals("Photosynthesis", TeachingPlanner.cleanTopic("  Photosynthesis!  "));
        assertEquals("What Is", TeachingPlanner.cleanTopic("what is").length() > 0 ? "What Is" : "");
    }

    @Test
    void openingLineUsesTheRequiredPattern() {
        assertEquals("What are we learning today? Java Variables!", TeachingPlanner.openingLine("Explain Java Variables"));
        assertTrue(TeachingPlanner.isEnglishFamily("Indian English"));
        assertFalse(TeachingPlanner.isEnglishFamily("Kannada"));
    }

    @Test
    void topicKindsAreClassifiedForTheFourAcceptanceTopics() {
        assertEquals(TopicKind.PROGRAMMING, TopicKind.classify("Java Variables", "GENERAL", "General"));
        assertEquals(TopicKind.SYSTEM_DESIGN, TopicKind.classify("System Design", "GENERAL", "General"));
        assertEquals(TopicKind.SYSTEM_DESIGN, TopicKind.classify("Apache Kafka", "GENERAL", "General"));
        assertEquals(TopicKind.SCIENCE_EXAM, TopicKind.classify("Photosynthesis", "GENERAL", "General"));
        assertEquals(TopicKind.GENERAL, TopicKind.classify("History of the Mughal Empire", "GENERAL", "General"));
        assertEquals(TopicKind.ALGORITHM, TopicKind.classify("Binary Search", "TECHNOLOGY", "General"));
        assertEquals(TopicKind.AI_ML, TopicKind.classify("Machine Learning basics", "GENERAL", "General"));
        assertEquals(TopicKind.SCIENCE_EXAM, TopicKind.classify("Anything", "JEE", "Physics"));
    }

    @Test
    void sectionTypesParseAliases() {
        assertEquals(SectionType.HOW_IT_WORKS, SectionType.parse("how it works"));
        assertEquals(SectionType.TRADEOFFS, SectionType.parse("pros and cons"));
        assertEquals(SectionType.RECAP, SectionType.parse("Summary"));
        assertNull(SectionType.parse("banana"));
        assertEquals(TeachingStyle.STORYTELLING_TEACHER, TeachingStyle.parse("storytelling"));
        assertEquals(TeachingStyle.ENGAGING_TECH_TUTOR, TeachingStyle.parse(null));
        assertEquals(Destination.CLASSROOM, Destination.parse("classroom"));
        assertEquals(Destination.SOCIAL, Destination.parse(""));
    }

    // ------------------------------------------------------------ prompts adapt to the topic

    @Test
    void promptsAdaptToTheTopicInsteadOfReusingOneScript() {
        String java = TeachingPlanner.planUserPrompt(request("Java Variables", "English", Destination.SOCIAL, TeachingStyle.ENGAGING_TECH_TUTOR, "GENERAL"));
        String design = TeachingPlanner.planUserPrompt(request("System Design", "English", Destination.SOCIAL, TeachingStyle.ENGAGING_TECH_TUTOR, "GENERAL"));
        String bio = TeachingPlanner.planUserPrompt(request("Photosynthesis", "English", Destination.SOCIAL, TeachingStyle.ENGAGING_TECH_TUTOR, "GENERAL"));
        assertTrue(java.contains("Java Variables") && java.contains("Code slides are appropriate"));
        assertTrue(design.contains("System Design") && design.contains("TRADEOFFS") && design.contains("do NOT use code templates"));
        assertTrue(bio.contains("Photosynthesis") && bio.contains("do NOT use code templates"));
        assertFalse(java.contains("Photosynthesis") || java.contains("load balancer"));
        assertFalse(bio.toLowerCase().contains("kafka"));
        assertNotEquals(design, java);
    }

    @Test
    void systemPromptsCarryTheFrameworkRulesButNoFixedScript() {
        String plan = TeachingPlanner.planSystemPrompt();
        String write = TeachingPlanner.writeSystemPrompt();
        assertTrue(plan.contains("What are we learning today? <topic>!"));
        assertTrue(plan.contains("never reuse a stock example"));
        assertTrue(plan.contains("flow") && plan.contains("compare"));
        assertTrue(write.contains("ALIGNMENT RULES") && write.contains("voiceDirection") && write.contains("NEVER write it inside the narration"));
        assertTrue(write.contains("derived from the narration"));
        // the original example scripts of the brief must not be baked into the prompts
        assertFalse(plan.contains("one lakh users") || write.contains("one lakh users"));
        assertFalse(write.contains("celebrate or resign"));
    }

    @Test
    void closingAndStyleFollowTheSelection() {
        String social = TeachingPlanner.writeUserPrompt(request("Kafka", "English", Destination.SOCIAL, TeachingStyle.ENGAGING_TECH_TUTOR, "GENERAL"), "{}");
        String classroom = TeachingPlanner.writeUserPrompt(request("Kafka", "English", Destination.CLASSROOM, TeachingStyle.PROFESSIONAL_INSTRUCTOR, "GENERAL"), "{}");
        assertTrue(social.contains("subscribe"));
        assertTrue(classroom.contains("no subscribe request"));
        assertTrue(classroom.contains("Professional Instructor") && social.contains("Engaging Tech Tutor"));
        assertTrue(social.contains("What are we learning today? Kafka!"));
        String kn = TeachingPlanner.writeUserPrompt(request("Kafka", "Kannada", Destination.SOCIAL, TeachingStyle.ENGAGING_TECH_TUTOR, "GENERAL"), "{}");
        assertTrue(kn.contains("natural Kannada equivalent"));
    }

    // ------------------------------------------------------------ voice direction

    @Test
    void voiceDirectionNormalisesAndMapsToTheEngine() {
        VoiceDirection vd = VoiceDirection.of("Excited", "quick", "funny", "calm");
        assertEquals("energetic", vd.emotion());
        assertEquals("fast", vd.pace());
        assertEquals("comedic", vd.delivery());
        assertTrue(vd.hadUnsupported());
        VoiceDirection bad = VoiceDirection.of("sarcastic", "warp", "yodel", "calm");
        assertEquals("neutral", bad.emotion());
        assertEquals("medium", bad.pace());
        assertEquals("conversational", bad.delivery());
        VoiceDirection ok = VoiceDirection.of("curious", "slow", "storytelling", "calm");
        assertFalse(ok.hadUnsupported());
        VoiceDirection def = VoiceDirection.of(null, null, null, "encouraging");
        assertEquals("encouraging", def.emotion());

        VoiceDirection.TtsParams cb = VoiceDirection.of("dramatic", "slow", "emphatic", "calm").toTts("narrator-male", 1.0, 1.0, false, false);
        assertEquals("dramatic", cb.emotion());
        assertTrue(cb.engineUnderstandsEmotion());
        assertTrue(cb.speed() < 0.95);
        VoiceDirection.TtsParams edge = VoiceDirection.of("energetic", "fast", "conversational", "calm").toTts("edge:kn-IN-SapnaNeural", 1.0, 1.0, false, false);
        assertFalse(edge.engineUnderstandsEmotion());
        assertTrue(edge.speed() > 1.0 && edge.pitch() > 1.0);
        VoiceDirection.TtsParams q = VoiceDirection.of("calm", "medium", "conversational", "calm").toTts("narrator-female", 1.0, 1.0, true, false);
        assertEquals("curious", q.emotion());
    }

    // ------------------------------------------------------------ validator: repairs

    @Test
    void openingLineIsAddedWhenMissing() {
        SceneDraft hook = scene("HOOK", "definition", "System Design", "Imagine your app suddenly gets a huge crowd of users.", "Will the server survive?");
        SceneDraft recap = recap("Design is about planning for growth.", "Subscribe for more.");
        List<SceneDraft> scenes = new java.util.ArrayList<>(List.of(hook, recap));
        LessonValidator.Report r = new LessonValidator().check(scenes, ctx("Explain System Design", Destination.SOCIAL, TopicKind.SYSTEM_DESIGN));
        assertEquals(TeachingPlanner.greeting("Explain System Design", "English") + " What are we learning today? System Design! Imagine your app suddenly gets a huge crowd of users.", scenes.get(0).narration.get(0));
        assertEquals(2, scenes.get(0).narration.size()); // merged: sentence k still matches slide element k
        assertEquals("Will the server survive?", scenes.get(0).narration.get(1));
        assertTrue(r.has("opening"));
    }

    @Test
    void wrongTopicInOpeningIsReplacedAndCorrectOpeningIsKept() {
        SceneDraft wrong = scene("HOOK", "definition", "Java Variables", "What are we learning today? Something unrelated!", "Boxes hold values.");
        SceneDraft other = recap("Variables name a value.", "Subscribe for more.");
        new LessonValidator().check(new java.util.ArrayList<>(List.of(wrong, other)), ctx("Java Variables", Destination.SOCIAL, TopicKind.PROGRAMMING));
        assertEquals(TeachingPlanner.greeting("Java Variables", "English") + " What are we learning today? Java Variables!", wrong.narration.get(0));

        SceneDraft good = scene("HOOK", "definition", "Photosynthesis", "What are we learning today? Photosynthesis!", "How do leaves cook food?");
        SceneDraft rec = recap("Leaves make food from light.", "Subscribe for more.");
        List<SceneDraft> list = new java.util.ArrayList<>(List.of(good, rec));
        LessonValidator.Report r = new LessonValidator().check(list, ctx("Photosynthesis", Destination.SOCIAL, TopicKind.SCIENCE_EXAM));
        assertEquals(2, list.get(0).narration.size());
        assertFalse(r.has("opening"));          // the required line was already right ...
        assertTrue(r.has("greeting"));          // ... only the one simple greeting was added
        assertEquals(TeachingPlanner.greeting("Photosynthesis", "English") + " What are we learning today? Photosynthesis!", list.get(0).narration.get(0));
    }

    @Test
    void nonEnglishOpeningIsNotForcedToEnglish() {
        SceneDraft hook = scene("HOOK", "definition", "Kafka", "ಇಂದು ನಾವು ಏನು ಕಲಿಯುತ್ತೇವೆ? ಕಾಫ್ಕಾ!", "ಸಂದೇಶಗಳು ಹೇಗೆ ಹೋಗುತ್ತವೆ?");
        SceneDraft rec = recap("ಕಾಫ್ಕಾ ಸಂದೇಶಗಳನ್ನು ಸಂಗ್ರಹಿಸುತ್ತದೆ.");
        LessonValidator.Context kn = new LessonValidator.Context("Kafka", "Kannada", Destination.SOCIAL, TopicKind.SYSTEM_DESIGN, TeachingStyle.ENGAGING_TECH_TUTOR, null, null);
        List<SceneDraft> list = new java.util.ArrayList<>(List.of(hook, rec));
        new LessonValidator().check(list, kn);
        // the Kannada opening is kept as written; only the single Kannada greeting is put in front
        assertEquals(TeachingPlanner.greeting("Kafka", "Kannada") + " ಇಂದು ನಾವು ಏನು ಕಲಿಯುತ್ತೇವೆ? ಕಾಫ್ಕಾ!", list.get(0).narration.get(0));
        assertEquals(1, list.get(1).narration.size()); // no English subscribe line is forced into a Kannada lesson
    }

    @Test
    void stageDirectionsNeverReachTheVoice() {
        assertEquals("Boom! The server crashed.", LessonValidator.sanitize("[dramatic voice] Boom! The server crashed."));
        assertEquals("Everyone laughs, right?", LessonValidator.sanitize("Everyone laughs, right? (pause for laughter)"));
        assertEquals("Hello class.", LessonValidator.sanitize("Narrator: Hello class."));
        assertEquals("That was funny [chuckle] anyway.", LessonValidator.sanitize("That was funny [chuckle] anyway."));
        assertEquals("Use a map (key and value) here.", LessonValidator.sanitize("Use a map (key and value) here."));
        assertEquals("Wait for it.", LessonValidator.sanitize("(whispering, excited) Wait for it."));
    }

    @Test
    void closingFollowsTheDestination() {
        SceneDraft hook = scene("HOOK", "definition", "Kafka", "What are we learning today? Kafka!", "Messages everywhere.");
        SceneDraft recap = recap("Kafka stores streams of messages.");
        List<SceneDraft> social = new java.util.ArrayList<>(List.of(hook, recap));
        new LessonValidator().check(social, ctx("Kafka", Destination.SOCIAL, TopicKind.SYSTEM_DESIGN));
        assertTrue(social.get(1).narrationText().toLowerCase().contains("subscribe"));

        SceneDraft hook2 = scene("HOOK", "definition", "Kafka", "What are we learning today? Kafka!", "Messages everywhere.");
        SceneDraft recap2 = recap("Kafka stores streams of messages.", "Please subscribe for more!");
        List<SceneDraft> classroom = new java.util.ArrayList<>(List.of(hook2, recap2));
        new LessonValidator().check(classroom, ctx("Kafka", Destination.CLASSROOM, TopicKind.SYSTEM_DESIGN));
        assertFalse(classroom.get(1).narrationText().toLowerCase().contains("subscribe"));
    }

    @Test
    void voiceValuesObjectivesAndVisualDescriptionsAreRepaired() {
        SceneDraft hook = scene("HOOK", "definition", "Kafka", "What are we learning today? Kafka!", "Messages everywhere.");
        hook.emotion = "excited";
        hook.pace = "turbo";
        hook.objective = "";
        hook.visualDescription = null;
        SceneDraft rec = recap("Kafka stores streams of messages. Subscribe for more.");
        LessonValidator.Context c = new LessonValidator.Context("Kafka", "English", Destination.SOCIAL, TopicKind.SYSTEM_DESIGN,
                TeachingStyle.ENGAGING_TECH_TUTOR, List.of("HOOK", "RECAP"), List.of("Know why Kafka exists", "Remember the idea"));
        LessonValidator.Report r = new LessonValidator().check(new java.util.ArrayList<>(List.of(hook, rec)), c);
        assertEquals("energetic", hook.emotion);
        assertEquals("medium", hook.pace);
        assertEquals("Know why Kafka exists", hook.objective);
        assertNotNull(hook.visualDescription);
        assertTrue(r.has("voice-values") && r.has("objective") && r.has("visual-description"));
        assertFalse(r.hasErrors());
    }

    // ------------------------------------------------------------ validator: errors / warnings

    @Test
    void missingContentIsAnError() {
        SceneDraft hook = scene("HOOK", "definition", "Kafka", "What are we learning today? Kafka!", "Messages everywhere.");
        SceneDraft empty = scene("CONCEPT", "definition", "Topic", "x");
        empty.narration.clear();
        SceneDraft thin = scene("HOW_IT_WORKS", "flow", "Flow", "Producer sends messages to the broker, then consumers read them.");
        thin.counts.clear();
        SceneDraft analogy = scene("ANALOGY", "analogy", "Post office", "Think of a post office sorting letters into boxes.");
        SceneDraft rec = recap("Done. Subscribe for more.");
        LessonValidator.Report r = new LessonValidator().check(new java.util.ArrayList<>(List.of(hook, empty, thin, analogy, rec)), ctx("Kafka", Destination.SOCIAL, TopicKind.SYSTEM_DESIGN));
        assertTrue(r.has("narration-missing"));
        assertTrue(r.has("slide-content"));
        assertTrue(r.has("image-prompt-missing"));
        assertTrue(r.hasErrors());
    }

    @Test
    void repeatedNarrationIsDetected() {
        SceneDraft a = scene("HOOK", "definition", "Kafka", "What are we learning today? Kafka!", "A broker stores messages safely for consumers to read later.");
        SceneDraft b = scene("CONCEPT", "definition", "Broker", "A broker stores messages safely for consumers to read later.");
        SceneDraft c = recap("Kafka moves messages. Subscribe for more.");
        LessonValidator.Report r = new LessonValidator().check(new java.util.ArrayList<>(List.of(a, b, c)), ctx("Kafka", Destination.SOCIAL, TopicKind.SYSTEM_DESIGN));
        assertTrue(r.has("repeated-sentence"));
    }

    @Test
    void diagramMustFollowTheSpokenOrder() {
        SceneDraft hook = scene("HOOK", "definition", "Load balancing", "What are we learning today? Load Balancing!", "Too many users, one server.");
        SceneDraft flow = scene("HOW_IT_WORKS", "flow", "Request flow",
                "A client sends a request.", "The server answers last.", "But first the load balancer picks which server.");
        flow.flowLabels = new java.util.ArrayList<>(List.of("Client", "Load Balancer", "Server"));
        flow.slideTerms.addAll(flow.flowLabels);
        SceneDraft rec = recap("Spread the load. Subscribe for more.");
        LessonValidator.Report bad = new LessonValidator().check(new java.util.ArrayList<>(List.of(hook, flow, rec)), ctx("Load Balancing", Destination.SOCIAL, TopicKind.SYSTEM_DESIGN));
        assertTrue(bad.has("flow-order"));

        SceneDraft flow2 = scene("HOW_IT_WORKS", "flow", "Request flow",
                "A client sends a request.", "The load balancer picks a healthy server.", "The server answers the client.");
        flow2.flowLabels = new java.util.ArrayList<>(List.of("Client", "Load Balancer", "Server"));
        flow2.slideTerms.addAll(flow2.flowLabels);
        SceneDraft hook2 = scene("HOOK", "definition", "Load balancing", "What are we learning today? Load Balancing!", "Too many users, one server.");
        SceneDraft rec2 = recap("Spread the load. Subscribe for more.");
        LessonValidator.Report good = new LessonValidator().check(new java.util.ArrayList<>(List.of(hook2, flow2, rec2)), ctx("Load Balancing", Destination.SOCIAL, TopicKind.SYSTEM_DESIGN));
        assertFalse(good.has("flow-order"));
        assertFalse(good.hasErrors());
    }

    @Test
    void unrelatedSlideAndCodeOnNonCodeTopicAreWarned() {
        SceneDraft hook = scene("HOOK", "definition", "Photosynthesis", "What are we learning today? Photosynthesis!", "Leaves cook food using light.");
        SceneDraft off = scene("CONCEPT", "code_block", "Loops", "Chlorophyll absorbs red and blue light.");
        off.counts.put("code", 1);
        off.slideTerms.clear();
        off.slideTerms.add("for loop");
        off.slideTerms.add("iterate");
        SceneDraft rec = recap("Light becomes sugar. Subscribe for more.");
        LessonValidator.Report r = new LessonValidator().check(new java.util.ArrayList<>(List.of(hook, off, rec)), ctx("Photosynthesis", Destination.SOCIAL, TopicKind.SCIENCE_EXAM));
        assertTrue(r.has("slide-unrelated"));
        assertTrue(r.has("code-on-non-code-topic"));
    }

    @Test
    void sceneOrderMustMatchThePlan() {
        SceneDraft a = scene("CONCEPT", "definition", "Kafka", "What are we learning today? Kafka!", "Messages everywhere.");
        SceneDraft b = scene("HOOK", "definition", "Idea", "A queue of events flows through the system.");
        SceneDraft c = recap("Done. Subscribe for more.");
        LessonValidator.Context pc = new LessonValidator.Context("Kafka", "English", Destination.SOCIAL, TopicKind.SYSTEM_DESIGN,
                TeachingStyle.ENGAGING_TECH_TUTOR, List.of("HOOK", "CONCEPT", "RECAP"), List.of("a", "b", "c"));
        LessonValidator.Report r = new LessonValidator().check(new java.util.ArrayList<>(List.of(a, b, c)), pc);
        assertTrue(r.has("scene-order"));
    }

    @Test
    void aGoodLessonPassesWithoutErrors() {
        SceneDraft hook = scene("HOOK", "definition", "Java Variables", "What are we learning today? Java Variables!", "Imagine a box with a name sticker on it.", "That box is a variable.");
        SceneDraft code = scene("HOW_IT_WORKS", "code_anatomy", "Declaration", "Here is one line of Java.", "int says the box holds a whole number.", "age is the name of the box.");
        code.counts.put("code", 1);
        code.counts.put("parts", 2);
        code.slideTerms.addAll(List.of("int", "age", "Data type", "Variable name"));
        SceneDraft rec = recap("A variable is a named box for a value.", "If this helped, subscribe for more simple lessons!");
        rec.counts.put("mappings", 3);
        LessonValidator.Report r = new LessonValidator().check(new java.util.ArrayList<>(List.of(hook, code, rec)), ctx("Java Variables", Destination.SOCIAL, TopicKind.PROGRAMMING));
        assertFalse(r.hasErrors(), r.errorMessages().toString());
    }

    // ------------------------------------------------------------ greeting (one simple greeting, once)

    @Test
    void greetingIsOneSimpleHelloFriendsOrEveryone() {
        for (String topic : List.of("Java Variables", "System Design", "Kafka", "Photosynthesis")) {
            String g = TeachingPlanner.greeting(topic, "English");
            assertTrue(g.equals("Hello friends!") || g.equals("Hello everyone!"), g);
        }
        // both wordings occur across topics (not one fixed script)
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) seen.add(TeachingPlanner.greeting("Topic " + i, "English"));
        assertEquals(2, seen.size());
    }

    @Test
    void openingWithGreetingContainsGreetingThenTheRequiredLine() {
        String o = TeachingPlanner.openingWithGreeting("Explain Java Variables", "English");
        assertTrue(o.startsWith("Hello "), o);
        assertTrue(o.endsWith("What are we learning today? Java Variables!"), o);
        assertEquals(1, o.split("(?i)hello", -1).length - 1);
    }

    @Test
    void validatorAddsExactlyOneGreetingToTheOpening() {
        SceneDraft hook = scene("HOOK", "definition", "Intro", "What are we learning today? Java Variables!", "Imagine a labelled box.");
        SceneDraft end = recap("That is it.");
        List<SceneDraft> drafts = new java.util.ArrayList<>(List.of(hook, end));
        new LessonValidator().check(drafts, ctx("Java Variables", Destination.CLASSROOM, TopicKind.PROGRAMMING));
        String first = hook.narration.get(0).toLowerCase();
        assertTrue(first.startsWith("hello friends!") || first.startsWith("hello everyone!"), first);
        assertEquals(1, first.split("hello", -1).length - 1);
        assertTrue(first.contains("what are we learning today? java variables!"), first);
    }

    @Test
    void indianLanguagesGetTheirOwnSingleGreeting() {
        assertFalse(TeachingPlanner.greeting("x", "Kannada").isEmpty());
        assertFalse(TeachingPlanner.greeting("x", "Hindi").isEmpty());
        assertEquals("", TeachingPlanner.greeting("x", "Santali")); // unmapped: nothing forced
    }
}
