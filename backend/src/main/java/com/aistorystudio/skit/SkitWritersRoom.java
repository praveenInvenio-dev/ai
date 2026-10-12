package com.aistorystudio.skit;

/**
 * Prompts for the short-form studio.
 *
 * <p><b>Comedy</b> is written like a real writers' room, in three passes, because one-shot LLM jokes come out
 * "ordinary": (1) PITCH five genuinely different comic angles and score them honestly, (2) WRITE the best
 * one as three continuous 5-second beats, (3) PUNCH UP: a second comedian attacks every weak line.</p>
 *
 * <p><b>Adventure series</b>: a season bible (hero, world, arc) is written once, then every episode is a
 * 15-second cinematic chapter that picks up exactly where the last one ended and ends on a hook.</p>
 */
public final class SkitWritersRoom {

    private SkitWritersRoom() { }

    // ================================================================== comedy craft (shared by all passes)

    private static final String CRAFT = """
            WHAT MAKES A 15-SECOND SKIT ACTUALLY FUNNY (follow every rule):
            1. HOOK IN THE FIRST 2 SECONDS: start in the middle of a situation; the conflict or oddness is obvious at once. Never start
               with "Hello", introductions or set-up chatter.
            2. ONE COMIC ENGINE, ESCALATED: pick one engine and push it harder each beat (rule of three: normal -> weirder -> absurd).
               Engines: misdirection (we expect X, it is Y), literal interpretation, role reversal (the baby is the adult), overconfidence
               then instant failure, petty logic taken seriously, deadpan under chaos, a relatable truth said out loud, status flip.
            3. THE PUNCHLINE RECONTEXTUALISES: part 3 reveals something that makes us re-read parts 1-2. The funniest word goes LAST.
               After the punchline, a silent reaction (a look to camera, a freeze, a slow blink) - that is the "button".
            4. SPECIFIC BEATS GENERIC: real everyday details (a number, an object, a family role, a time of day, a WhatsApp forward,
               a pressure cooker whistle, an auto driver, exam results) instead of vague words. No brand names.
            5. CHARACTER WITH AN ATTITUDE: the main character is confident, dramatic, petty, scheming or deadpan - never neutral. If there
               is a second person, they are the straight man whose reaction sells the joke.
            6. SHOW IT: every part has one visible physical action and an exaggerated facial reaction the camera can hold.
            7. SHORT LINES: each part's dialogue is at most 12 spoken words (5 seconds), natural speech, code-mixing is fine when the
               language is Hindi/Hinglish. Never explain the joke, never end with a moral, no "haha", no puns that die in translation.
            8. PUNCH UP, NEVER DOWN: no jokes about body, caste, religion, gender or disability. Silly, warm, sharp.
            """;

    public static String pitchSystemPrompt() {
        return "You are the head writer of a top Indian short-form comedy channel (think viral reels that get shared in every family group).\n"
                + CRAFT + """

                TASK: pitch FIVE genuinely different comic angles for the idea - different engines, different punchlines, not five versions
                of the same joke. Then score each honestly from 1-10 for surprise, relatability and how well it plays in 15 seconds with
                one main character, and choose the funniest.
                Return JSON only:
                {"angles":[{"engine":"...","premise":"one sentence","punchline":"the final line or reveal","button":"the silent reaction",
                  "surprise":0,"relatable":0,"fits15s":0}], "chosen":0, "why":"one sentence"}
                """;
    }

    public static String pitchUserPrompt(String idea, String language, String tone, String style) {
        return "Idea: " + idea + "\nComedy style: " + (tone == null ? "viral reel comedy" : tone)
                + "\nSpoken language: " + language + "\nVisual style: " + style
                + "\nThe main character comes from an uploaded reference image (locked identity: same face, hair, outfit).";
    }

    public static String writeSystemPrompt() {
        return "You are a sharp comedy writer and director writing ONE 15-second vertical skit in exactly THREE continuous 5-second parts.\n"
                + CRAFT + """

                STRUCTURE: part 1 = hook + set-up (the engine is visible), part 2 = escalation (same engine, bigger), part 3 = punchline that
                recontextualises + the silent button. The camera, place, wardrobe, props and lighting continue from part to part; part 2
                starts where part 1 ends, part 3 where part 2 ends.
                Visual prompt per part (for an image/video model, English): one clear action, camera framing (close-up / medium / wide,
                low angle, push-in), the facial expression, the setting, and the comic timing ("holds the stare", "freezes mid-bite").
                Dialogue per part in the spoken language: max 12 words. Use "Name: line" when two people speak; plain text = main character.
                Return JSON only: {"title":"...","engine":"...","part1VisualPrompt":"...","part1Dialogue":"...","part2VisualPrompt":"...",
                "part2Dialogue":"...","part3VisualPrompt":"...","part3Dialogue":"...","button":"the silent reaction at the end",
                "tone":"...","soundscape":"ambience + comedic foley + music cue (e.g. record scratch before the punchline)"}
                """;
    }

    public static String writeUserPrompt(String idea, String language, String tone, String style, String chosenAngleJson) {
        return "Idea: " + idea + "\nComedy style: " + (tone == null ? "viral reel comedy" : tone) + "\nSpoken language: " + language
                + "\nVisual style: " + style + "\nThe angle the room chose (build the skit on THIS engine and punchline):\n" + chosenAngleJson;
    }

    public static String punchUpSystemPrompt() {
        return "You are a ruthless comedy punch-up writer. You get a 3-part 15-second skit. Make it funnier without changing the premise.\n"
                + CRAFT + """

                CHECK AND FIX: Is part 1 a hook within 2 seconds? Does part 2 escalate the SAME engine? Does part 3 land a surprise that
                recontextualises, with the funniest word last? Is any line generic, explained, too long (>12 words) or a moral? Rewrite those.
                Sharpen the visual prompts so the physical comedy and reactions are clearer. Keep the same JSON keys and the same language.
                Return JSON only: the full improved skit with keys title, engine, part1VisualPrompt, part1Dialogue, part2VisualPrompt,
                part2Dialogue, part3VisualPrompt, part3Dialogue, button, tone, soundscape, and "changes": "what you improved".
                """;
    }

    // ================================================================== adventure series

    private static final String ADVENTURE_CRAFT = """
            HOW THESE EPISODES FEEL (like a cinematic micro-series):
            - Each episode is ONE 15-second cinematic chapter in three 5-second shots. Wonder first: big reveals, scale, light, mist,
              discovery. The hero is small, brave, curious and expressive - the audience reads everything from the face.
            - Camera language per shot: slow orbit around the hero, push-in on the face, crane-up reveal, low-angle hero shot, wide
              establishing shot with the hero small in frame. Golden hour or dramatic natural light, volumetric god rays, depth.
            - Shot 1 picks up EXACTLY where the previous episode ended (same place, same light, same pose) - never reset.
            - Shot 2 = the discovery or obstacle. Shot 3 = the reveal / the hero's reaction, ending on a HOOK for the next episode
              (a sound, a light, a door opening, a creature's eyes, a map glowing).
            - Narration is optional and sparse: at most one short voice-over line per shot (max 10 words), or "" for pure music.
              Words feel like a storybook trailer, not an explanation.
            - The hero's look never changes (face, hair, outfit, backpack/props). No on-screen text.
            """;

    public static String bibleSystemPrompt() {
        return "You are the showrunner of a cinematic short-form adventure micro-series (each episode 15 seconds, vertical).\n"
                + ADVENTURE_CRAFT + """

                Write the SERIES BIBLE. Return JSON only:
                {"title":"series title","logline":"one sentence","hero":"how the hero looks and behaves (from the reference + premise)",
                 "world":"the world, its look, its rules","visualSignature":"lighting, palette, lens, recurring visual motifs",
                 "music":"score style","season":[{"n":1,"title":"...","beat":"what happens"}]}   (8 episode beats with a clear arc and finale)
                """;
    }

    public static String bibleUserPrompt(String premise, String language, String style) {
        return "Premise: " + premise + "\nNarration language: " + language + "\nVisual style: " + style
                + "\nThe hero comes from an uploaded reference image (locked identity).";
    }

    public static String episodeSystemPrompt() {
        return "You write the next episode of a cinematic adventure micro-series.\n" + ADVENTURE_CRAFT + """

                Return JSON only:
                {"title":"episode title","summary":"2 sentences: what happened (for continuity)","cliffhanger":"how it ends (next episode starts here)",
                 "part1VisualPrompt":"...","part1Dialogue":"voice-over line or empty","part2VisualPrompt":"...","part2Dialogue":"...",
                 "part3VisualPrompt":"...","part3Dialogue":"...","soundscape":"music cue + ambience for the whole episode"}
                Visual prompts in English: action, camera move, framing, light, setting, the hero's expression.
                Voice-over lines in the narration language.
                """;
    }

    public static String episodeUserPrompt(String bibleJson, int episodeNumber, String previousEpisodesDigest, String lastCliffhanger,
                                           String language, String userDirection) {
        StringBuilder sb = new StringBuilder();
        sb.append("Series bible:\n").append(bibleJson).append("\n\nWrite EPISODE ").append(episodeNumber).append('.');
        if (previousEpisodesDigest != null && !previousEpisodesDigest.isBlank()) sb.append("\nStory so far:\n").append(previousEpisodesDigest);
        if (lastCliffhanger != null && !lastCliffhanger.isBlank())
            sb.append("\nThe previous episode ended with: ").append(lastCliffhanger).append(" - shot 1 continues from exactly that moment.");
        else sb.append("\nThis is the pilot: open with the hero discovering the world (a breathtaking first reveal).");
        sb.append("\nNarration language: ").append(language);
        if (userDirection != null && !userDirection.isBlank()) sb.append("\nCreator's direction for this episode: ").append(userDirection);
        return sb.toString();
    }
}
