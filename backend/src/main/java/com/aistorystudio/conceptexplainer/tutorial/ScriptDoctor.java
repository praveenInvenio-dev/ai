package com.aistorystudio.conceptexplainer.tutorial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Script review before any GPU time is spent (idea borrowed from OpenMontage's script self-evaluation: count the words, check the
 * hook, check the pacing, never invent facts). Everything here is deterministic, so the review is instant and repeatable; the
 * optional "fix flagged scenes" pass that follows is the only part that uses the language model.
 *
 * <p>Checks: word budget against the chosen length; a hook instead of a greeting; a hook that arrives quickly; "and then" chains;
 * sentences too long to say in one breath; the same sentence opener again and again; numbers and dates that nobody verified;
 * mood adjectives where a picture description should say what is seen; too many new ideas for the time; and, for the kids age
 * bands, talking TO the child and sentence length.</p>
 */
public final class ScriptDoctor {

    private ScriptDoctor() { }

    public enum Severity { WARN, ERROR }

    /** @param scene 1-based scene number, 0 = the whole script */
    public record Issue(int scene, Severity severity, String code, String message, boolean fixable) { }

    /** @param section HOOK / CONCEPT / ... (may be null); @param picture the scene's picture description (kids lessons) or null */
    public record SceneInput(int number, String section, List<String> sentences, String picture, int spokenWords) {
        public SceneInput(int number, String section, List<String> sentences, String picture) { this(number, section, sentences, picture, -1); }
    }

    public record Report(List<Issue> issues, int words, int seconds) {
        public boolean clean() { return issues.isEmpty(); }
        public boolean has(String code) { return issues.stream().anyMatch(i -> i.code().equals(code)); }
    }

    private static final Pattern GREETING = Pattern.compile(
            "^(?:in this (?:video|lesson|short|reel|tutorial|clip)|hello|hi\\b|hey\\b|welcome|today (?:we|i)(?:'ll| will| are| 'm)?\\b|"
                    + "good (?:morning|evening|afternoon)|let'?s (?:start|begin|get started|learn|dive)|so,? (?:today|in this)|"
                    + "i(?:'m| am) going to (?:show|teach|explain)|have you ever wondered about the definition)",
            Pattern.CASE_INSENSITIVE);
    /** Greeting / announcement phrases anywhere in the opening sentence (the app's own scrub can leave "And welcome back to ..." behind). */
    private static final Pattern GREETING_ANYWHERE = Pattern.compile(
            "\\b(?:welcome (?:back|to)|in this (?:video|lesson|short|reel|tutorial|clip)|today (?:we|i)(?:'ll| will| are| am|'re|'m)? (?:going to|gonna|will)|"
                    + "(?:hey|hello|hi) (?:guys|everyone|friends|folks)|smash that|like and subscribe|subscribe to)\\b", Pattern.CASE_INSENSITIVE);
    /** Empty phrases that sound like teaching but say nothing ("that's where the real magic happens", "game changer", "a nightmare"). */
    private static final Pattern FILLER = Pattern.compile(
            "\\b(?:real magic|the magic (?:happens|is)|magic happens|game[- ]?changer|a nightmare|mind[- ]?blowing|supercharge|unlock the power|take (?:it|your \\w+) to the next level|"
                    + "in today'?s fast[- ]paced world|seamlessly|revolutioni[sz]e|it'?s that simple)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AND_THEN = Pattern.compile("\\band then\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_CLAIM = Pattern.compile(
            "(?<![\\w.])(?:\\d[\\d,.]*\\s?(?:%|percent\\b|million\\b|billion\\b|trillion\\b|thousand\\b|crore\\b|lakh\\b|x\\b|times\\b|years?\\b|ms\\b|gb\\b|mb\\b|tb\\b|k\\b|b\\b)"
                    + "|(?:19|20)\\d{2}\\b|\\d{4,}\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOOD_WORDS = Pattern.compile(
            "\\b(epic|magical|magic|stunning|beautiful|inspiring|inspirational|breathtaking|amazing|moody|dreamy|cinematic|awe-?inspiring|powerful|majestic)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT_ADDRESS = Pattern.compile("\\b(you|your|you're|let'?s|we|can you|do you)\\b", Pattern.CASE_INSENSITIVE);
    /** Scenes that introduce something new (steps of one idea and the payoff do not count as new ideas). */
    private static final List<String> CONCEPT_SECTIONS = List.of("CONCEPT", "HOW_IT_WORKS", "ECOSYSTEM", "NAME");

    /**
     * @param minWords / maxWords word budget for the chosen length
     * @param wpm       narrator pace used to estimate the running time (about 155 explainer, 175 reel / breakdown)
     * @param kidsBand  0 = not a kids lesson, 1 = ages 2-5, 2 = ages 5-10, 3 = ages 10-15
     * @param textIsEnglish true when {@code scenes} sentences are English (checks that rely on English phrases only run then)
     */
    public static Report review(List<SceneInput> scenes, int minWords, int maxWords, double wpm, int kidsBand, boolean textIsEnglish) {
        List<Issue> out = new ArrayList<>();
        int words = 0, sentenceCount = 0;
        for (SceneInput s : scenes) {
            int w = 0;
            for (String line : s.sentences()) { w += wordCount(line); sentenceCount++; }
            words += s.spokenWords() >= 0 ? s.spokenWords() : w;   // the budget counts the words actually spoken (captions may be English)
        }
        int seconds = (int) Math.round(words * 60.0 / Math.max(100, wpm) + sentenceCount * 0.3);

        // ---- word budget (the #1 cause of a video that runs 40 s when 60 s was asked for, or 100 s for 60)
        if (maxWords > 0 && words > maxWords * 1.15) {
            out.add(new Issue(0, Severity.WARN, "too-long", String.format(Locale.ROOT,
                    "The script has %d words (about %d s spoken) but %d-%d words fit the chosen length - about %d words too many.",
                    words, seconds, minWords, maxWords, words - maxWords), true));
        } else if (minWords > 0 && words < minWords * 0.85) {
            out.add(new Issue(0, Severity.WARN, "too-short", String.format(Locale.ROOT,
                    "The script has only %d words (about %d s spoken) and %d-%d words fit the chosen length - about %d words short.",
                    words, seconds, minWords, maxWords, minWords - words), false));
        }

        // ---- hook
        if (!scenes.isEmpty() && !scenes.get(0).sentences().isEmpty() && textIsEnglish) {
            String first = scenes.get(0).sentences().get(0).trim();
            Matcher g = GREETING.matcher(first);
            if (g.find() || GREETING_ANYWHERE.matcher(first).find()) {
                out.add(new Issue(1, Severity.ERROR, "weak-hook", "Scene 1 opens with “" + clip(first, 60) + "” - open with a hook "
                        + "(a surprising claim, a question or a problem), not a greeting or an announcement.", true));
            }
            if (wordCount(first) > 24) {
                out.add(new Issue(1, Severity.WARN, "long-hook", "The first sentence is " + wordCount(first) + " words long - a hook should be one short, "
                        + "punchy line.", true));
            }
        }
        if (!scenes.isEmpty()) {   // the hook + set-up should be done early
            double t = 0;
            int firstTeaching = -1;
            for (int i = 0; i < scenes.size(); i++) {
                String sec = scenes.get(i).section() == null ? "" : scenes.get(i).section().toUpperCase(Locale.ROOT);
                if (i > 0 && CONCEPT_SECTIONS.contains(sec)) { firstTeaching = i; break; }
            }
            if (firstTeaching > 0) {
                for (int i = 0; i < firstTeaching; i++) for (String l : scenes.get(i).sentences()) t += wordCount(l) * 60.0 / Math.max(100, wpm);
                double limit = seconds <= 90 ? 20 : 40;
                if (t > limit) out.add(new Issue(1, Severity.WARN, "slow-start", String.format(Locale.ROOT,
                        "About %.0f s pass before the lesson really starts - most viewers leave in the first 30 s, so get to the point sooner.", t), false));
            }
        }

        // ---- per scene
        int andThenTotal = 0;
        for (SceneInput s : scenes) {
            List<String> ss = s.sentences();
            int andThen = 0;
            for (int i = 0; i < ss.size(); i++) {
                String line = ss.get(i).trim();
                int w = wordCount(line);
                int cap = kidsBand == 1 ? 8 : kidsBand == 2 ? 14 : 30;
                if (w > cap) {
                    out.add(new Issue(s.number(), Severity.WARN, "long-sentence", "Scene " + s.number() + ", sentence " + (i + 1) + " has " + w
                            + " words - " + (kidsBand == 1 ? "toddlers follow 3-7 word sentences" : kidsBand == 2 ? "children follow 4-12 word sentences"
                            : "spoken sentences work best under 25 words") + ". Split it.", true));
                }
                if (textIsEnglish) {
                    Matcher a = AND_THEN.matcher(line);
                    while (a.find()) andThen++;
                    Matcher f = FILLER.matcher(line);
                    if (f.find()) out.add(new Issue(s.number(), Severity.WARN, "filler-phrase", "Scene " + s.number() + ", sentence " + (i + 1) + " uses the empty phrase “"
                            + clip(f.group(), 30) + "”. Say the concrete thing instead (what happens, to what, and why it matters).", true));
                }
                Matcher n = NUMBER_CLAIM.matcher(line);
                if (n.find()) {
                    out.add(new Issue(s.number(), Severity.WARN, "verify-number", "Scene " + s.number() + " says “" + clip(n.group(), 30) + "” - the app cannot "
                            + "check facts, so keep specific numbers and dates only if you are sure they are right (or say “about”).", true));
                }
            }
            andThenTotal += andThen;
            if (andThen >= 2) out.add(new Issue(s.number(), Severity.WARN, "and-then", "Scene " + s.number() + " chains ideas with “and then”. "
                    + "Link them with “but” or “therefore” so each step follows from the last.", true));

            // same opener 3 times in a row
            int run = 1;
            for (int i = 1; i < ss.size(); i++) {
                run = opener(ss.get(i)).equals(opener(ss.get(i - 1))) && !opener(ss.get(i)).isEmpty() ? run + 1 : 1;
                if (run == 3) {
                    out.add(new Issue(s.number(), Severity.WARN, "repetitive", "Scene " + s.number() + " starts three sentences in a row with “"
                            + opener(ss.get(i)) + "” - vary the openings.", true));
                    break;
                }
            }
            if (s.picture() != null) {
                Matcher m = MOOD_WORDS.matcher(s.picture());
                if (m.find()) out.add(new Issue(s.number(), Severity.WARN, "mood-picture", "The picture for scene " + s.number() + " says “" + m.group()
                        + "” - describe what is seen (who, doing what, the framing, the light, the expression), because a mood word does not "
                        + "tell the image model what to draw.", false));
            }
        }
        if (andThenTotal >= 4) out.add(new Issue(0, Severity.WARN, "and-then-total", "“And then” appears " + andThenTotal
                + " times in the script - stories hold attention when each step is a “but” or a “therefore”.", false));

        // ---- too many new ideas for the time
        int concepts = 0;
        for (SceneInput s : scenes) if (s.section() != null && CONCEPT_SECTIONS.contains(s.section().toUpperCase(Locale.ROOT))) concepts++;
        if (seconds >= 40 && concepts > 0) {
            double perThirty = concepts / (seconds / 30.0);
            if (perThirty > 2.0) out.add(new Issue(0, Severity.WARN, "too-dense", String.format(Locale.ROOT,
                    "%d new-idea scenes in about %d s is a lot (a new idea every %.0f s) - viewers retain more from fewer ideas shown well.",
                    concepts, seconds, seconds / (double) concepts), false));
        }

        // ---- kids age bands
        if (kidsBand > 0 && !scenes.isEmpty()) {
            int direct = 0, questions = 0;
            for (SceneInput s : scenes) {
                boolean d = false;
                for (String l : s.sentences()) {
                    if (DIRECT_ADDRESS.matcher(l).find()) d = true;
                    if (l.trim().endsWith("?")) questions++;
                }
                if (d) direct++;
            }
            if (textIsEnglish && direct < scenes.size() * 0.5) out.add(new Issue(0, Severity.WARN, "not-direct", "Only " + direct + " of " + scenes.size()
                    + " scenes speak to the child (“you”, “let's”). Children learn more when the narrator talks with them.", true));
            if (questions == 0) out.add(new Issue(0, Severity.WARN, "no-questions", "There is no question for the child to answer - ask one, then pause. "
                    + "The app leaves a pause after every question.", true));
        }
        out.sort((x, y) -> x.scene() == y.scene() ? x.severity().compareTo(y.severity()) * -1 : Integer.compare(x.scene(), y.scene()));
        return new Report(out, words, seconds);
    }

    /** Default silence beats: a pause after the sentence that carries a reveal or a name, a short one after a question. */
    public static List<Double> silenceBeats(List<String> sentences, boolean kids) {
        List<Double> pauses = new ArrayList<>();
        Pattern reveal = Pattern.compile("\\b(this is called|that'?s called|is called|the answer is|here'?s the (?:key|trick|secret|catch|twist)|"
                + "that'?s why|and that'?s the|so the (?:answer|real reason)|the real reason)\\b", Pattern.CASE_INSENSITIVE);
        double budget = 3.0;
        for (String s : sentences) {
            double p = 0;
            String t = s == null ? "" : s.trim();
            if (reveal.matcher(t).find()) p = 1.2;
            else if (!kids && t.endsWith("?")) p = 0.5;
            if (p > budget) p = budget;
            budget -= p;
            pauses.add(p);
        }
        return pauses;
    }

    static int wordCount(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    private static String opener(String s) {
        String[] w = (s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}' ]", "")).split("\\s+");
        return w.length >= 2 ? w[0] + " " + w[1] : w.length == 1 ? w[0] : "";
    }

    private static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n).trim() + "..."; }
}
