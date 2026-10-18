package com.aistorystudio.conceptexplainer.speech;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes an English narration line read the way a person talks. Small language models write essays: "It is important to
 * note that we do not utilize threads. Furthermore, ..." A voice reads that perfectly and it still sounds like a manual.
 *
 * <p>Rewrites only what is safe and meaning-neutral: contractions ("do not" to "don't"), stiff connectors ("Furthermore,"
 * to "Also,"), and a handful of formal words ("utilize" to "use"). It never touches a sentence that is not English, never
 * contracts where the contraction would be wrong ("Yes, it is." / "I know what it is"), and never changes a word written in
 * capitals for emphasis ("do NOT"). Pure Java, deterministic, idempotent.</p>
 */
public final class SpeechHumanizer {

    private SpeechHumanizer() { }

    private static final Pattern ENGLISH_HINT = Pattern.compile("(?i)\\b(the|is|are|a|an|to|of|and|that|it|this|you|for|with|how|what|we|do|not)\\b");
    private static final Pattern HINGLISH_HINT = Pattern.compile("(?i)\\b(hai|hain|ke|ki|ka|nahi|aur|yeh|kya|toh|mein|karna|hota)\\b");

    /** Negations: the left side is matched case-sensitively in lower case, so "do NOT" is left alone. */
    private static final Map<String, String> NEGATIONS = new LinkedHashMap<>();
    static {
        NEGATIONS.put("do not", "don't"); NEGATIONS.put("does not", "doesn't"); NEGATIONS.put("did not", "didn't");
        NEGATIONS.put("is not", "isn't"); NEGATIONS.put("are not", "aren't"); NEGATIONS.put("was not", "wasn't"); NEGATIONS.put("were not", "weren't");
        NEGATIONS.put("will not", "won't"); NEGATIONS.put("would not", "wouldn't"); NEGATIONS.put("should not", "shouldn't"); NEGATIONS.put("could not", "couldn't");
        NEGATIONS.put("have not", "haven't"); NEGATIONS.put("has not", "hasn't"); NEGATIONS.put("had not", "hadn't"); NEGATIONS.put("cannot", "can't"); NEGATIONS.put("can not", "can't");
    }

    /** Pronoun + verb: only contracted when another word follows ("it is working" yes, "yes it is." no). */
    private static final Map<String, String> PRONOUNS = new LinkedHashMap<>();
    static {
        PRONOUNS.put("it is", "it's"); PRONOUNS.put("that is", "that's"); PRONOUNS.put("there is", "there's"); PRONOUNS.put("what is", "what's");
        PRONOUNS.put("here is", "here's"); PRONOUNS.put("who is", "who's"); PRONOUNS.put("let us", "let's");
        PRONOUNS.put("you are", "you're"); PRONOUNS.put("we are", "we're"); PRONOUNS.put("they are", "they're");
        PRONOUNS.put("you will", "you'll"); PRONOUNS.put("we will", "we'll"); PRONOUNS.put("they will", "they'll"); PRONOUNS.put("it will", "it'll");
        PRONOUNS.put("you have", "you've"); PRONOUNS.put("we have", "we've"); PRONOUNS.put("they have", "they've");
        PRONOUNS.put("you would", "you'd"); PRONOUNS.put("we would", "we'd");
    }

    /** Stiff sentence openers and their spoken replacements. */
    private static final Map<String, String> OPENERS = new LinkedHashMap<>();
    static {
        OPENERS.put("Furthermore", "Also"); OPENERS.put("Moreover", "And"); OPENERS.put("Additionally", "Also"); OPENERS.put("In addition", "Also");
        OPENERS.put("In conclusion", "So"); OPENERS.put("To summarize", "So"); OPENERS.put("To sum up", "So"); OPENERS.put("Therefore", "So");
        OPENERS.put("Thus", "So"); OPENERS.put("Hence", "So"); OPENERS.put("Consequently", "So"); OPENERS.put("Subsequently", "Then");
        OPENERS.put("However", "But"); OPENERS.put("Nevertheless", "Still"); OPENERS.put("Nonetheless", "Still"); OPENERS.put("Firstly", "First");
        OPENERS.put("Secondly", "Second"); OPENERS.put("Thirdly", "Third"); OPENERS.put("It is important to note that", "Note that");
        OPENERS.put("It is worth noting that", "Note that"); OPENERS.put("It should be noted that", "Note that");
    }

    private static final Map<String, String> FORMAL = new LinkedHashMap<>();
    static {
        FORMAL.put("utilizes", "uses"); FORMAL.put("utilize", "use"); FORMAL.put("utilizing", "using"); FORMAL.put("utilized", "used");
        FORMAL.put("commence", "start"); FORMAL.put("commences", "starts"); FORMAL.put("numerous", "many"); FORMAL.put("prior to", "before");
        FORMAL.put("in order to", "to"); FORMAL.put("a variety of", "lots of"); FORMAL.put("due to the fact that", "because");
        FORMAL.put("in the event that", "if"); FORMAL.put("with regard to", "about"); FORMAL.put("demonstrates", "shows"); FORMAL.put("demonstrate", "show");
        FORMAL.put("approximately", "about"); FORMAL.put("subsequently", "then"); FORMAL.put("terminate", "stop"); FORMAL.put("terminates", "stops");
    }

    private static final java.util.Set<String> COMMA_KEPT = java.util.Set.of("So", "Also", "Still", "Then", "First", "Second", "Third");
    private static final Pattern OPENER = buildOpenerPattern();
    private static final Pattern FORMAL_P = Pattern.compile("(?<![\\w'-])(" + String.join("|", FORMAL.keySet().stream().map(Pattern::quote).toList()) + ")(?![\\w'-])");

    private static Pattern buildOpenerPattern() {
        return Pattern.compile("^(" + String.join("|", OPENERS.keySet().stream().map(Pattern::quote).toList()) + ")(,?)\\s+");
    }

    /** True when the sentence is English prose (Latin letters, English function words, not romanised Hindi). */
    public static boolean looksEnglish(String s) {
        if (s == null || s.length() < 6) return false;
        long letters = s.chars().filter(Character::isLetter).count();
        long latin = s.chars().filter(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')).count();
        return letters > 0 && latin >= letters * 0.95 && ENGLISH_HINT.matcher(s).find() && !HINGLISH_HINT.matcher(s).find();
    }

    /** The humanized line; the input unchanged when it is not English or is already natural. */
    public static String humanize(String sentence) {
        if (!looksEnglish(sentence)) return sentence;
        String t = sentence;
        Matcher o = OPENER.matcher(t);
        if (o.find()) {
            String repl = OPENERS.get(o.group(1));
            // "However, the lock" -> "But the lock"; "Therefore," -> "So,"
            boolean keepComma = COMMA_KEPT.contains(repl) && o.group(2).equals(",");
            t = repl + (keepComma ? ", " : " ") + t.substring(o.end());
        }
        t = FORMAL_P.matcher(t).replaceAll(m -> Matcher.quoteReplacement(FORMAL.get(m.group(1))));
        for (Map.Entry<String, String> e : NEGATIONS.entrySet()) t = replaceWord(t, e.getKey(), e.getValue(), false);
        for (Map.Entry<String, String> e : PRONOUNS.entrySet()) t = replaceWord(t, e.getKey(), e.getValue(), true);
        t = replaceWord(t, "I am", "I'm", true);
        t = replaceWord(t, "I will", "I'll", true);
        t = replaceWord(t, "I have", "I've", true);
        return t;
    }

    /**
     * Replaces {@code phrase} (any case of the first letter, never when written in capitals) with {@code contraction}.
     * With {@code needsFollower} the replacement only happens when another word follows, so "Yes, it is." stays intact.
     */
    private static String replaceWord(String text, String phrase, String contraction, boolean needsFollower) {
        String first = phrase.substring(0, 1);
        String regex = "(?<![\\w'])(?:" + first.toLowerCase(Locale.ROOT) + "|" + first.toUpperCase(Locale.ROOT) + ")" + Pattern.quote(phrase.substring(1))
                + (needsFollower ? "(?=\\s+[A-Za-z0-9\"'(])" : "(?![\\w'])");
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String found = m.group();
            boolean capital = Character.isUpperCase(found.charAt(0));
            String rep = capital ? Character.toUpperCase(contraction.charAt(0)) + contraction.substring(1) : contraction;
            if (phrase.startsWith("I ")) rep = contraction;     // "I'm" keeps its capital I
            // "that is" inside "that is, ..." reads as a definition marker, keep it formal
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
