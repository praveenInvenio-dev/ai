package com.aistorystudio.conceptexplainer.tutorial;

import java.util.Locale;

/**
 * Prompts for the "Tech tutorial (reference video)" style: a calm instructor builds ONE running
 * example up into a diagram, one sentence and one on-screen element at a time.
 *
 * <p>Teaching pattern and pacing were taken from the approved reference video (≈160 spoken words
 * a minute, short pauses, no music, no greeting fluff, diagrams instead of artwork, a fictional
 * scenario with made-up company names that keeps growing across the lesson).</p>
 *
 * Two calls: an OUTLINE for the whole lesson, then the scenes are WRITTEN in small batches so a
 * 10-minute lesson fits a local model's context.
 */
public final class TutorialPrompts {

    private TutorialPrompts() { }

    /** Lesson size per duration choice. */
    public record Size(int minScenes, int maxScenes, int minWords, int maxWords) { }

    public static Size size(String duration) {
        String d = duration == null ? "" : duration.toLowerCase(Locale.ROOT);
        if (d.contains("full") || d.contains("10") || d.contains("15")) return new Size(32, 42, 1450, 1750);
        if (d.contains("3")) return new Size(15, 20, 420, 520);
        return new Size(8, 11, 140, 180);
    }

    // ------------------------------------------------------------------ shared style rules

    private static final String VOICE = """
            NARRATION STYLE (copy this voice exactly):
            - A calm, confident senior instructor talking to ONE learner, like a good recorded course. Conversational, never salesy, no hype,
              no jokes, no emojis, no "Hello friends", no "smash that subscribe". Start the first scene straight with the hook.
            - Plain words. Sentences of 8-20 words. Every technical term is explained in simple words the first time it appears.
            - Natural spoken connectors: "So", "Now", "Let's say", "Here's the thing", "Think about it", "That's where X comes in",
              "In other words", "Let's take a look", "Right?". Use an occasional rhetorical question, then answer it.
            - Point at the screen: "this agent here", "on the right we have three airlines", "notice this field".
            - Teach in layers, never jumping ahead: something familiar -> its limitation in the running example -> the new idea as the
              fix -> what it is exactly -> how it works step by step (who calls whom, what data moves) -> what it really looks like
              (a request, a response, a config, a few lines of code) -> how it fits with related ideas -> real-world use cases with a
              concrete user request in quotes -> a short recap of the whole picture.
            - ONE running example for the whole lesson (for example a travel app booking flights through three airlines, a shop, a
              hospital, a bank). Use made-up company and product names (like "Joyair", "DracAir", "AeroGo") instead of real brands.
              Each scene adds one piece to that same picture, so the learner watches the system being built.
            - Accurate. If a detail is simplified, say "in simple terms" or "a simplified version". Never invent version numbers,
              statistics or quotes from real people.
            """;

    private static final String SCREEN = """
            ON-SCREEN STYLE (copy this look exactly):
            - Dark teal canvas, few elements, lots of empty space. Diagrams, never pictures. Text on screen is SHORT: labels of 1-5 words,
              card bullets of 1-3 words, quotes only for a user's request. The narration carries the explanation, the screen anchors it.
            - Recurring characters: the learner/user (actor, icon "user"), an AI agent or service (actor, icon "agent", pink), systems as
              coloured tiles with a simple icon and a made-up name, actions as teal buttons, settings/preferences as an outlined card,
              decisions/benefits as a green note, protocols/layers as a tall violet container, data as terminal/browser/code windows.
            - Arrows (curved dashed green connectors) show who talks to whom; put a short label like "Tool", "API", "request" on them.
            - Build up: the scene starts with the context element(s) at sentence 0, then each following sentence adds or highlights
              one thing. Later scenes KEEP the important elements of the previous scene (same id, same place) and extend them.
            """;

    private static final String SCHEMA = """
            SCENE JSON - every scene:
            {"title": "short internal name",
             "heading": "optional on-screen title for section starts, e.g. 'Real World Use Cases - Troubleshooting' (else empty)",
             "keep": ["ids of elements from the PREVIOUS scene that stay on screen unchanged"],
             "narration": ["sentence 0", "sentence 1", ...],
             "captions": ["English sentence 0", ...]   (only when the narration language is not English; same count as narration),
             "elements": [ ... new or changed elements ... ]}
            Element fields: id (short, unique, reused across scenes for the same thing), type, at (index of the narration sentence
            during which it appears; 0 = from the start), x and y (CENTRE position, percent of the 16:9 frame, 0-100), optional w and h
            (percent), and the type's fields:
              actor     text=name, icon=user|agent|robot, color (agent default pink; e.g. green for a second agent)
              heading   text (big gradient label, e.g. "LLM (GPT)")
              button    text (teal label box, e.g. "Check Backend Code"); color optional (violet for concept lists)
              tile      text=made-up name, icon, color=teal|purple|blue|orange|green|red|dark|yellow|white
              card      title, lines=[1-4 short bullets], icon optional
              note      text (green outlined box: a benefit / decision / key idea)
              quote     text (a user's request in their own words)
              pill      text (small tag like "API" or "Commit #3234 caused this"), color, style=solid|outline
              label     text, color, style=small|medium|large
              chat      title=app name, text=user's message, lines=[the assistant's answer, short lines]
              terminal  text=the command, content="output lines separated by \\n"
              browser   title=url like "www.flyfast.com/api/flights", content=page headline + lines OR JSON, style=json|page
              code      title=file name, content="code lines separated by \\n" (max 14 lines, real and correct), style="4-6" to frame lines 4-6
              container text=layer name (tall violet box that other elements sit inside), w and h required
              message   text=what one agent says to another, style=right|left (arrow direction), w
              arrow     from=id, to=id, text=optional short label, style=dashed|solid|both
              icon      icon, color (a lone glyph, e.g. sparkle)
            Optional on any element: dimAt (sentence index from which it fades into the background), highlightAt ([sentence indexes] when it
            glows because the narration talks about it).
            Icons: user, agent, robot, plane, hotel, server, database, cloud, brain, gear, terminal, browser, doc, lock, chart, mail, git,
            code, search, bolt, cart, folder, key, globe, phone, check, warning, api, sparkle, money, clock, book, cpu, network, chat, shield, list.
            LAYOUT: 1920x1080 frame. Keep y >= 14 when there is a heading. Left column x 10-30, middle 40-60, right column x 75-90.
            Stack tiles/buttons with about 17-25 y between them. Never place two elements on the same spot. Max ~10 visible elements.
            - A container always has a title (text) and its members are placed INSIDE its box (never half on its border); an empty container
              is not allowed. To change code / a list / a card, re-declare the SAME id with the new content (it updates in place) instead of
              adding a second window. Leave at least 6 % between boxes so arrows and their labels have room.
            CAMERA (automatic, like the reference video):
            - MOVE: to slide an element somewhere else, put it in "keep" AND re-declare it in "elements" with the same id and new x/y;
              it glides there at the start of the scene (e.g. the agent moves left to make room for a new layer).
            - OVERVIEW: when the system grows (a second agent with its own tools, a new layer, a database behind a server), add the
              new part BEYOND the frame - x 100-150 or y 100-150 - and keep the old part. The camera pulls back automatically so the
              whole system is visible at once. Use this 1-3 times per long lesson, at the moments the big picture matters.
            - Elements of the previous scene that are not kept fade out; new elements fade in.
            """;

    /** True when the narration is English (then captions = narration). */
    public static boolean english(String language) {
        String l = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        return l.isEmpty() || l.startsWith("english") || l.equals("en") || l.startsWith("en-") || l.equals("auto-detect") || l.equals("auto");
    }

    /**
     * Language contract: ONLY the spoken narration follows the chosen language. Every piece of on-screen text
     * (headings, labels, cards, code, titles) and every caption is English, so the visuals are identical whatever
     * the voice language is.
     */
    public static String languageRule(String language) {
        if (english(language)) return "Narration language: English. ALL on-screen text in English.";
        return "Narration language: " + language + " - ONLY the \"narration\" sentences are in " + language + " (natural, spoken, native script)."
                + "\nEVERYTHING ELSE IS ENGLISH: heading, label, title, every element's text/title/lines/content, code, numbers and units."
                + "\nAlso give \"captions\": an English translation of the narration, the SAME number of sentences, one English sentence per narration"
                + " sentence, in the same order (they are shown as subtitles while that sentence is spoken).";
    }

    // ------------------------------------------------------------------ outline

    public static String outlineSystemPrompt() {
        return "You are the lead instructor and course designer of a top technical training platform. You design ONE video lesson in the\n"
                + "exact style described below. Plan only; narration is written later.\n\n" + VOICE + "\n" + SCREEN + """

                Return JSON only:
                {"title": "lesson title",
                 "summary": "one sentence",
                 "runningExample": "the single scenario that is built up through the whole lesson, with the made-up names",
                 "cast": [{"id": "agent", "type": "actor|tile|button|card|container|...", "text": "label", "icon": "...", "color": "..."}],
                 "scenes": [{"section": "HOOK|FAMILIAR|PROBLEM|CONCEPT|HOW_IT_WORKS|IN_PRACTICE|ECOSYSTEM|USE_CASES|RECAP",
                             "heading": "on-screen heading or empty",
                             "keyIdea": "the one thing this scene teaches",
                             "screen": "what is on screen at the start and what each sentence adds, using cast ids"}]}
                """;
    }

    public static String outlineUserPrompt(String topic, String language, String difficulty, String instructions, String trackNotes, Size size) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topic: ").append(topic);
        sb.append("\n").append(languageRule(language));
        sb.append("\nLearner level: ").append(difficulty);
        sb.append("\nScenes: ").append(size.minScenes()).append("-").append(size.maxScenes());
        sb.append(" | Total narration: ").append(size.minWords()).append("-").append(size.maxWords()).append(" words (about 160 words per minute).");
        if (size.maxScenes() >= 30) {
            sb.append("\nThis is a full lesson: cover what it is, why it exists, how it works end to end, what it looks like in practice "
                    + "(requests, responses, config or code), related concepts, at least three real-world use cases (each with a user request in "
                    + "quotes and the steps the system takes) and a recap.");
        }
        if (instructions != null && !instructions.isBlank()) sb.append("\nUser instructions: ").append(instructions);
        if (trackNotes != null && !trackNotes.isBlank()) sb.append("\n").append(trackNotes);
        sb.append("\nThe first scene is HOOK (what we will understand and why it matters, starting from something the learner already "
                + "knows). The last scene is RECAP.");
        return sb.toString();
    }

    // ------------------------------------------------------------------ write

    public static String writeSystemPrompt() {
        return "You write the scenes of a video lesson, in the exact voice and on-screen style below. You turn each outlined scene into\n"
                + "narration sentences plus the diagram elements that appear with them.\n\n" + VOICE + "\n" + SCREEN + "\n" + SCHEMA + """

                RULES:
                - 3-7 narration sentences per scene. Every element's "at" points at the sentence that mentions it.
                - Something new appears on (almost) every sentence; the scene's first sentence has its context already on screen (at 0).
                - Reuse ids from the cast and previous scenes for the same thing; list the ones that stay in "keep".
                - Only write the scenes asked for, in order.
                Return JSON only: {"scenes": [ ...scene JSON... ]}
                """;
    }

    public static String writeUserPrompt(String topic, String language, String outlineJson, int from, int to, int totalScenes,
                                         String previousScene, String lastSentences, int wordsForBatch) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topic: ").append(topic).append("\n").append(languageRule(language));
        sb.append("\nLesson outline (all ").append(totalScenes).append(" scenes):\n").append(outlineJson);
        sb.append("\n\nWrite scenes ").append(from).append(" to ").append(to).append(" (1-based) now, about ")
                .append(wordsForBatch).append(" spoken words in total for these scenes.");
        if (previousScene != null && !previousScene.isBlank()) {
            sb.append("\nElements on screen at the end of the previous scene (reuse ids, keep what still matters):\n").append(previousScene);
        }
        if (lastSentences != null && !lastSentences.isBlank()) {
            sb.append("\nThe narration so far ended with: \"").append(lastSentences).append("\" - continue naturally, no repetition.");
        }
        return sb.toString();
    }

    // ================================================================== 60-second short (vertical reel)

    /** 60 s at ~140 wpm: title card + 5-7 teaching scenes + outro. */
    /** 60 s of speech at the narrator's pace (~150-160 wpm incl. pauses) = 155-175 words. */
    public static Size shortSize() { return new Size(9, 11, 155, 175); }

    public static final String SHORT_TAGLINE = "Follow the page to learn one concept a day.";

    private static final String SHORT_VOICE = """
            NARRATION (a 60-second vertical explainer reel, 155-175 words in total - it MUST fill 60 seconds):
            - Open with the question people actually ask ("Docker container vs VM?", "How does an AI model actually learn?").
              Then answer it with ONE concrete, tiny example that is built up on screen ("Ten houses. Their size, and their price.").
            - Short spoken sentences of 4-14 words. Fragments are fine. No greeting, no "in this video", no hype, no emojis.
            - Use real numbers the viewer can see: values, sizes, counts, before -> after ("600 megabytes versus 30").
            - One idea per scene, in this order: the question -> the setup/example -> how it works (2-4 scenes, each one
              step) -> the key difference or the payoff with a number -> one sentence that names the concept ("That loop is
              gradient descent.") -> scale it up to the real world in one line.
            - Calm, clear, confident male explainer voice, about 140 words per minute.
            - The final scene says "Follow the page to learn one concept a day." (in the narration language; English caption exactly that).
            - COMPLETENESS (the viewer must get it in one watch, never half-baked): say in plain words WHAT the concept is, show HOW it
              works step by step on the example (cause -> effect), show WHY it matters with a concrete before/after, NAME it, and give one
              real-world place it is used. Every technical word is explained the moment it appears.
            """;

    private static final String SHORT_SCREEN = """
            ON SCREEN (vertical 1080x1920, near-black, minimal monochrome UI + a few colour accents):
            - Scene 1: ONE "titlecard" element: title = small topic tag (e.g. "Machine learning"), text = the concept name (2-4 words,
              drawn big in green), content = a 3-6 word subtitle ("how AI models learn"), style = "Learn one concept a day".
            - Teaching scenes: a small mono "label" like "02 · the hypervisor" and a short "heading" (2-6 words, the scene's claim).
            - Use: node (wireframe cube + name + mono sub-label), button (a wide dark bar such as "hypervisor" or "host kernel"),
              chips (rows of small numbered squares with a caption), table (dark info card with numbered key|value rows),
              bars (two-row size/speed comparison), tabs (1 · speed / 2 · size / 3 · security with the active one), value (mono
              number pill like "w 0.2"), metric (huge number + label like "11.4 LOSS"), plot (points + line / error lines /
              falling loss curve), arrow (thin connector), label (small text).
            - Everything sits between y 18 and y 72; the bottom quarter is for captions.
            - RICH, MEANINGFUL FRAMES: every teaching scene builds ONE diagram of 3-6 elements that SHOWS the mechanism - things connected
              by arrows, values that change, a before/after side by side. Use the full width (x 15-85) and height (y 20-70).
              Every element carries real information from the narration. NO decoration: no chips/tabs/plots/numbers that are not
              explained; a plot only with real meaningful data points; never a lone element in an empty frame.
            - Last scene: ONE "outro" element, text = "Follow the page to learn one concept a day.", title = "Follow".
            """;

    private static final String SHORT_SCHEMA = """
            SCENE JSON: {"title":"internal name", "label":"0N · short section name (empty on title/outro)", "heading":"short claim (empty on title/outro)",
             "keep":["ids from the previous scene that stay"], "narration":["sentence 0","sentence 1",...],
             "captions":["English translation of each narration sentence"] (only for non-English narration), "elements":[...]}
            Element fields: id, type, at (index of the sentence during which it appears), x, y (centre, % of the frame), optional w, h (%), and:
              titlecard: title, text, content, style        outro: text, title
              node: text=name, title=mono sub-label, style="boxed" for a dashed frame, icon optional (default cube)
              button: text   chips: lines=["1 2 3|cpu","1 2 3|ram"], style="solid" for white chips, title=caption under the row
              table: title, text=right tag, lines=["key|value",...] (max 4)     bars: title, lines=["VM image|600|MB","container image|30|MB"]
              tabs: lines=["1 · speed","2 · size","3 · security"], style="2" (active tab number)
              value: text="w 0.2", title=caption     metric: text="11.4", title="loss", color=red|green|cyan
              plot: title=y-axis label, text=x-axis label, lines=["x,y",...], style="residual" (error lines to the line) | "fit" | "decay"
                    (falling curve), content="slope, intercept" to draw a specific (e.g. wrong first-guess) line
              arrow: from, to, text optional      label: text, style=small|medium|large, color
            Optional: dimAt, highlightAt (sentence indexes).  Re-declare a kept id with a new x/y to glide it; place things at
            y > 100 only when the picture must grow (the camera pulls back).
            """;

    public static String shortOutlineSystemPrompt() {
        return "You design 60-second vertical explainer reels that teach one concept so clearly a beginner gets it in one watch.\n\n"
                + SHORT_VOICE + "\n" + SHORT_SCREEN + """

                Return JSON only:
                {"title":"concept name","summary":"one sentence","hook":"the opening question",
                 "example":"the one tiny concrete example built up on screen (with its numbers)",
                 "scenes":[{"section":"HOOK|SETUP|STEP|PAYOFF|NAME|SCALE|OUTRO","label":"0N · name","heading":"claim","keyIdea":"...","screen":"what appears, per sentence"}]}
                """;
    }

    public static String shortOutlineUserPrompt(String topic, String language, String difficulty, String instructions, String trackNotes, Size size) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topic: ").append(topic).append("\n").append(languageRule(language)).append("\nLearner level: ").append(difficulty);
        sb.append("\nScenes: ").append(size.minScenes()).append("-").append(size.maxScenes()).append(" including the title card and the outro")
                .append(" | Total narration: ").append(size.minWords()).append("-").append(size.maxWords()).append(" words (60 seconds).");
        if (instructions != null && !instructions.isBlank()) sb.append("\nUser instructions: ").append(instructions);
        if (trackNotes != null && !trackNotes.isBlank()) sb.append("\n").append(trackNotes);
        return sb.toString();
    }

    public static String shortWriteSystemPrompt() {
        return "You write every scene of a 60-second vertical explainer reel: narration sentences plus the on-screen elements that appear with them.\n\n"
                + SHORT_VOICE + "\n" + SHORT_SCREEN + "\n" + SHORT_SCHEMA + """

                RULES: 1-4 sentences per scene; each element's "at" is the sentence that mentions it; numbers on screen match the narration;
                keep the same ids for the same things across scenes. Return JSON only: {"scenes":[ ...all scenes, in order... ]}
                """;
    }

    // ================================================================== real-world breakdown (3D blueprint reel)

    /** ~2-2.5 minutes at ~175 wpm. */
    public static Size breakdownSize() { return new Size(16, 22, 380, 470); }

    private static final String BREAKDOWN_VOICE = """
            NARRATION (a 2-minute vertical "real-world breakdown" reel, 380-470 words, fast and confident, ~175 words per minute):
            - Explain the concept THROUGH one real-world product or situation the viewer already uses (a train-ticket app, a food-delivery
              ETA, UPI payments, a lift in a building, a cricket score app, a bank queue...). The example is the spine of the whole video.
            - Open with the challenge, as an interviewer or a real problem would put it: "Build a train booking system like IRCTC."
            - Then the naive answer most people give, said in first person ("Lock the seat in the database till the payment is done.")
              - and show why it breaks at real scale.
            - Make the scale feel real. Use a real, well-known number ONLY if you are certain it is true; otherwise say "imagine" and give
              an obviously illustrative number. Never invent dates, studies or statistics.
            - Walk the user's journey through the product step by step (3-4 named steps). At each step: what the user does, what the system
              must do, what breaks, the technique that fixes it, and its name in one beat: "This is called X." Add the exact command/query
              or rule when it makes it concrete. Mention the trade-off ("when clashes are rare, X; when certain, Y").
            - "The best part": one clever, lesser-known detail at the end that makes people say "wow".
            - Recap in one breath: step -> technique. End with a question that starts a debate in the comments.
            - Second person, short spoken sentences (5-15 words), no filler, no greeting, no hype words, no emojis.
            """;

    private static final String BREAKDOWN_SCREEN = """
            ON SCREEN (vertical 1080x1920 "3D blueprint" look: deep navy 3D room, glowing objects, yellow highlights, no captions):
            - Every scene has the same "label" series tag, e.g. "SYSTEM DESIGN · 007" (or "MONEY · 012", "EVERYDAY TECH · 004").
            - From the journey part on, a "journey" element at the top (x 50, y 11) shows the steps with the current one active.
            - Text lives in "callout" cards (x 50, y 18-24, w 88): kicker in title ("you", "the trap", "this is called", "recap"),
              text with *highlighted words* and ~~struck-out words~~. style: "trap" for the wrong idea, "named" when a technique is named,
              "stat" for a big number sentence ("*2* in *3* requests were *bots*").
            - Objects (centre column, y 30-80): db (glowing 3D database, style "main"/"slow"), server (rack), phone (the real app screen:
              text = header, title = sub-header, lines = list rows "Name|STATUS", style = button label), rows (3D record slabs
              "23|B4|LOCKED · YOU|v7" - status words BOOKED/FREE/LOCKED/HELD/BOOKED · YOU drive the look), particles (a swarm of requests,
              style "bots" = mostly red), cables (bundle between two ids, text = how many), sql (query card: title = kicker,
              content = lines), pill (small mono tag like READ / WRITE / IN USE 98/100), icon with icon "padlock", arrow.
            - photo: a generated full-screen picture of the real-world example (text = what to show, e.g. "a long Indian passenger train at
              a night platform"), used for the opening scene and once or twice more (a callout can sit on top of it). Max 3 photos.
            - 2-6 elements per scene, one new thing per sentence, the same ids for the same objects across scenes (keep + glide).
            - NEVER an empty scene: besides the journey tracker every scene shows at least one real object or card. A phone ALWAYS has
              lines (the real list on that screen). A photo is always full-screen (no x/y/w/h). Stat callouts: one short sentence,
              numbers as digits ("*1,000,000* users, *1* row").
            """;

    private static final String BREAKDOWN_SCHEMA = """
            SCENE JSON: {"title":"internal","label":"SERIES · 007","heading":"optional small kicker like THE BEST PART (else empty)",
             "keep":["ids from the previous scene that stay"],"narration":["sentence 0",...],"captions":[...only for non-English narration...],
             "elements":[...]}
            Element fields: id, type, at (sentence index when it appears), x, y (centre, % of frame), optional w, h (% of frame), and:
              callout: title=kicker, text (with *hl* / ~~strike~~), style (trap|named|stat|stat red), lines=["Step -> *technique*"] for recaps
              journey: lines=["search","book now","payment"], style="2" (active step)      photo: text=what to picture
              db: text=label, style main|slow, w optional (default ~24)      server: text=label
              phone: text, title, lines=["Train 1|AVAILABLE 0021"], style=button label
              rows: lines=["21|B4|BOOKED","23|B4|LOCKED · YOU|v7"]      particles: text=how many (e.g. 200), style bots, w/h
              cables: from, to, text=count      sql: title, content="SELECT * FROM seats\nWHERE id = 23 FOR UPDATE;"
              pill: text, color yellow|white     icon: icon=padlock     arrow: from, to, text
            Optional: dimAt, highlightAt.
            """;

    public static String breakdownOutlineSystemPrompt() {
        return "You create premium 2-minute vertical explainer reels that teach a concept through one real-world product the viewer uses every day,\n"
                + "with the clarity of a senior engineer and the pace of a top creator.\n\n" + BREAKDOWN_VOICE + "\n" + BREAKDOWN_SCREEN + """

                Return JSON only:
                {"title":"...","summary":"one sentence","example":"the real-world product/situation used","label":"SERIES · 0NN",
                 "challenge":"the opening challenge line","naive":"the naive answer","journey":["step 1","step 2","step 3"],
                 "scenes":[{"section":"CHALLENGE|NAIVE|SCALE|JOURNEY|NAME|TRADEOFF|BEST_PART|RECAP|CTA","keyIdea":"...","screen":"what is on screen per sentence"}]}
                """;
    }

    public static String breakdownOutlineUserPrompt(String topic, String language, String difficulty, String instructions, String trackNotes, Size size) {
        StringBuilder sb = new StringBuilder();
        sb.append("Concept / topic: ").append(topic).append("\n").append(languageRule(language)).append("\nViewer level: ").append(difficulty);
        sb.append("\nScenes: ").append(size.minScenes()).append("-").append(size.maxScenes()).append(" | Narration: ").append(size.minWords())
                .append("-").append(size.maxWords()).append(" words.");
        sb.append("\nIf the topic does not name a real-world example, pick the most relatable everyday product or situation for an Indian viewer.");
        if (instructions != null && !instructions.isBlank()) sb.append("\nUser instructions: ").append(instructions);
        if (trackNotes != null && !trackNotes.isBlank()) sb.append("\n").append(trackNotes);
        return sb.toString();
    }

    public static String breakdownWriteSystemPrompt() {
        return "You write the scenes of a 2-minute real-world breakdown reel: narration plus the 3D-blueprint elements that appear with it.\n\n"
                + BREAKDOWN_VOICE + "\n" + BREAKDOWN_SCREEN + "\n" + BREAKDOWN_SCHEMA + """

                RULES: 2-5 sentences per scene; every element's "at" is the sentence that mentions it; numbers on screen match the narration;
                callout text is short (max ~10 words); reuse ids across scenes. Return JSON only: {"scenes":[ ...the scenes asked for... ]}
                """;
    }
}
