package com.aistorystudio.conceptexplainer.tutorial;

import java.util.List;
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

    // ------------------------------------------------------------------ variety (every format)

    /** Teaching devices every format can use; each one carries a different kind of idea. */
    static final String DEVICES = """
            TEACHING DEVICES (pick the one that fits the idea; they work in every look):
              timeline  lines=["1876|Telephone","2007|Smartphone"], style="3" highlights event 3     (history, evolution, sequence in time)
              steps     lines=["Swallow|muscles open the tube",...], style "vertical" or "2" (highlight)  (a process, how-to, order matters)
              compare   title="SQL|NoSQL", lines=["Schema|✓ Fixed|✗ Flexible",...]            (A vs B, before vs after, myth vs fact)
              cycle     text=centre label, lines=["Evaporation","Condensation","Rain"]          (loops, feedback, repeating processes)
              layers    lines=["Apps|what you use","OS|...",...] top->bottom, style "pyramid"  (stacks, hierarchies, levels, needs)
              venn      lines=["Maths","Coding","Domain"], text=what sits in the overlap       (overlap, what two things share)
              meter     text="72%", title=label, lines=["low","high"]                          (a level, risk, score, "how much")
              checklist title, lines=["✓ Chew gum","✗ Sleep through landing"]                   (do / don't, symptoms, requirements)
              analogy   title=the idea, icon=its icon, text=everyday thing, content=its icon, lines=[shared traits]   (explain by comparison)
              matrix    title="x axis|y axis", lines=[4 quadrant labels TL,TR,BL,BR], style="2" highlights one  (2x2 trade-offs, priorities)
              formula   text="E = m × c²", lines=["E|energy","m|mass"]                          (a rule, equation, ratio)
              pie       text=centre caption, lines=["Rent|40","Food|25"] (percent)              (parts of a whole - real numbers only)
              tree      text=root, lines=["Plants: trees, grass","Animals: birds, fish"]         (categories, family, breakdown)
            SUBJECT DEVICES:
              forces    text=object, lines=["up|N|Normal","down|mg|Weight","right|F|Push"] (dir|symbol|name[|size 0.5-2])   (physics: free-body)
              wave      title=caption, lines=["λ = 0.5 m","A = 2 cm"], style "square" optional    (physics: waves, sound, light, AC)
              circuit   text=law in the middle, lines=["battery|9 V","resistor|R = 3 Ω","bulb|","ammeter|","switch|"]   (electricity)
              atom      text=symbol, title=caption, lines=electrons per shell ["2","8","1"]      (chemistry: structure, valency)
              molecule  text=formula like "H2O", lines=atoms, first is central ["O","H","H"], "=O" = double bond   (chemistry: bonding, shape)
              reaction  text="2H2 + O2 -> 2H2O" (digits become subscripts), title=condition above the arrow, lines=labels   (chemistry)
              element   text=symbol, title=name, lines=[atomic number, atomic mass]                   (periodic table)
              pins      from=id of a photo / shape, lines=["Eardrum@50,55"] (label @ x%,y% inside it)  (biology / anatomy / geography labels)
              numberline title="-5..5", lines=["-2|x","3|y"], style="-2..3" shades an interval     (maths: integers, inequalities)
              triangle  lines=["a|4","b|3","c|5"], text=angle label, style "right"                   (maths: geometry, trigonometry)
              fraction  lines=["3/4|three slices of pizza","1/2|half a glass"]                        (maths for beginners / kids)
              quiz      title=question, lines=[options], style=number of the right option, highlightAt=[sentence that reveals it]  (recall / exams / kids)
              factcard  title="Did you know?", text=one surprising true fact, icon                      (general knowledge, hooks)
              mascot    text="Robo", style happy|wow|thinking|wave                                    (kids: the friendly guide)
              speech    text=what the mascot says (short), style left|right = tail side              (kids)
              badge     text="Great job!"                                                             (kids: celebrate at the end)
            KIDS / AGE DEVICES:
              cloudtitle text=title, title="Explore · Play · Learn" (3 short words)          (kids: title banner over an illustrated scene)
              iconrow   lines=["Letters|ABC|pink","Numbers|123|green","Coding|code|violet"] (label|icon or 3-letter text|colour)
              bigpic    text=the one word, icon, color                                      (ages 2-5: one big thing)
              count     text=how many (1-5 for toddlers), icon                               (ages 2-5: counting)
              choose    lines=["Phone|phone","Apple|apple"], style=right one, highlightAt=[answer sentence]   (ages 2-5: pick a picture)
              sizes     icon, lines=["BIG","small"]                                          (ages 2-5)
              pip       text="Pip", style happy|wow|listen|wave                              (ages 2-5 drawn friend, when no picture)
              flowchart text=edges "start>check, check>pay:yes, check>retry:no", lines=["start|Open the app|start","check|Card valid?|decision","pay|Take payment|process","done|Order placed|end"] (kind: start|process|decision|end|io)   (algorithms, processes, decisions)
              sequence  lines=["Browser|laptop","Server|server","DB|database"], text="Browser>Server:GET /login, Server>DB:find user, DB>Server:user row!" (trailing ! = dashed reply)   (who talks to whom, APIs, handshakes)
              datastruct style=array|array:2|stack|queue|linkedlist|hash (":2" highlights item 2), lines=values (hash: "0|cat:3, dog:7")   (data structures, memory)
              periodic  text="alkali, halogen, Fe" (symbols and/or groups; empty = whole table by group), title=caption   (chemistry: trends, groups)
              energy    lines=["Reactants|0","Transition state|65","Products|-30"] (kJ), style="catalyst" adds the lower route, title=x caption   (chemistry: Ea, delta H)
              lens      style=convex|concave, text="u=30, f=10"   (physics: ray diagram, image position and kind)
              funnel    lines=["Visitors|10000","Sign-ups|2400","Paying|310"] largest first   (business: conversion, filtering, selection)
              level     title="LEVEL 2", text=what this level unlocks, style=XP percent      (ages 10-15)
              challenge title=the puzzle, lines=options, style=right one, text="10 s", highlightAt=[answer sentence]   (ages 10-15)
            """;

    static final String VARIETY = """
            VARIETY (the lesson must not look like every other lesson):
            - Every scene has ONE main visual device chosen for what that scene teaches (devices above, or the diagram / window /
              chart elements). Two consecutive scenes never use the same main device.
            - Use at least MIN_DEVICES different main devices across the lesson. Mix structure (steps, cycle, tree, layers),
              comparison (compare, venn, matrix, analogy), quantity (meter, pie, formula, plot/bars) and story (actors, chat, photo).
            - Vary the composition from scene to scene: centred hero, left-right split, top-to-bottom flow, grid of items.
            - Add a real-world photo/illustration when the learner benefits from seeing the actual object, environment, person, organism,
              historical place or physical setup. Use up to 5 photo moments in a full lesson, not as decoration and not in consecutive scenes.
            - Give each scene a visible change: a request moves, a force vector appears, a value changes, a character reacts, a route advances,
              a code line executes, or a comparison reveals a trade-off. Avoid a sequence of unchanged title cards.
            - Maintain a consistent design system, but change shot scale and depth: establish the setting, move to a close-up of the important
              detail, then return to the whole system. Keep all text large enough for a mobile screen and leave safe margins for captions.
            """;

    /** Teaching approaches; a different one is suggested for each lesson so lessons do not all follow the same arc. */
    public static final List<String> APPROACHES = List.of(
            "STORY-FIRST: follow one person through a real situation; the concept appears exactly when they need it",
            "MYTH-BUSTER: start from what most people wrongly believe, then show what is really going on",
            "BUILD IT FROM SCRATCH: start with the simplest version, add one piece per scene until the full idea stands",
            "QUESTION LADDER: five questions a curious learner would ask, each answer unlocking the next question",
            "COMPARE AND CONTRAST: understand the idea by putting it next to its opposite or its older alternative",
            "HOW WE GOT HERE: the history - the problem people had, the failed fixes, and the idea that finally worked",
            "ANALOGY-FIRST: open with an everyday thing that works the same way, then map each part onto the real concept",
            "MISTAKE-DRIVEN: walk through the most common mistakes and what each one teaches about how it really works",
            "NUMBERS-FIRST: open with one striking real number, then explain what produces it");

    public static String approachFor(long seed) {
        return APPROACHES.get((int) Math.floorMod(seed, (long) APPROACHES.size()));
    }

    static String variety(int minDevices) { return VARIETY.replace("MIN_DEVICES", String.valueOf(minDevices)); }


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
            NARRATION STYLE:
            - Follow the selected NARRATION TRACK included in the user prompt (humorous, logic-first, story-driven, professional, beginner-friendly,
              or engaging tutor). That track controls the narrator's personality, rhythm, humour level and per-scene voiceDirection; do not force every
              lesson into the same calm-instructor voice. If no track is supplied, use a calm, confident senior instructor talking to ONE learner.
              Conversational, never salesy or shouty. No emojis in spoken narration. Start the first scene straight with the hook.
            - Plain words. Sentences of 8-20 words. Every technical term is explained in simple words the first time it appears.
            - Natural spoken connectors: "So", "Now", "Let's say", "Here's the thing", "Think about it", "That's where X comes in",
              "In other words", "Let's take a look", "Right?". Use an occasional rhetorical question, then answer it.
            - Point at the screen: "this agent here", "on the right we have three airlines", "notice this field".
            - Teach in layers, never jumping ahead: something familiar -> its limitation in the running example -> the new idea as the
              fix -> what it is exactly -> how it works step by step (who calls whom, what data moves) -> what it really looks like
              (a request, a response, a config, a few lines of code) -> how it fits with related ideas -> real-world use cases with a
              concrete user request in quotes -> a short recap of the whole picture.
            - ONE running example for the whole lesson that fits the SUBJECT (tech: an app or system; health: a person and their body,
              e.g. a passenger whose ears pop on a flight; money: a family budget; science: an everyday object). Use made-up company and product names (like "Joyair", "DracAir", "AeroGo") instead of real brands.
              Each scene adds one piece to that same picture, so the learner watches the system being built.
            - Accurate. If a detail is simplified, say "in simple terms" or "a simplified version". Never invent version numbers,
              statistics or quotes from real people.
            """;

    private static final String SCREEN = """
            ON-SCREEN STYLE (copy this look exactly unless the selected visual style says otherwise):
            - Default to a rich, layered visual rather than a bare slide: subject-specific diagram or concrete illustration, clear focal point,
              deliberate depth, directional lighting for picture scenes, restrained accent colours and strong contrast. Do not clutter the frame.
            - Dark teal canvas, few elements, lots of intentional empty space. Diagrams are preferred when relationships or mechanisms matter;
              use real pictures when the object, place, body structure, experiment or human reaction is the lesson. Text on screen is SHORT: labels of 1-5 words,
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
            """ + DEVICES + """
            Icons: user, agent, robot, plane, hotel, server, database, cloud, brain, gear, terminal, browser, doc, lock, chart, mail, git,
            code, search, bolt, cart, folder, key, globe, phone, check, warning, api, sparkle, money, clock, book, cpu, network, chat, shield, list,
            ear, eye, heart, lungs, tooth, bone, pill, syringe, stethoscope, virus, bacteria, cell, dna, drop, water, thermometer, hospital,
            plus, stomach, kidney, muscle, apple, food, leaf, sun, moon, wind, gauge, person, baby, family, home, car, bed, mask, microscope.
              photo     text = what to picture (an anatomy cross-section, a real object or place), style "framed", w/h in % - a generated
                        illustration. Use up to 5 per full lesson only where a real picture explains more than a diagram; never as filler.
            LAYOUT: 1920x1080 frame. Keep y >= 14 when there is a heading. Left column x 10-30, middle 40-60, right column x 75-90.
            Stack tiles/buttons with about 17-25 y between them. Never place two elements on the same spot. Max ~10 visible elements.
            - MATCH THE SUBJECT. Health / body / biology topics: use body-part and medical icons (ear, heart, lungs, pill, virus ...)
              on tiles for organs and parts, people as actors, a photo for the anatomy; NEVER app tiles, servers, code or terminals.
              Money, food, travel, everyday topics: everyday icons (money, home, car, food, plane ...). Tech vocabulary only for tech topics.
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
        return NarrationScript.rule(language) + "\nNarration language: " + language + " - ONLY the \"narration\" sentences are in " + language + " (natural, spoken, native script)."
                + "\nEVERYTHING ELSE IS ENGLISH: heading, label, title, every element's text/title/lines/content, code, numbers and units."
                + "\nAlso give \"captions\": an English translation of the narration, the SAME number of sentences, one English sentence per narration"
                + " sentence, in the same order (they are shown as subtitles while that sentence is spoken).";
    }

    // ------------------------------------------------------------------ outline

    public static String outlineSystemPrompt() {
        return "You are the lead instructor and course designer of a top technical training platform. You design ONE video lesson in the\n"
                + "exact style described below. Plan only; narration is written later.\n\n" + VOICE + "\n" + SCREEN + "\n" + DEVICES + """

                Return JSON only:
                {"title": "lesson title",
                 "summary": "one sentence",
                 "domain": "technology|medical|biology|science|finance|everyday|history|other",
                 "runningExample": "the single scenario that is built up through the whole lesson, with the made-up names",
                 "cast": [{"id": "agent", "type": "actor|tile|button|card|container|...", "text": "label", "icon": "...", "color": "..."}],
                 "approach": "the teaching approach you follow",
                 "scenes": [{"section": "HOOK|FAMILIAR|PROBLEM|CONCEPT|HOW_IT_WORKS|IN_PRACTICE|ECOSYSTEM|USE_CASES|RECAP",
                             "device": "the scene's main visual device (timeline, steps, compare, cycle, ... or diagram)",
                             "heading": "on-screen heading or empty",
                             "keyIdea": "the one thing this scene teaches",
                             "screen": "what is on screen at the start and what each sentence adds, using cast ids"}]}
                """;
    }

    public static String outlineUserPrompt(String topic, String language, String difficulty, String instructions, String trackNotes, Size size) {
        return outlineUserPrompt(topic, language, difficulty, instructions, trackNotes, size, null);
    }

    public static String outlineUserPrompt(String topic, String language, String difficulty, String instructions, String trackNotes, Size size, String approach) {
        StringBuilder sb = new StringBuilder();
        if (approach != null) sb.append("Teaching approach for THIS lesson (follow it, it keeps lessons from all feeling the same): ").append(approach).append("\n");
        sb.append(variety(size.maxScenes() >= 30 ? 9 : size.maxScenes() >= 15 ? 6 : 4)).append("\n");
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
        sb.append("\nEach outlined scene names its main visual \"device\" - build that scene around exactly that device.");
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
            - Narrator personality and emotional delivery follow the selected NARRATION TRACK in the user prompt. Keep the short-form pace clear and intelligible,
              around 140-155 words per minute, and follow the selected narrator voice's gender and language; never force a male persona when a different voice is selected.
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
              (for health / body / biology / everyday topics use matching icons on nodes and tiles - ear, heart, pill, virus, money,
              food ... - never servers, terminals or code), chips (rows of small numbered squares with a caption), table (dark info card with numbered key|value rows),
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
            """ + DEVICES + """
            Optional: dimAt, highlightAt (sentence indexes).  Re-declare a kept id with a new x/y to glide it; place things at
            y > 100 only when the picture must grow (the camera pulls back).
            """;

    public static String shortOutlineSystemPrompt() {
        return "You design 60-second vertical explainer reels that teach one concept so clearly a beginner gets it in one watch.\n\n"
                + SHORT_VOICE + "\n" + SHORT_SCREEN + "\n" + DEVICES + """

                Return JSON only:
                {"title":"concept name","summary":"one sentence","hook":"the opening question",
                 "example":"the one tiny concrete example built up on screen (with its numbers)",
                 "approach":"the teaching approach you follow",
                 "scenes":[{"section":"HOOK|SETUP|STEP|PAYOFF|NAME|SCALE|OUTRO","device":"main visual device","label":"0N · name","heading":"claim","keyIdea":"...","screen":"what appears, per sentence"}]}
                """;
    }

    public static String shortOutlineUserPrompt(String topic, String language, String difficulty, String instructions, String trackNotes, Size size) {
        return shortOutlineUserPrompt(topic, language, difficulty, instructions, trackNotes, size, null);
    }

    public static String shortOutlineUserPrompt(String topic, String language, String difficulty, String instructions, String trackNotes, Size size, String approach) {
        StringBuilder sb = new StringBuilder();
        if (approach != null) sb.append("Teaching approach for THIS reel: ").append(approach).append("\n");
        sb.append(variety(4)).append("\n");
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
            - Second person, short spoken sentences (5-15 words), no filler, no greeting, no hype words, no emojis. The selected NARRATION TRACK in the user prompt
              controls humour, reasoning style, storytelling and per-scene voiceDirection; the breakdown structure must not flatten all tracks into one voice.
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
            """ + DEVICES + """
            Optional: dimAt, highlightAt.
            """;

    public static String breakdownOutlineSystemPrompt() {
        return "You create premium 2-minute vertical explainer reels that teach a concept through one real-world product the viewer uses every day,\n"
                + "with the clarity of a senior engineer and the pace of a top creator.\n\n" + BREAKDOWN_VOICE + "\n" + BREAKDOWN_SCREEN + "\n" + DEVICES + """

                Return JSON only:
                {"title":"...","summary":"one sentence","example":"the real-world product/situation used","label":"SERIES · 0NN",
                 "challenge":"the opening challenge line","naive":"the naive answer","journey":["step 1","step 2","step 3"],
                 "scenes":[{"section":"CHALLENGE|NAIVE|SCALE|JOURNEY|NAME|TRADEOFF|BEST_PART|RECAP|CTA","device":"main visual device","keyIdea":"...","screen":"what is on screen per sentence"}]}
                """;
    }

    public static String breakdownOutlineUserPrompt(String topic, String language, String difficulty, String instructions, String trackNotes, Size size) {
        StringBuilder sb = new StringBuilder();
        sb.append(variety(6)).append("\n");
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

    // ================================================================== pre-production craft (all formats)
    // Ideas drawn from published explainer-video research (hook in the first seconds, misconception-first, but/therefore links,
    // guided discovery, one idea at a time, deliberate silence) and from the way OpenMontage structures its script stage.
    // The wording here is our own.

    /** Seconds for hook / tension / core / proof / close, interpolated between lengths (1, 2, 3, 5 min), scaled beyond. */
    static int[] arcSeconds(int total) {
        int[][] rows = {{60, 5, 10, 30, 10, 5}, {120, 8, 15, 60, 25, 12}, {180, 8, 22, 100, 30, 15}, {300, 10, 30, 180, 50, 20}};
        if (total <= rows[0][0]) {
            double k = Math.max(0.5, total / 60.0);
            return new int[]{(int) Math.max(3, Math.round(5 * k)), (int) Math.round(10 * k), (int) Math.round(30 * k), (int) Math.round(10 * k), (int) Math.round(5 * k)};
        }
        if (total >= rows[3][0]) {
            double k = total / 300.0;
            return new int[]{(int) Math.min(15, Math.round(10 + (k - 1) * 3)), (int) Math.round(30 * k), (int) Math.round(180 * k), (int) Math.round(50 * k), (int) Math.round(20 * k)};
        }
        for (int r = 0; r < 3; r++) {
            if (total <= rows[r + 1][0]) {
                double f = (total - rows[r][0]) / (double) (rows[r + 1][0] - rows[r][0]);
                int[] o = new int[5];
                for (int c = 0; c < 5; c++) o[c] = (int) Math.round(rows[r][c + 1] + f * (rows[r + 1][c + 1] - rows[r][c + 1]));
                return o;
            }
        }
        return new int[]{8, 15, 60, 25, 12};
    }

    public static String storyCraft(int totalSeconds) {
        int[] a = arcSeconds(Math.max(30, totalSeconds));
        return """
                STORY CRAFT - how this video holds attention (follow these on top of everything above):
                1. SHAPE: hook, tension, core, proof, close. Time plan for about %d s: hook %d s, tension %d s, core %d s, proof %d s, close %d s.
                2. HOOK in the first seconds. Pick the type that fits the topic: contrarian ("what you were told about X is wrong"), outcome
                   ("by the end you will be able to ..."), mystery (a strange fact or event) or stakes ("this one mistake costs people ...").
                   Never open with a greeting, "in this video", a definition or the bare topic name.
                3. Hook and problem together are finished in the first 30 s (the first 15 s of a one-minute video): most viewers decide then.
                4. Join ideas with BUT and THEREFORE, never "and then": "X is true, BUT it breaks when ..., THEREFORE we need ...".
                5. For science and technical topics begin with the common misconception, then show what is really true: people learn more
                   from a refuted belief than from the plain fact.
                6. Guided discovery: ask a concrete question, show the obvious attempt, let it fail, give ONE key insight, build it step by
                   step, then show it works elsewhere. Do not simply announce the answer.
                7. One new idea at a time (about one per 30-45 s). Signpost where you are ("here is where it gets interesting"). In lessons over
                   two minutes add a light breather (a joke, a quick recap) about once a minute and change the rhythm every 45-90 s.
                8. Silence is a tool: the sentence that carries the key insight is followed by a pause.
                9. Say what is SEEN, not how it should feel. In picture and visual descriptions give framing, subject, action, light and
                   expression ("wide shot, a child points at a glowing screen, warm lamp light"); never "epic", "magical", "stunning", "inspiring".
                10. Never invent statistics, dates, names or quotes. If unsure say "about", "imagine", or leave the number out.
                Every scene also returns:
                  "camera": one of push-in | pull-out | pan-left | pan-right | rise | hold   (the camera idea for the picture)
                  "delivery": {"pace":"slow|measured|brisk", "energy":"calm|curious|excited|warm|serious",
                               "emphasis":["1-3 words to stress"], "pauseAfter":[one number of seconds per narration sentence],
                               "note":"one concrete direction, e.g. measured, then slow down on the last phrase"}
                  pauseAfter: 0 for most sentences, 1-2 s after the sentence that carries the reveal, never more than 3 s in a scene.
                """.formatted(totalSeconds, a[0], a[1], a[2], a[3], a[4]);
    }

    /** The system prompt with the story craft appended, plus the Manim templates when the animation service is running. */
    public static String withCraft(String system, int totalSeconds, boolean manim) {
        return withCraft(system, totalSeconds) + (manim ? "\n\n" + manimBlock() : "");
    }

    public static String manimBlock() {
        return """
                ANIMATED MATHS / PHYSICS SCENES (Manim) - available now:
                A scene may carry a "manim" object next to its "elements": a real animation (a graph being drawn, vectors adding, a ball flying)
                that plays behind the scene's overlays and is paced to the narration. Use it when a MOVING mathematical picture teaches the idea
                better than boxes and arrows - never as decoration.
                  "manim": {"template": "<name>", "params": {...}}
                  function_plot   {"expr":"x^2 - 4x + 3","xmin":-1,"xmax":5,"points":[{"x":1,"label":"root"}],"area":[1,3],"tangent":2,"caption":"short line"}
                  vector_add      {"vectors":[{"x":3,"y":2,"label":"A"},{"x":2,"y":-1,"label":"B"}],"resultant":true}
                  projectile      {"v0":20,"angle":45}
                  equation_steps  {"lines":["x^2 + 5x + 6 = 0","(x + 2)(x + 3) = 0","x = -2 or x = -3"],"notes":["","factorise","each bracket = 0"]}
                  wave            {"waves":[{"amp":1,"freq":1},{"amp":0.7,"freq":3}],"sum":true}
                  unit_circle     {"angle":50}
                  pythagoras      {"a":3,"b":4}
                Rules: write formulas as plain text (x^2, sin(x)/x, 2x(x+1)), never LaTeX. The numbers must be correct - a wrong graph is worse than none.
                The animation shows its steps in order (axes, curve, points, area ...) spread evenly over the narration, so narrate them in that order.
                In a Manim scene leave "elements" EMPTY ([]): the scene heading already titles it and the animation fills the frame; put any words
                on screen through the template itself (function_plot "caption", equation_steps "notes", point labels). At most one Manim scene in every three. Best for maths and physics (NEET / JEE):
                graphs, vectors, motion, waves, trigonometry, algebra steps.
                """;
    }

    /** The system prompt with the story craft appended. */
    public static String withCraft(String system, int totalSeconds) {
        return system + "\n\n" + storyCraft(totalSeconds);
    }

    // ------------------------------------------------------------------ concepts first (the proposal step)

    public static String conceptsSystemPrompt() {
        return """
                You are a creative director pitching video concepts to a creator before anything is made.
                Pitch exactly 3 concepts for the topic. They must be genuinely different from each other (different hook type AND different
                angle), not three versions of the same idea. Every premise must be true and checkable; do not invent statistics.
                Return JSON only:
                {"concepts":[{"title":"short working title",
                              "hookType":"contrarian|outcome|mystery|stakes",
                              "hook":"the exact opening line, one sentence",
                              "angle":"the one idea, misconception or story the video is built on",
                              "approach":"story-first | myth-buster | guided discovery | compare and contrast | build from scratch | analogy-first | mistake-driven | numbers-first",
                              "visualIdea":"what the viewer mostly sees",
                              "whyItWorks":"one sentence"}]}
                """;
    }

    public static String conceptsUserPrompt(String topic, String language, String duration, String difficulty, String audience,
                                            String outcome, String tone, String trackNotes) {
        StringBuilder sb = new StringBuilder("Topic: ").append(topic).append("\nLength: ").append(duration).append("\nLevel: ").append(difficulty);
        sb.append("\nNarration language: ").append(language).append(" (on-screen text will be English)");
        if (audience != null && !audience.isBlank()) sb.append("\nAudience: ").append(audience);
        if (outcome != null && !outcome.isBlank()) sb.append("\nAfter watching they should: ").append(outcome);
        if (tone != null && !tone.isBlank()) sb.append("\nTone: ").append(tone);
        if (trackNotes != null && !trackNotes.isBlank()) sb.append("\n").append(trackNotes);
        return sb.toString();
    }

    // ------------------------------------------------------------------ script doctor: rewrite flagged scenes

    public static String doctorSystemPrompt() {
        return """
                You are a script doctor for short spoken videos. You receive scenes (their narration sentences) and a list of problems per scene.
                Rewrite ONLY the scenes you are given. Rules:
                - keep EXACTLY the same number of sentences in each scene, in the same order;
                - keep the meaning, the names, the facts and the narration language; add no new facts;
                - write for the ear: short sentences, contractions are fine;
                - fix every listed problem: "weak-hook" -> open with a surprising claim, a question or a problem; "and-then" -> link with but / therefore;
                  "verify-number" -> replace the exact figure with "about"/"roughly" wording or drop it unless it is common knowledge;
                  "long-sentence" / "long-hook" -> shorten without losing the point; "repetitive" -> vary the openings;
                  "too-long" -> cut filler and repetition, keep every key idea; "not-direct" / "no-questions" -> talk to the child ("you", "let's") and ask a question.
                Return JSON only: {"scenes":[{"scene":N,"narration":["sentence 1","sentence 2"]}]}
                """;
    }

    public static String doctorUserPrompt(String scenesJson, int shrinkWords) {
        return "Language of the narration: keep it. Scenes to fix:\n" + scenesJson
                + (shrinkWords > 0 ? "\nThe whole script is about " + shrinkWords + " words too long: make these scenes shorter, about " + shrinkWords + " words in total." : "");
    }
}
