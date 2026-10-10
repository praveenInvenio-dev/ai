package com.aistorystudio.conceptexplainer.teaching;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt engineering for the adaptive teaching framework. Two separate prompts:
 * <ol>
 *   <li><b>Plan</b> - audience, hook, analogy, real-world example, humour opportunities, learning objective per scene,
 *       section order chosen for THIS topic (never a fixed template).</li>
 *   <li><b>Write</b> - turns that plan into spoken narration, and derives each scene's slide, visual description,
 *       image prompt and voice direction FROM that scene's narration and objective.</li>
 * </ol>
 * Pure string logic; no network, no framework.
 */
public final class TeachingPlanner {

    private TeachingPlanner() { }

    /** Everything the prompts need. */
    public record Request(String topic, String language, String difficulty, String track, String subject, boolean examFocus,
                          String instructions, String trackNotes, boolean deep, TeachingStyle style, Destination destination,
                          int minScenes, int maxScenes, int minWords, int maxWords) {
        public String cleanTopic() { return TeachingPlanner.cleanTopic(topic); }
        public TopicKind kind() { return TopicKind.classify(topic, track, subject); }
    }

    // ------------------------------------------------------------------ topic helpers

    private static final Pattern LEAD = Pattern.compile(
            "^\\s*(please\\s+)?(can you\\s+)?(explain|teach me|teach|tell me about|describe|learn|what is|what are|what's|how does|how do|introduction to|intro to|basics of|about)\\s+(about\\s+|the\\s+)?",
            Pattern.CASE_INSENSITIVE);

    /** "Explain Java Variables" -> "Java Variables"; all-lowercase Latin text is title-cased. */
    public static String cleanTopic(String topic) {
        if (topic == null) return "";
        String t = topic.trim();
        Matcher m = LEAD.matcher(t);
        if (m.find() && m.end() < t.length()) t = t.substring(m.end());
        t = t.replaceAll("[\\s?!.,;:]+$", "").replaceAll("^[\"'“”‘’]+|[\"'“”‘’]+$", "").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return topic.trim();
        if (t.equals(t.toLowerCase(Locale.ROOT)) && t.chars().anyMatch(ch -> ch >= 'a' && ch <= 'z')) {
            StringBuilder sb = new StringBuilder();
            for (String w : t.split(" ")) {
                if (sb.length() > 0) sb.append(' ');
                boolean small = List.of("a", "an", "the", "of", "in", "and", "to", "vs", "for", "on").contains(w) && sb.length() > 0;
                sb.append(small || w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
            }
            t = sb.toString();
        }
        return t;
    }

    /** The required topic-introducing line (without the greeting). */
    public static String openingLine(String topic) {
        return cleanTopic(topic) + ". Let's break down what it is, why it matters, and exactly how it works.";
    }

    /**
     * ONE simple greeting at the very start of the lesson - "Hello friends!" or "Hello everyone!"
     * (the wording alternates per topic so lessons do not all sound identical), or the natural
     * equivalent in the main Indian languages. Returns "" for languages without a mapped greeting:
     * nothing is forced where we would have to guess the wording.
     */
    public static String greeting(String topic, String language) {
        String l = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        boolean friends = (topic == null ? 0 : (topic.hashCode() & 0x7fffffff)) % 2 == 0;
        if (isEnglishFamily(language)) return friends ? "Hello friends!" : "Hello everyone!";
        return switch (l) {
            case "hindi" -> friends ? "\u0928\u092E\u0938\u094D\u0924\u0947 \u0926\u094B\u0938\u094D\u0924\u094B\u0902!" : "\u0928\u092E\u0938\u094D\u0924\u0947 \u0938\u092C\u0915\u094B!";
            case "kannada" -> friends ? "\u0CA8\u0CAE\u0CB8\u0CCD\u0C95\u0CBE\u0CB0 \u0CB8\u0CCD\u0CA8\u0CC7\u0CB9\u0CBF\u0CA4\u0CB0\u0CC7!" : "\u0CA8\u0CAE\u0CB8\u0CCD\u0C95\u0CBE\u0CB0 \u0C8E\u0CB2\u0CCD\u0CB2\u0CB0\u0CBF\u0C97\u0CC2!";
            case "tamil" -> friends ? "\u0BB5\u0BA3\u0B95\u0BCD\u0B95\u0BAE\u0BCD \u0BA8\u0BA3\u0BCD\u0BAA\u0BB0\u0BCD\u0B95\u0BB3\u0BC7!" : "\u0BB5\u0BA3\u0B95\u0BCD\u0B95\u0BAE\u0BCD \u0B8E\u0BB2\u0BCD\u0BB2\u0BCB\u0BB0\u0BC1\u0B95\u0BCD\u0B95\u0BC1\u0BAE\u0BCD!";
            case "telugu" -> friends ? "\u0C28\u0C2E\u0C38\u0C4D\u0C15\u0C3E\u0C30\u0C02 \u0C2E\u0C3F\u0C24\u0C4D\u0C30\u0C41\u0C32\u0C3E\u0C30\u0C3E!" : "\u0C28\u0C2E\u0C38\u0C4D\u0C15\u0C3E\u0C30\u0C02 \u0C05\u0C02\u0C26\u0C30\u0C3F\u0C15\u0C40!";
            case "malayalam" -> friends ? "\u0D28\u0D2E\u0D38\u0D4D\u0D15\u0D3E\u0D30\u0D02 \u0D15\u0D42\u0D1F\u0D4D\u0D1F\u0D41\u0D15\u0D3E\u0D30\u0D47!" : "\u0D28\u0D2E\u0D38\u0D4D\u0D15\u0D3E\u0D30\u0D02 \u0D0E\u0D32\u0D4D\u0D32\u0D3E\u0D35\u0D7C\u0D15\u0D4D\u0D15\u0D41\u0D02!";
            case "marathi" -> friends ? "\u0928\u092E\u0938\u094D\u0915\u093E\u0930 \u092E\u093F\u0924\u094D\u0930\u093E\u0902\u0928\u094B!" : "\u0928\u092E\u0938\u094D\u0915\u093E\u0930 \u0938\u0930\u094D\u0935\u093E\u0902\u0928\u093E!";
            case "bengali" -> "\u09A8\u09AE\u09B8\u09CD\u0995\u09BE\u09B0 \u09AC\u09A8\u09CD\u09A7\u09C1\u09B0\u09BE!";
            case "gujarati" -> "\u0AA8\u0AAE\u0AB8\u0ACD\u0AA4\u0AC7 \u0AAE\u0ABF\u0AA4\u0ACD\u0AB0\u0ACB!";
            default -> "";
        };
    }

    /** Greeting + required line, e.g. "Hello friends! What are we learning today? Java Variables!" */
    public static String openingWithGreeting(String topic, String language) {
        String g = greeting(topic, language);
        return g.isEmpty() ? openingLine(topic) : g + " " + openingLine(topic);
    }

    public static boolean isEnglishFamily(String language) {
        String l = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        return l.isEmpty() || l.equals("english") || l.equals("en") || l.startsWith("english ") || l.equals("indian english") || l.equals("hinglish") || l.startsWith("en-");
    }

    // ------------------------------------------------------------------ plan prompt

    public static String planSystemPrompt() {
        return """
                You are the head of curriculum design for a premium explainer-video studio. Plan a short lesson that a student understands
                after one viewing and enjoys watching. You plan ONLY - you do not write the narration yet.

                Every lesson follows the same teaching pattern, but the content, analogies, jokes and structure must be invented fresh for
                the actual topic (never reuse a stock example or joke):
                1. HOOK - opens with ONE short greeting ('Hello friends!' or 'Hello everyone!') and then 'What are we learning today? <topic>!', then a topic-specific curiosity hook or relatable situation.
                2. ANALOGY - a relatable everyday situation BEFORE any difficult term. Pick it for this topic (daily life, classroom, office, food
                   delivery, shopping, banking, travel, cricket, traffic, queues, family chat ...). Do not force Indian references where they do
                   not fit. The analogy must be accurate - it may not teach something technically wrong.
                3. CONCEPT - what it is, why it exists, what problem it solves, in simple words; terminology only when needed.
                4. HOW_IT_WORKS - a step-by-step walkthrough (components, order of events, data flow). Only components relevant to the topic.
                5. EXAMPLE / REAL_WORLD - a practical example. Real products may be used as illustrations; never claim a company's exact internal
                   design without evidence - say 'a simplified version of'.
                6. TRADEOFFS / MISTAKES - benefits, limits, common mistakes, cost or performance effects, when another approach is better -
                   ONLY when meaningful for this topic.
                7. PRACTICE - a short question or mini challenge (only when it can be answered from the lesson).
                8. RECAP - concise takeaways, one memorable line, optionally what to learn next.
                Select, combine, omit or reorder these sections to suit the topic. Programming concepts want code and common mistakes;
                architecture wants a request/data flow and trade-offs; algorithms want an analogy, a walkthrough and complexity;
                AI topics want intuition, applications and limitations; general topics want an analogy, steps, an example and a check question.

                Each scene teaches ONE idea and gets a learningObjective ('after this scene the learner can ...'). The slide template for every
                scene is chosen now so image/diagram/slide and narration always describe the same thing. Templates:
                definition, analogy, analogy_code, code_anatomy, table, code_visual, code_block, example_list, checklist, summary,
                flow (ordered steps / data flow, 3-6 nodes), compare (advantages vs limits, two columns).
                Code templates ONLY for programming topics. Use 'flow' for any sequence, 'compare' for trade-offs.

                Return JSON only:
                {"title":"...","summary":"...","audience":"...","hook":"the opening curiosity line idea","analogy":"the analogy and why it is accurate",
                 "realWorldExample":"...","humorIdeas":["tiny, topic-specific, optional"],
                 "scenes":[{"sectionType":"HOOK|ANALOGY|CONCEPT|HOW_IT_WORKS|EXAMPLE|REAL_WORLD|TRADEOFFS|MISTAKES|PRACTICE|RECAP",
                            "learningObjective":"...","template":"...","keyIdea":"the single fact this scene states","diagram":"what the slide/diagram shows, in order (optional)"}]}
                """;
    }

    public static String planUserPrompt(Request r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topic: ").append(r.cleanTopic()).append("  (as typed: ").append(r.topic()).append(")");
        sb.append("\nTopic family: ").append(r.kind().label());
        sb.append("\nLearning track: ").append(nz(r.track(), "GENERAL")).append(" | Subject: ").append(nz(r.subject(), "General")).append(" | Exam focus: ").append(r.examFocus());
        sb.append("\nLanguage: ").append(r.language()).append("\nAudience difficulty: ").append(r.difficulty());
        sb.append("\nTeaching style: ").append(r.style().label()).append(" - ").append(r.style().promptFragment());
        sb.append("\nDestination: ").append(r.destination().label());
        sb.append("\nSuggested sections for this kind of topic (adapt freely): ");
        for (SectionType s : r.kind().suggestedSections()) sb.append(s.name()).append(' ');
        sb.append("\nScenes: between ").append(r.minScenes()).append(" and ").append(r.maxScenes()).append(". Total spoken length target: ")
                .append(r.minWords()).append("-").append(r.maxWords()).append(" words.");
        sb.append(r.kind().codeFriendly() ? "\nCode slides are appropriate for this topic." : "\nThis is not primarily a coding topic: do NOT use code templates; use flow / compare / table / definition / analogy.");
        if (r.instructions() != null && !r.instructions().isBlank()) sb.append("\nUser instructions: ").append(r.instructions());
        if (r.trackNotes() != null && !r.trackNotes().isBlank()) sb.append("\n").append(r.trackNotes());
        sb.append("\nThe first scene must be HOOK and the last must be RECAP.");
        return sb.toString();
    }

    // ------------------------------------------------------------------ write prompt

    public static String writeSystemPrompt() {
        return """
                You are an excellent human tutor AND the slide designer of a premium explainer-video studio. You receive a lesson plan and write
                the final scenes. Accuracy is mandatory; never invent facts, syntax, formulas, numbers or company internals.

                ONE SCENE PER PLAN ENTRY, SAME ORDER, same sectionType and learningObjective. Per scene you write, IN THIS ORDER:
                1. narration  - what the tutor says (array of 2-5 short spoken sentences)
                2. slide      - the slide that REINFORCES that narration (template fields below)
                3. visualDescription - one sentence describing what is on screen, matching the narration step by step
                4. imagePrompt - ONLY for analogy / analogy_code scenes: ONE concrete object or situation that the narration just described,
                   derived from the narration (e.g. the same bottle, queue, shop, box the tutor mentioned). No text, no people, no style words
                   (the art style is applied automatically).
                5. voiceDirection - {"emotion":"energetic|curious|calm|playful|dramatic|serious|encouraging|warm|neutral",
                   "pace":"slow|medium|fast","delivery":"conversational|storytelling|explanatory|emphatic|comedic|reflective"}
                   Direction is metadata for the voice engine. NEVER write it inside the narration (no '(dramatic voice)', no '[pause]').

                ALIGNMENT RULES (most important)
                - The slide, diagram and image show exactly what the narration says at that moment. Every label on the slide is a word the tutor
                  actually says in that scene. Diagram order = spoken order.
                - sentence 1 introduces the idea; each later sentence explains the NEXT element of the slide (the app reveals one element
                  per sentence), so element k appears while sentence k is spoken.
                - Never explain on one scene what another scene shows. Never repeat the same sentence in two scenes.

                NARRATION STYLE
                - OPENING: Start with the topic itself in the first words, as the reference tutorial does. No greeting, welcome, channel intro, or generic "What are we learning today?". Use a confident topic-led line that immediately establishes the key question, promise, or problem (for example: "Java variables are how a program remembers information. But where does that value go when the code runs?"). Translate this approach naturally into the requested language.
                - Narration is a connected expert-led explanation, not a sequence of slide captions. Use natural transitions and build one mental model across scenes. State the topic, establish the problem, explain the mechanism, unpack the important parts and their relationships, then demonstrate the complete flow with a concrete example before a concise takeaway.
                - For deep lessons, explain WHY and HOW, not only WHAT. Define every technical term when it first appears. Explain each component's purpose, inputs, outputs, and relationship to adjacent components where relevant. Walk through cause and effect in order. Use precise examples and avoid vague claims such as "it makes things easier" without showing how.
                - Quick lessons must still have a meaningful explanation: one clear question, one mental model, one worked example, one takeaway. Do not cram a shallow list of definitions into the short duration.
                - Spoken language should sound like a skilled human educator: varied sentence length, signposting ("Here's what happens next"), useful rhetorical questions, precise analogies and no repeated filler. Each sentence must add a new piece of understanding.
                - Humour: brief, relevant, helps memory; vary it by topic and make it natural in the lesson's language (do not translate English
                  jokes literally). Serious topics (security, data consistency, exam facts) stay clear and mostly joke-free.
                - [chuckle] [laugh] [sigh] [gasp] are allowed sparingly as performance cues. No other bracket or parenthesis stage directions.
                - Never say 'as you can see on the slide' and never read every label out loud. Narration must explain the idea; visuals provide evidence and structure.
                - No greeting or channel intro. The very first words must be the topic or its central problem. Never stack hooks, repeat the title, or waste time announcing the lesson. After the opening, deepen the explanation immediately.
                - Last scene: concise recap (+ memorable takeaway). When the destination is social video, finish with a short friendly line
                  inviting viewers to subscribe for more simple, entertaining lessons (natural wording in the lesson's language).
                  For classroom/internal destinations do not ask for subscriptions.

                SLIDE TEMPLATES - fill ONLY the fields of the chosen template; slide text <= 25 words, labels <= 4 words:
                - "definition": text, box {label, value}, callouts [3 x {label, detail}], optional formula + formulaResult (one-line REMEMBER rule); use detailed callouts to explain the mechanism, not generic benefits
                - "analogy": text, callouts [2-3 x {label, detail}], caption   (+ imagePrompt)
                - "analogy_code": text, code (3-6 lines)   (+ imagePrompt)
                - "code_anatomy": code (ONE line), parts [2-4 x {token (exact substring of code), label, detail}]
                - "table": columns [2-3], rows [3-6 x [cells]]
                - "code_visual": text, code (1-3 lines), box {label, value}, after {label, value} ONLY when a value changes, caption
                - "code_block": text (optional), code (3-8 lines)
                - "example_list": text (optional), items [3-5 x {icon, code, label}] - icon one of: bank, fuel, calendar, parking, cart, phone,
                  wallet, bag, clock, home, bulb, car, book, money, chart, lock, cloud, box, ticket, food; "code" is a short name or fact for non-code topics
                - "checklist": items [3-6 x {ok: true|false, text}]
                - "summary": statement, mappings [2-4 x {left, right}], formula, formulaResult
                - "flow": text (optional), items [3-6 x {icon, label, text}] IN SPOKEN ORDER (node k <-> sentence k); label = component name (<= 3 words),
                  text = what it does (<= 8 words). Use for request flows, pipelines, step-by-step processes. Nodes must form a meaningful causal chain; narration explains what enters, what each step does, and what leaves.
                - "compare": columns [leftTitle, rightTitle], items [{ok:true|false, text}] where ok:true lands in the left column (advantages / use it)
                  and ok:false in the right (limits / avoid it); list them in spoken order.
                Code / code_* templates ONLY for programming topics; otherwise leave "code" empty.
                Slide text and narration are in the requested language; code and technical identifiers keep their original syntax.

                Return JSON only: {"title":"...","summary":"...","scenes":[{"sectionType":"...","learningObjective":"...","template":"...","title":"...",
                "narration":["..."],"visualDescription":"...","imagePrompt":"...","voiceDirection":{...}, ...template fields}]}
                """;
    }

    public static String writeUserPrompt(Request r, String planJson) {
        return "Write the final scenes for this plan.\n"
                + "Topic: " + r.cleanTopic() + " | Language: " + r.language() + " | Difficulty: " + r.difficulty() + "\n"
                + "Opening requirement: Start directly with the topic/problem; no greeting or generic intro. Suggested topic-led opening: " + openingLine(r.topic()) + "\\n"
                + "Teaching style: " + r.style().label() + " - " + r.style().promptFragment() + "\\n"
                + "Destination: " + r.destination().label() + (r.destination() == Destination.SOCIAL ? " (end with a short subscribe invitation)" : " (no subscribe request)") + "\\n"
                + "Total spoken length: " + r.minWords() + "-" + r.maxWords() + " words across all scenes.\\n"
                + "REFERENCE BENCHMARK: detailed, topic-led, connected teaching; deep explanation of mechanisms and relationships; scene-specific rich visuals; concrete end-to-end example; precise diagram/code flow synchronized to narration. Do not generate generic template narration or shallow bullet-point coverage.\\n"
                + "Plan (follow its scene order, objectives and templates):\\n" + planJson;
    }

    /** Targeted repair: lists the concrete problems and asks for the complete corrected JSON. */
    public static String repairUserPrompt(Request r, String previousJson, List<String> problems) {
        StringBuilder sb = new StringBuilder("Your previous answer has problems that must be fixed. Return the COMPLETE corrected JSON in the same format.\n");
        sb.append("Keep everything that is already correct. Problems:\n");
        for (String p : problems) sb.append(" - ").append(p).append('\n');
        sb.append("Topic: ").append(r.cleanTopic()).append(" | Language: ").append(r.language()).append(" | Target ").append(r.minWords()).append('-').append(r.maxWords()).append(" spoken words.\n");
        sb.append("Previous answer:\n").append(previousJson);
        return sb.toString();
    }

    /** Asks for a longer / shorter lesson (length correction). */
    public static String lengthFixPrompt(Request r, String previousJson, int words) {
        return "Your previous lesson had " + words + " spoken words in total. The target is " + r.minWords() + "-" + r.maxWords() + ". Return the COMPLETE lesson again in the same JSON format, "
                + (words < r.minWords() ? "adding sentences/scenes with real teaching value" : "removing the least important sentences/scenes")
                + ", keeping the opening line, the alignment rules and the recap. Previous lesson:\n" + previousJson;
    }

    private static String nz(String v, String d) { return v == null || v.isBlank() ? d : v; }
}
