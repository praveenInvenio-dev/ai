package com.aistorystudio.h3;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text helpers that make MiniMax H3 speak Indian languages more reliably.
 *
 * H3 reads whatever is inside {@code <d>[Language] ...</d>}. Most pronunciation problems come
 * from the text, not the model call: wrong/missing language tag, romanised text tagged as a
 * native language, invisible Unicode junk, emoji, stage directions read aloud, digits, and
 * lines too long for one shot (the model then rushes or the clip ends mid-word). This class
 * fixes those before the prompt is built.
 *
 * Pure Java on purpose (no Spring / Jackson) so it can be unit-tested on its own.
 */
public final class IndicSpeech {

    private IndicSpeech() {
    }

    /** Resolved speaking language for a line or a scene. */
    public record Language(String name, String script, String region, boolean indic, boolean romanized,
                           double syllablesPerSecond) {
        /** Tag used inside {@code <d>[...]}. */
        public String tag() {
            return romanized ? name + ", romanized" : name;
        }
    }

    /** A line after cleanup. {@code cues} are bracketed stage directions removed from the spoken text. */
    public record Prepared(String text, List<String> cues, boolean hasDigits, boolean hasLatinWords) {
    }

    private record Spec(String name, String script, String region, int blockStart, double rate) {
    }

    // Unicode blocks are 128 code points wide; virama is always block + 0x4D.
    private static final Map<String, Spec> LANGS = new LinkedHashMap<>();

    static {
        add(new Spec("Hindi", "Devanagari", "standard North Indian Hindi", 0x0900, 4.9));
        add(new Spec("Marathi", "Devanagari", "Maharashtra", 0x0900, 4.8));
        add(new Spec("Bengali", "Bengali", "standard Kolkata Bengali", 0x0980, 4.8));
        add(new Spec("Punjabi", "Gurmukhi", "Punjab", 0x0A00, 4.8));
        add(new Spec("Gujarati", "Gujarati", "Gujarat", 0x0A80, 4.8));
        add(new Spec("Odia", "Odia", "Odisha", 0x0B00, 4.7));
        add(new Spec("Tamil", "Tamil", "Tamil Nadu", 0x0B80, 4.9));
        add(new Spec("Telugu", "Telugu", "Andhra Pradesh / Telangana", 0x0C00, 4.8));
        add(new Spec("Kannada", "Kannada", "Karnataka (Bengaluru / Mysuru)", 0x0C80, 4.6));
        add(new Spec("Malayalam", "Malayalam", "Kerala", 0x0D00, 5.0));
    }

    private static void add(Spec s) {
        LANGS.put(s.name.toLowerCase(Locale.ROOT), s);
    }

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("hi", "hindi"), Map.entry("hin", "hindi"), Map.entry("हिन्दी", "hindi"), Map.entry("हिंदी", "hindi"),
            Map.entry("hinglish", "hindi"),
            Map.entry("mr", "marathi"), Map.entry("मराठी", "marathi"),
            Map.entry("bn", "bengali"), Map.entry("bangla", "bengali"), Map.entry("বাংলা", "bengali"),
            Map.entry("pa", "punjabi"), Map.entry("ਪੰਜਾਬੀ", "punjabi"),
            Map.entry("gu", "gujarati"), Map.entry("ગુજરાતી", "gujarati"),
            Map.entry("or", "odia"), Map.entry("oriya", "odia"), Map.entry("ଓଡ଼ିଆ", "odia"),
            Map.entry("ta", "tamil"), Map.entry("தமிழ்", "tamil"),
            Map.entry("te", "telugu"), Map.entry("తెలుగు", "telugu"),
            Map.entry("kn", "kannada"), Map.entry("ಕನ್ನಡ", "kannada"),
            Map.entry("ml", "malayalam"), Map.entry("മലയാളം", "malayalam"));

    private static final Language ENGLISH = new Language("English", "Latin", "neutral Indian English", false, false, 0);

    // ------------------------------------------------------------------ language

    /**
     * Declared story language wins when it matches the script actually used. If the text is
     * clearly in another Indian script (e.g. story says "English" but lines are Kannada), the
     * script wins: tagging Kannada text as English is the #1 cause of wrong pronunciation.
     */
    public static Language resolve(String declared, String sampleText) {
        Spec declaredSpec = specFor(declared);
        boolean declaredHinglish = declared != null && declared.toLowerCase(Locale.ROOT).contains("hinglish");
        String script = dominantScript(sampleText);
        if ("Latin".equals(script) || script == null) {
            if (declaredSpec == null) return declaredHinglish ? toLanguage(LANGS.get("hindi"), true) : ENGLISH;
            // Indian language declared but written in Latin letters (Hinglish / romanised).
            return script == null ? toLanguage(declaredSpec, false) : toLanguage(declaredSpec, true);
        }
        if (declaredSpec != null && declaredSpec.script.equals(script)) return toLanguage(declaredSpec, false);
        // Script disagrees with the declaration: trust the script.
        for (Spec s : LANGS.values()) {
            if (s.script.equals(script)) return toLanguage(s, false); // Devanagari -> Hindi first
        }
        return ENGLISH;
    }

    private static Language toLanguage(Spec s, boolean romanized) {
        return new Language(s.name, s.script, s.region, true, romanized, s.rate);
    }

    private static Spec specFor(String declared) {
        if (declared == null || declared.isBlank()) return null;
        String d = declared.trim().toLowerCase(Locale.ROOT);
        int dash = d.indexOf('-');
        if (dash > 0 && dash <= 3) d = d.substring(0, dash); // kn-IN -> kn
        d = d.replace("(india)", "").replace("indian", "").trim();
        if (LANGS.containsKey(d)) return LANGS.get(d);
        String alias = ALIASES.get(d);
        if (alias != null) return LANGS.get(alias);
        for (Map.Entry<String, Spec> e : LANGS.entrySet()) {
            if (d.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /** "Latin", an Indic script name, or null when the text has no letters. */
    public static String dominantScript(String text) {
        if (text == null) return null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        int latin = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp) && Character.getType(cp) != Character.NON_SPACING_MARK
                    && Character.getType(cp) != Character.COMBINING_SPACING_MARK) continue;
            if (cp < 0x0250) { latin++; continue; }
            for (Spec s : LANGS.values()) {
                if (cp >= s.blockStart && cp < s.blockStart + 0x80) {
                    counts.merge(s.script, 1, Integer::sum);
                    break;
                }
            }
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) { best = e.getKey(); bestCount = e.getValue(); }
        }
        if (best == null && latin == 0) return null;
        return bestCount >= latin ? best : "Latin";
    }

    // ------------------------------------------------------------------ engine-aware Indic TTS voices

    /** Engine ids for {@code studio.tts.indic-engine} / INDIC_ENGINE. */
    public static final String ENGINE_INDICF5 = "indicf5", ENGINE_INDICSPEAK = "indicspeak";

    /** True for voices served by an Indic engine sidecar ("indic:..." IndicF5, "speak:..." Indic-Speak). */
    public static boolean isIndicEngineVoice(String voice) {
        return voice != null && (voice.startsWith("indic:") || voice.startsWith("speak:"));
    }

    public static boolean isIndicSpeak(String engine) {
        return engine != null && engine.trim().equalsIgnoreCase(ENGINE_INDICSPEAK);
    }

    /**
     * Indic-Speak (Bodhan AI / AI4Bharat) voices: [female, male] for the languages it ships in production.
     * Unlike IndicF5 it also speaks English and code-mixed text, and needs no reference clips.
     */
    public static List<String> indicSpeakVoices(Language lang) {
        if (lang == null) return List.of();
        return switch (lang.name()) {
            case "Hindi" -> List.of("speak:hi-Kavya", "speak:hi-Amit");
            case "Kannada" -> List.of("speak:kn-Deepika", "speak:kn-Adarsh");
            case "Tamil" -> List.of("speak:ta-Anitha", "speak:ta-Arun");
            case "Telugu" -> List.of("speak:te-Sravani", "speak:te-Vamsi");
            case "Malayalam" -> List.of("speak:ml-Lakshmi", "speak:ml-Kiran");
            case "Marathi" -> List.of("speak:mr-Anagha", "speak:mr-Chinmay");
            case "Bengali" -> List.of("speak:bn-Ishita", "speak:bn-Sourav");
            case "Gujarati" -> List.of("speak:gu-Dhara", "speak:gu-Parth");
            case "Punjabi" -> List.of("speak:pa-Kaur", "speak:pa-Manpreet");
            case "Odia" -> List.of("speak:or-Itishree", "speak:or-Akash");
            case "English" -> List.of("speak:en-Kavya", "speak:en-Amit");
            default -> List.of();
        };
    }

    /**
     * Automatic narrator voice for a story language setting ("Kannada", "Hinglish", "ta" ...) from the chosen engine,
     * or null: English, unknown languages and languages the engine lacks never get a guessed voice
     * (an unknown language name resolves to English, so it is checked against what was declared).
     */
    public static String autoVoice(String declaredLanguage, String engine) {
        if (declaredLanguage == null || declaredLanguage.isBlank()) return null;
        Language l = resolve(declaredLanguage, "");
        if (!l.indic()) return null;                                        // English / unknown
        List<String> v = indicVoices(l, engine);
        return v.isEmpty() ? null : v.get(0);                               // female voice first
    }

    /** The voices the chosen Indic engine offers for this language ([female, male]); empty = not supported by it. */
    public static List<String> indicVoices(Language lang, String engine) {
        return isIndicSpeak(engine) ? indicSpeakVoices(lang) : indicVoices(lang);
    }

    // ------------------------------------------------------------------ IndicF5 voices

    /** IndicF5 reference voices shipped in tts-indic/prompts: [female, male]. Empty = none. */
    public static List<String> indicVoices(Language lang) {
        if (lang == null) return List.of();
        return switch (lang.name()) {
            case "Hindi" -> List.of("indic:hi-in-swara", "indic:hi-in-madhur");
            case "Kannada" -> List.of("indic:kn-in-sapna", "indic:kn-in-gagan");
            case "Tamil" -> List.of("indic:ta-in-pallavi", "indic:ta-in-valluvar");
            case "Telugu" -> List.of("indic:te-in-shruti", "indic:te-in-mohan");
            case "Malayalam" -> List.of("indic:ml-in-sobhana", "indic:ml-in-midhun");
            case "Marathi" -> List.of("indic:mr-in-aarohi", "indic:mr-in-manohar");
            case "Bengali" -> List.of("indic:bn-in-tanishaa", "indic:bn-in-bashkar");
            case "Gujarati" -> List.of("indic:gu-in-dhwani", "indic:gu-in-niranjan");
            case "English" -> List.of("indic:en-in-neerja", "indic:en-in-prabhat");
            default -> List.of();
        };
    }

    // ------------------------------------------------------------------ cleanup

    private static final Pattern BRACKET_CUE = Pattern.compile("\\[([^\\]]{1,60})]|\\(((?:laugh|laughs|laughing|sigh|sighs|gasp|gasps|whisper|whispers|chuckle|chuckles|giggle|giggles|cries|crying|pause|beat)[^)]{0,30})\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGS = Pattern.compile("</?[a-zA-Z][^>]{0,40}>");
    private static final Pattern ELLIPSIS = Pattern.compile("\\.{2,}|…");
    private static final Pattern DASH = Pattern.compile("\\s+[-–—]+\\s+|—|–");
    private static final Pattern REPEAT_PUNCT = Pattern.compile("([!?।॥.,])\\1+");
    private static final Pattern DIGIT = Pattern.compile("\\p{Nd}");
    private static final Pattern LATIN_WORD = Pattern.compile("\\b[A-Za-z]{2,}\\b");
    private static final Pattern TERMINAL = Pattern.compile("[.!?।॥]$");

    /** Cleans one spoken line so H3 reads exactly the words and nothing else. */
    public static Prepared prepare(String raw, Language lang) {
        if (raw == null) return new Prepared("", List.of(), false, false);
        String t = Normalizer.normalize(raw, Normalizer.Form.NFC);
        // Invisible junk. ZWJ (200D) / ZWNJ (200C) are KEPT: they change Indic conjunct rendering.
        t = t.replaceAll("[\\u200B\\u2060\\uFEFF\\u00AD]", "");
        List<String> cues = new ArrayList<>();
        Matcher m = BRACKET_CUE.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String cue = m.group(1) != null ? m.group(1) : m.group(2);
            if (cue != null && !cue.isBlank()) cues.add(cue.trim());
            m.appendReplacement(sb, " ");
        }
        m.appendTail(sb);
        t = TAGS.matcher(sb.toString()).replaceAll(" ");
        t = t.replace("<d>", " ").replace("</d>", " ");
        // Emoji / pictographs / symbols are either read aloud or cause garbage audio.
        StringBuilder clean = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (cp >= 0x1F000 || type == Character.OTHER_SYMBOL || type == Character.SURROGATE
                    || (cp >= 0x2600 && cp <= 0x27BF)) continue;
            clean.appendCodePoint(cp);
        }
        t = clean.toString();
        t = t.replaceAll("[\"“”«»„]", "").replace('‘', '\'').replace('’', '\'');
        t = ELLIPSIS.matcher(t).replaceAll(", ");
        t = DASH.matcher(t).replaceAll(", ");
        t = REPEAT_PUNCT.matcher(t).replaceAll("$1");
        t = t.replaceAll("\\s+([,.!?।॥])", "$1").replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^[,.;:\\s]+", "");
        if (!t.isEmpty() && !TERMINAL.matcher(t).find()) {
            t = t.replaceAll("[,;:]+$", "");
            t += usesDanda(lang) ? "।" : ".";
        }
        boolean digits = DIGIT.matcher(t).find();
        boolean latinWords = lang != null && lang.indic() && !lang.romanized() && LATIN_WORD.matcher(t).find();
        return new Prepared(t, List.copyOf(cues), digits, latinWords);
    }

    private static boolean usesDanda(Language lang) {
        if (lang == null || lang.romanized()) return false;
        return switch (lang.script()) {
            case "Devanagari", "Bengali", "Gurmukhi", "Odia" -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ timing

    /**
     * Rough spoken duration in seconds at a calm storytelling pace. Indic text is counted in
     * aksharas (consonant clusters + independent vowels), which tracks speech time far better
     * than word count: one Kannada/Malayalam "word" can be 6-8 syllables.
     */
    public static double estimateSeconds(String text, Language lang) {
        if (text == null || text.isBlank()) return 0;
        double seconds;
        if (lang != null && lang.indic() && !lang.romanized()) {
            int aksharas = countAksharas(text);
            int latinWords = 0;
            Matcher lw = LATIN_WORD.matcher(text);
            while (lw.find()) latinWords++;
            seconds = aksharas / lang.syllablesPerSecond() + latinWords / 2.6;
        } else {
            int words = text.trim().split("\\s+").length;
            seconds = words / 2.5; // calm narration, slower than TTS
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '।' || c == '॥') seconds += 0.35;
            else if (c == ',' || c == ';' || c == ':') seconds += 0.15;
        }
        return Math.max(0.8, seconds);
    }

    static int countAksharas(String text) {
        int count = 0;
        int[] cps = text.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            Spec block = blockOf(cp);
            if (block == null) continue;
            int off = cp - block.blockStart;
            boolean independentVowel = (off >= 0x05 && off <= 0x14) || off == 0x60 || off == 0x61;
            boolean consonant = (off >= 0x15 && off <= 0x39) || (off >= 0x58 && off <= 0x5F);
            if (independentVowel) {
                count++;
            } else if (consonant) {
                int next = i + 1 < cps.length ? cps[i + 1] : -1;
                // skip nukta (block+0x3C) before checking for virama
                if (next == block.blockStart + 0x3C && i + 2 < cps.length) next = cps[i + 2];
                if (next != block.blockStart + 0x4D) count++; // consonant + virama = part of a cluster
            }
        }
        return count;
    }

    private static Spec blockOf(int cp) {
        for (Spec s : LANGS.values()) {
            if (cp >= s.blockStart && cp < s.blockStart + 0x80) return s;
        }
        return null;
    }

    // ------------------------------------------------------------------ splitting

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?।॥])\\s+");
    private static final Pattern CLAUSE_END = Pattern.compile("(?<=[,;:])\\s+");

    /**
     * Splits a line into pieces that each fit {@code maxSeconds} of speech. Prefers sentence
     * breaks, then clause breaks, then word boundaries - never mid-word.
     */
    public static List<String> split(String text, Language lang, double maxSeconds) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        if (estimateSeconds(text, lang) <= maxSeconds) {
            out.add(text.trim());
            return out;
        }
        List<String> units = new ArrayList<>();
        for (String sentence : SENTENCE_END.split(text.trim())) {
            if (estimateSeconds(sentence, lang) <= maxSeconds) { units.add(sentence); continue; }
            for (String clause : CLAUSE_END.split(sentence)) {
                if (estimateSeconds(clause, lang) <= maxSeconds) { units.add(clause); continue; }
                units.addAll(splitWords(clause, lang, maxSeconds));
            }
        }
        // Greedy re-pack so short sentences share a shot.
        StringBuilder current = new StringBuilder();
        for (String u : units) {
            String candidate = current.isEmpty() ? u : current + " " + u;
            if (!current.isEmpty() && estimateSeconds(candidate, lang) > maxSeconds) {
                out.add(current.toString().trim());
                current.setLength(0);
                current.append(u);
            } else {
                current.setLength(0);
                current.append(candidate);
            }
        }
        if (!current.isEmpty()) out.add(current.toString().trim());
        return out;
    }

    private static List<String> splitWords(String clause, Language lang, double maxSeconds) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : clause.trim().split("\\s+")) {
            String candidate = cur.isEmpty() ? w : cur + " " + w;
            if (!cur.isEmpty() && estimateSeconds(candidate, lang) > maxSeconds) {
                out.add(cur.toString());
                cur.setLength(0);
                cur.append(w);
            } else {
                cur.setLength(0);
                cur.append(candidate);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }
}
