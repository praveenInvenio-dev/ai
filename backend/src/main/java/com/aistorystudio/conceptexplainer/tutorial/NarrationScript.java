package com.aistorystudio.conceptexplainer.tutorial;

import com.aistorystudio.h3.IndicSpeech;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps Indian-language narration in its own script. A small local model asked for "Hindi" often answers in Latin letters
 * ("Ek cricket ball bowler ke haath se nikal kar ..."); a Hindi voice then reads it as if it were English and sounds terrible, and nothing
 * downstream notices. This class detects that case (built on {@link IndicSpeech}, which the skit flow already relies on), builds the repair
 * prompts, and recognises romanised Hindi / Marathi on screen, where only English is meant to appear.
 */
public final class NarrationScript {

    private NarrationScript() { }

    /** True when the language is one of the supported Indian languages (so its narration must be in that language's own script). */
    public static boolean expectsNativeScript(String language) {
        return language != null && !language.isBlank() && IndicSpeech.resolve(language, null).indic();
    }

    /** "Devanagari", "Kannada" ... or null when the language is not Indian. */
    public static String scriptName(String language) {
        return expectsNativeScript(language) ? IndicSpeech.resolve(language, null).script() : null;
    }

    /** True when {@code sentence} should be in the language's own script but is mostly Latin letters. */
    public static boolean romanized(String language, String sentence) {
        if (!expectsNativeScript(language) || sentence == null) return false;
        IndicSpeech.Language l = IndicSpeech.resolve(language, sentence);
        return l.indic() && l.romanized();
    }

    /** The prompt paragraph that states the script rule (appended to the language rule for Indian languages). */
    public static String rule(String language) {
        String script = scriptName(language);
        if (script == null) return "";
        String example = switch (language.trim().toLowerCase(Locale.ROOT)) {
            case "hindi" -> " Example: write \"\u092f\u0939 \u090f\u0915 \u0932\u094b\u0921 \u092c\u0948\u0932\u0947\u0902\u0938\u0930 \u0939\u0948\", never \"Yeh ek load balancer hai\".";
            default -> "";
        };
        return "\nSCRIPT RULE (the voice cannot read anything else): every narration sentence is written ONLY in " + script + " script, never in Latin letters, "
                + "never romanised / Hinglish." + example + " English technical words are written the way a " + language + " speaker says them, in " + script
                + " script (force, inertia, load balancer ...); acronyms are spelled out in " + script + " letters.";
    }

    // ------------------------------------------------------------------------------------------------ repair prompts

    public static String repairSystemPrompt(String language) {
        String script = scriptName(language);
        return "You fix narration for a text-to-speech voice. The lines you receive are " + language + " written in Latin letters (romanised / Hinglish), "
                + "which a " + language + " voice cannot pronounce. Rewrite EVERY line in " + language + " using ONLY " + script + " script.\n"
                + "Rules:\n- keep the meaning, the order and the number of lines exactly;\n- natural, spoken " + language + ", short sentences;\n"
                + "- write English technical words as a " + language + " speaker says them, in " + script + " script (e.g. force, inertia, load balancer);\n"
                + "- spell acronyms with " + script + " letters; digits may stay as digits;\n- no Latin letters at all, no notes, no quotes.\n"
                + "Return JSON only: {\"scenes\":[{\"scene\":N,\"lines\":[\"...\"]}]}";
    }

    public static String repairUserPrompt(String scenesJson) {
        return "Rewrite these lines (keep each scene's line count):\n" + scenesJson;
    }

    public static String translateOnScreenSystemPrompt() {
        return "You turn short on-screen texts that were written in romanised Hindi / Marathi (Latin letters) into plain, natural ENGLISH of about the same length. "
                + "Keep numbers, formulas and technical terms. Return JSON only: {\"texts\":[\"...\"]} with one English text per input, same order and count.";
    }

    // ------------------------------------------------------------------------------------------------ on-screen text

    /** Words that are everyday romanised Hindi / Marathi but not English (ambiguous ones such as "tab", "par", "do", "ab" are left out). */
    private static final Set<String> ROMAN_WORDS = Set.of("ek", "ke", "ki", "ka", "hai", "hain", "hota", "hoti", "hote", "se", "mein", "nahi", "nahin", "kar", "karke",
            "aur", "yeh", "ye", "woh", "wo", "kya", "kyun", "kyon", "kaise", "liye", "jab", "bhi", "toh", "matlab", "haath", "nikal", "nikalkar", "agar", "lekin",
            "phir", "sirf", "bahut", "zyada", "apne", "hum", "aap", "mera", "tera", "uska", "unka", "koi", "kuch", "sab", "wahan", "yahan", "tak", "saath", "bina",
            "jaise", "chahiye", "sakta", "sakti", "andar", "bahar", "upar", "neeche", "aage", "peeche", "dono", "pehle", "baad", "hamesha", "kabhi", "chalo",
            "dekho", "suno", "samjho", "taraf", "jaata", "jaati", "jaate", "rehta", "rehti", "milta", "milti", "lagta", "lagti", "dikhta", "banta", "banti", "hoga",
            "hogi", "raha", "rahi", "rahe", "wala", "wali", "wale", "isliye", "kyunki", "magar", "abhi", "bohot", "thoda", "zyaada", "ghar", "paani", "log", "cheez");

    /** True when a short text reads like romanised Hindi / Marathi (at least two distinct typical words and a fair share of the words). */
    public static boolean looksRomanizedIndic(String text) {
        if (text == null) return false;
        String[] tokens = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]", " ").trim().split("\\s+");
        if (tokens.length < 3) return false;
        java.util.Set<String> distinct = new java.util.HashSet<>();
        int hits = 0;
        for (String t : tokens) if (ROMAN_WORDS.contains(t)) { hits++; distinct.add(t); }
        return distinct.size() >= 2 && hits >= tokens.length * 0.22;
    }

    /** Convenience for tests / reports: how many of these sentences are romanised for the language. */
    public static int countRomanized(String language, List<String> sentences) {
        int n = 0;
        for (String s : sentences) if (romanized(language, s)) n++;
        return n;
    }
}
