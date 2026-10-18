package com.aistorystudio.conceptexplainer.speech;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the WRITTEN form of a narration line into the SPOKEN form that is sent to the text-to-speech engine.
 *
 * <p>Captions and on-screen text keep the written form ("count++", "O(n)", "3.5GB", "API", "e.g."). A voice model that is
 * handed those symbols guesses, and the guess is what makes a lesson sound like a machine reading a document: it spells
 * words that should be spoken, speaks symbols as silence, and runs the text of a parenthesis into the sentence without a
 * breath. This class converts the symbols into the words a person would say, adds the commas a person would pause at and
 * guarantees a terminal mark so the engine ends the sentence with falling intonation.</p>
 *
 * <p>Pure Java, deterministic, no dependencies. Only the English rules are applied to English text; for any other language
 * only the language-neutral clean-up (markdown, emoji, brackets, spacing) runs, because those rules are not valid there.</p>
 */
public final class SpokenText {

    private SpokenText() { }

    /** Paralinguistic cues understood by the Chatterbox engine; they must survive untouched. */
    private static final Pattern CUE = Pattern.compile("\\[(?:laugh|chuckle|sigh|gasp|cough|clear throat|groan|sniff|shush)]", Pattern.CASE_INSENSITIVE);

    /** Acronyms that are said as a word, not letter by letter. */
    private static final Map<String, String> AS_WORD = Map.ofEntries(
            Map.entry("SQL", "sequel"), Map.entry("JSON", "jay son"), Map.entry("YAML", "yammel"), Map.entry("GUI", "gooey"),
            Map.entry("NASA", "NASA"), Map.entry("NATO", "NATO"), Map.entry("RAM", "ram"), Map.entry("ROM", "rom"), Map.entry("LAN", "lan"),
            Map.entry("WAN", "wan"), Map.entry("ASCII", "ask ee"), Map.entry("UNICODE", "unicode"), Map.entry("CAPTCHA", "cap cha"),
            Map.entry("NOSQL", "no sequel"), Map.entry("OAUTH", "oh auth"), Map.entry("SCSI", "scuzzy"), Map.entry("GIF", "gif"),
            Map.entry("PIN", "pin"), Map.entry("OK", "okay"), Map.entry("UNESCO", "UNESCO"), Map.entry("ISRO", "ISRO"), Map.entry("WIFI", "why fie"),
            Map.entry("LASER", "laser"), Map.entry("RADAR", "radar"), Map.entry("SCUBA", "scuba"), Map.entry("PDF", "P D F"), Map.entry("REST", "rest"), Map.entry("CRUD", "crud"), Map.entry("RAID", "raid"));

    /** Ordinary English words that people type in capitals for emphasis; they are said normally, not spelled. */
    private static final Set<String> SHOUTED_WORDS = Set.of("THE", "AND", "NOT", "ALL", "NEVER", "ALWAYS", "ONE", "TWO", "NO", "YES", "NOW", "WAIT",
            "STOP", "THIS", "THAT", "ONLY", "EVERY", "ANY", "BUT", "FOR", "ARE", "WAS", "CAN", "WILL", "MUST", "DO", "DON'T", "BEFORE", "AFTER",
            "FIRST", "LAST", "NEXT", "NEW", "OLD", "BIG", "HOW", "WHY", "WHAT", "WHEN", "WHERE", "YOU", "YOUR", "OUR", "SAME", "ZERO", "NONE",
            "TRUE", "FALSE", "NULL", "WRONG", "RIGHT", "SAFE", "FAST", "SLOW", "BOOM", "WOW", "OOPS", "HOT", "COLD", "OFF", "ON", "OUT", "IN",
            "UP", "DOWN", "OPEN", "LOCKED", "FREE", "BOOKED", "HELD", "MORE", "LESS", "ALMOST", "JUST", "ALSO", "EACH", "BOTH", "WORKS", "BROKEN");

    private static final Map<String, String> UNITS = Map.ofEntries(
            Map.entry("ms", "milliseconds"), Map.entry("s", "seconds"), Map.entry("sec", "seconds"), Map.entry("min", "minutes"),
            Map.entry("hr", "hours"), Map.entry("kb", "kilobytes"), Map.entry("mb", "megabytes"), Map.entry("gb", "gigabytes"),
            Map.entry("tb", "terabytes"), Map.entry("ghz", "gigahertz"), Map.entry("mhz", "megahertz"), Map.entry("khz", "kilohertz"),
            Map.entry("hz", "hertz"), Map.entry("kg", "kilograms"), Map.entry("g", "grams"), Map.entry("mg", "milligrams"),
            Map.entry("km", "kilometers"), Map.entry("m", "meters"), Map.entry("cm", "centimeters"), Map.entry("mm", "millimeters"),
            Map.entry("ml", "milliliters"), Map.entry("l", "liters"), Map.entry("fps", "frames per second"), Map.entry("px", "pixels"),
            Map.entry("mph", "miles per hour"), Map.entry("kmph", "kilometers per hour"), Map.entry("kw", "kilowatts"),
            Map.entry("mw", "megawatts"), Map.entry("v", "volts"), Map.entry("a", "amps"), Map.entry("w", "watts"));

    private static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("e.g.", "for example"), Map.entry("i.e.", "that is"), Map.entry("etc.", "and so on"), Map.entry("vs.", "versus"),
            Map.entry("vs", "versus"), Map.entry("approx.", "about"), Map.entry("aka", "also known as"), Map.entry("a.k.a.", "also known as"),
            Map.entry("w/o", "without"), Map.entry("w/", "with"), Map.entry("fig.", "figure"), Map.entry("no.", "number"),
            Map.entry("dr.", "doctor"), Map.entry("mr.", "mister"), Map.entry("mrs.", "missus"), Map.entry("prof.", "professor"));

    // ------------------------------------------------------------------------------------------------------ patterns
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://)?(?:www\\.)?([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|org|net|io|dev|ai|in|co|edu|gov|app))(?:/[^\\s,;)]*)?");
    private static final Pattern BIG_O = Pattern.compile("\\bO\\(\\s*([^()]{1,24}?)\\s*\\)");
    private static final Pattern INCR = Pattern.compile("(\\w)(\\+\\+|--)(?!\\w)");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final Pattern IDENT = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*(?:_[A-Za-z0-9]+)+\\b|\\b[a-z]+(?:[A-Z][a-z0-9]+)+\\b|\\b(?:[A-Z][a-z0-9]+){2,}\\b");
    private static final Pattern DOTTED_CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)(\\(\\))?");
    private static final Pattern EMPTY_CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\(\\)");
    private static final Pattern NUMBER_SUFFIX = Pattern.compile("(?<![\\w.])(\\d+(?:\\.\\d+)?)\\s?(k|K|M|B|bn|cr|L)(?![A-Za-z0-9])");
    private static final Pattern TIMES = Pattern.compile("(?<![\\w.])(\\d+(?:\\.\\d+)?)\\s?[x\\u00D7](?![A-Za-z0-9])");
    private static final Pattern NUM_UNIT = Pattern.compile("(?<![\\w.])(\\d+(?:\\.\\d+)?)\\s?(ms|sec|min|hr|KB|MB|GB|TB|GHz|MHz|kHz|Hz|kg|mg|km|cm|mm|ml|fps|px|mph|kmph|kW|MW|[smglVAW])(?![A-Za-z0-9])");
    private static final Pattern NUM_RATE = Pattern.compile("(?<![\\w.])(\\d+(?:\\.\\d+)?)\\s?(km|m|ft|mi)/(h|s|hr|sec|min)(?![A-Za-z0-9])");
    private static final Pattern RATE = Pattern.compile("\\b(km|m|ft|mi)/(h|s|hr|sec|min)\\b");
    private static final Pattern FRACTION = Pattern.compile("(?<![\\w/])(\\d{1,3})/(\\d{1,3})(?![\\w/])");
    private static final Pattern RANGE = Pattern.compile("(?<![\\w.-])(\\d+(?:\\.\\d+)?)\\s?[\\u2013-]\\s?(\\d+(?:\\.\\d+)?)(?![\\w-])");
    private static final Pattern NEGATIVE = Pattern.compile("(?<![\\w)\\d.])-(\\d+(?:\\.\\d+)?)");
    private static final Pattern CURRENCY = Pattern.compile("([$\\u20B9\\u20AC\\u00A3])\\s?(\\d[\\d,]*(?:\\.\\d+)?)\\s?(thousand|million|billion|crore|lakh|k|M|B|bn)?");
    private static final Pattern PERCENT = Pattern.compile("(\\d(?:[\\d,]*\\d)?(?:\\.\\d+)?)\\s?%");
    private static final Pattern DEGREES = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s?\\u00B0\\s?([CFK])?");
    private static final Pattern SUPER = Pattern.compile("(\\w)\\u00B2");
    private static final Pattern SUPER3 = Pattern.compile("(\\w)\\u00B3");
    private static final Pattern CARET = Pattern.compile("(\\w)\\^(\\w+)");
    private static final Pattern ACRONYM = Pattern.compile("(?<![\\w'])([A-Z][A-Z0-9]{1,7})(s|'s)?(?![\\w'])");
    private static final Pattern EMOJI = Pattern.compile("(?:[\\p{So}&&[^\\u00B0]]|[\\p{Cs}\\uFE0F\\u200D])");
    private static final Pattern MARKDOWN = Pattern.compile("(\\*\\*|__|`+|~~|^#{1,6}\\s+|^[-*]\\s+)", Pattern.MULTILINE);
    private static final Pattern PAREN = Pattern.compile("\\s*\\(([^()]{1,120})\\)\\s*");
    private static final Pattern DASH = Pattern.compile("\\s+[\\u2013\\u2014-]{1,2}\\s+|\\u2014");
    private static final Pattern SLASH_WORDS = Pattern.compile("(?<=[A-Za-z])\\s?/\\s?(?=[A-Za-z])");
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]");
    private static final Pattern ENGLISH_HINT = Pattern.compile("(?i)\\b(the|is|are|a|an|to|of|and|in|that|it|this|you|for|with|on|as|how|what)\\b");

    // ------------------------------------------------------------------------------------------------------- public
    /** The spoken form of {@code written}. Never returns null; returns the trimmed input when there is nothing to convert. */
    public static String forSpeech(String written, String language) {
        if (written == null) return "";
        String t = written.trim();
        if (t.isEmpty()) return t;
        boolean english = isEnglish(language, t);
        // protect engine cues from every rewrite below
        java.util.List<String> cues = new java.util.ArrayList<>();
        Matcher cm = CUE.matcher(t);
        StringBuilder protectedText = new StringBuilder();
        while (cm.find()) { cues.add(cm.group()); cm.appendReplacement(protectedText, "\u0001" + (cues.size() - 1) + "\u0002"); }
        cm.appendTail(protectedText);
        t = protectedText.toString();

        t = MARKDOWN.matcher(t).replaceAll("");
        t = EMOJI.matcher(t).replaceAll("");
        if (english) t = englishRules(t);
        t = PAREN.matcher(t).replaceAll(", $1, ");
        t = DASH.matcher(t).replaceAll(", ");
        t = t.replace(';', ',').replace('\u2026', '.').replace('\u201C', '"').replace('\u201D', '"').replace('\u2019', '\'').replace('\u2018', '\'');
        t = t.replace("\"", "");
        t = tidy(t);
        for (int i = 0; i < cues.size(); i++) t = t.replace("\u0001" + i + "\u0002", cues.get(i));
        t = finish(t);
        // "NEVER trust" becomes "never trust": keep the sentence-initial capital the writer had
        if (!t.isEmpty() && Character.isLowerCase(t.charAt(0)) && Character.isUpperCase(written.trim().charAt(0))) t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        return t;
    }

    /** True when the language is English (or unspecified and the text itself reads as English). */
    static boolean isEnglish(String language, String text) {
        String l = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (l.equals("english") || l.equals("en") || l.startsWith("english") || l.startsWith("en-") || l.equals("indian english")) return true;
        if (!l.isEmpty()) return false;
        long latin = LATIN.matcher(text).results().count();
        return latin >= text.replaceAll("\\s", "").length() * 0.8 && ENGLISH_HINT.matcher(text).find();
    }

    // ------------------------------------------------------------------------------------------------ english rules
    private static String englishRules(String t) {
        t = ABBREV_PATTERN.matcher(t).replaceAll(m -> Matcher.quoteReplacement(ABBREVIATIONS.get(m.group(1).toLowerCase(Locale.ROOT))));
        t = URL.matcher(t).replaceAll(m -> Matcher.quoteReplacement(m.group(1).replace(".", " dot ")));
        t = BIG_O.matcher(t).replaceAll(m -> "big O of " + speakMathArg(m.group(1)));
        t = INCR.matcher(t).replaceAll(m -> m.group(1) + (m.group(2).equals("++") ? " plus plus" : " minus minus"));
        t = t.replace("==", " equals equals ").replace("!=", " not equal to ").replace("<=", " less than or equal to ")
                .replace(">=", " greater than or equal to ").replace("&&", " and ").replace("||", " or ").replace("=>", " gives ")
                .replace("->", " to ").replace("\u2192", " to ").replace("\u2190", " from ").replace("\u2248", " about ")
                .replace("\u2260", " not equal to ").replace("\u2264", " less than or equal to ").replace("\u2265", " greater than or equal to ")
                .replace("\u00F7", " divided by ").replace("\u00B1", " plus or minus ").replace("\u221E", " infinity ").replace("\u221A", " square root of ");
        t = CURRENCY.matcher(t).replaceAll(m -> Matcher.quoteReplacement(money(m.group(1), m.group(2), m.group(3))));
        t = PERCENT.matcher(t).replaceAll("$1 percent");
        t = DEGREES.matcher(t).replaceAll(m -> m.group(1) + " degrees" + (m.group(2) == null ? "" : " " + switch (m.group(2)) { case "C" -> "Celsius"; case "F" -> "Fahrenheit"; default -> "Kelvin"; }));
        t = SUPER.matcher(t).replaceAll("$1 squared");
        t = SUPER3.matcher(t).replaceAll("$1 cubed");
        t = CARET.matcher(t).replaceAll(m -> m.group(1) + " to the power " + m.group(2));
        t = NUM_RATE.matcher(t).replaceAll(m -> m.group(1) + " " + rateWord(m.group(2)) + " per " + rateWord(m.group(3)).replaceFirst("s$", ""));
        t = RATE.matcher(t).replaceAll(m -> rateWord(m.group(1)) + " per " + rateWord(m.group(2)).replaceFirst("s$", ""));
        t = NUM_UNIT.matcher(t).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + " " + unit(m.group(2), m.group(1))));
        t = NUMBER_SUFFIX.matcher(t).replaceAll(m -> m.group(1) + " " + switch (m.group(2)) { case "k", "K" -> "thousand"; case "M" -> "million";
                case "B", "bn" -> "billion"; case "cr" -> "crore"; default -> "lakh"; });
        t = TIMES.matcher(t).replaceAll("$1 times");
        t = FRACTION.matcher(t).replaceAll(m -> fraction(m.group(1), m.group(2)));
        t = RANGE.matcher(t).replaceAll("$1 to $2");
        t = NEGATIVE.matcher(t).replaceAll("minus $1");
        t = DOTTED_CALL.matcher(t).replaceAll(m -> looksLikeFile(m.group(2)) ? m.group(1) + " dot " + m.group(2)
                : m.group(1) + " dot " + splitIdentifier(m.group(2)));
        t = EMPTY_CALL.matcher(t).replaceAll(m -> splitIdentifier(m.group(1)));
        t = IDENT.matcher(t).replaceAll(m -> splitIdentifier(m.group()));
        t = SLASH_WORDS.matcher(t).replaceAll(" slash ");
        t = t.replace(" & ", " and ").replace("&", " and ").replace(" + ", " plus ").replace(" = ", " equals ")
                .replace(" > ", " greater than ").replace(" < ", " less than ").replace(" * ", " times ").replace("@", " at ").replace("#", " number ");
        t = ACRONYM.matcher(t).replaceAll(m -> Matcher.quoteReplacement(acronym(m.group(1), m.group(2))));
        return t;
    }

    private static final Pattern ABBREV_PATTERN = Pattern.compile("(?i)(?<![\\w.])(e\\.g\\.|i\\.e\\.|etc\\.|vs\\.?|approx\\.|a\\.k\\.a\\.|aka|w/o|w/|fig\\.|dr\\.|mr\\.|mrs\\.|prof\\.)(?![\\w])");

    private static String speakMathArg(String a) {
        String s = a.trim().replace("^2", " squared").replace("\u00B2", " squared").replace("^3", " cubed").replace("\u00B3", " cubed")
                .replace("*", " ").replace("log n", "log n");
        if (s.equals("1")) return "one";
        return s.replaceAll("\\s+", " ");
    }

    private static String money(String symbol, String amount, String scale) {
        String unitWord = switch (symbol) { case "$" -> "dollars"; case "\u20B9" -> "rupees"; case "\u20AC" -> "euros"; default -> "pounds"; };
        String n = amount.replace(",", "");
        String scaleWord = scale == null ? "" : switch (scale.toLowerCase(Locale.ROOT)) { case "k" -> "thousand"; case "m" -> "million"; case "b", "bn" -> "billion"; default -> scale.toLowerCase(Locale.ROOT); };
        return n + (scaleWord.isEmpty() ? "" : " " + scaleWord) + " " + unitWord;
    }

    private static String unit(String raw, String number) {
        String key = raw.toLowerCase(Locale.ROOT);
        String word = UNITS.getOrDefault(key, raw);
        // "1 second", "1 meter": singular for exactly one
        return number.equals("1") ? word.replaceFirst("s$", "") : word;
    }

    private static String rateWord(String r) {
        return switch (r) { case "km" -> "kilometers"; case "m" -> "meters"; case "ft" -> "feet"; case "mi" -> "miles"; case "h", "hr" -> "hours"; case "s", "sec" -> "seconds"; default -> "minutes"; };
    }

    private static String fraction(String a, String b) {
        if (a.equals("1") && b.equals("2")) return "one half";
        if (a.equals("1") && b.equals("4")) return "one quarter";
        if (a.equals("3") && b.equals("4")) return "three quarters";
        if (a.equals("1") && b.equals("3")) return "one third";
        if (a.equals("2") && b.equals("3")) return "two thirds";
        return a + " over " + b;
    }

    private static boolean looksLikeFile(String ext) {
        return Set.of("txt", "csv", "json", "xml", "py", "js", "ts", "java", "html", "css", "md", "yml", "yaml", "png", "jpg", "pdf", "sh", "sql", "log", "env", "zip").contains(ext.toLowerCase(Locale.ROOT));
    }

    /** getUserName -> "get user name", user_id -> "user I D", HashMap -> "hash map". */
    static String splitIdentifier(String id) {
        StringBuilder out = new StringBuilder();
        for (String part : id.split("_+")) {
            if (part.isEmpty()) continue;
            for (String w : CAMEL.split(part)) {
                if (w.isEmpty()) continue;
                if (out.length() > 0) out.append(' ');
                out.append(w.length() <= 3 && w.equals(w.toUpperCase(Locale.ROOT)) && !w.matches("\\d+") ? acronym(w, null) : w.toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }

    private static String acronym(String token, String plural) {
        String suffix = plural == null ? "" : "'s";
        String upper = token.toUpperCase(Locale.ROOT);
        if (AS_WORD.containsKey(upper)) return AS_WORD.get(upper) + (plural == null ? "" : (plural.equals("s") ? "s" : "'s"));
        if (SHOUTED_WORDS.contains(upper)) return upper.toLowerCase(Locale.ROOT) + (plural == null ? "" : plural);
        if (token.matches("[A-Z]\\d+")) return token;                                    // "H2", "B4": left to the engine
        if (token.matches("\\d.*")) return token;
        boolean hasVowel = upper.matches(".*[AEIOUY].*");
        // vowel-less runs (HTTP, SQL, CSS) and short runs (CPU, DNS, API) are letters; a long run with vowels is a shouted word
        if (!hasVowel || token.length() <= 4 || KNOWN_SPELLED.contains(upper)) return spell(token) + suffix;
        return token.toLowerCase(Locale.ROOT) + (plural == null ? "" : plural);
    }

    private static final Set<String> KNOWN_SPELLED = Set.of("HTTPS", "HTML", "XML", "IPV4", "IPV6", "OAUTH2", "JWT", "SSH", "SSL", "TLS", "FTP", "CSS",
            "NLP", "LLM", "LLMS", "GPT", "USB", "HDMI", "VPN", "DNS", "CDN", "JVM", "JDK", "NPM", "AWS", "GCP", "CI", "CD", "ORM", "MVC", "SDK", "IDE", "ISBN", "BMI", "ECG", "MRI", "DNA", "RNA", "ATP");

    private static String spell(String token) {
        StringBuilder sb = new StringBuilder();
        for (char c : token.toCharArray()) { if (sb.length() > 0) sb.append(' '); sb.append(c); }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------------ cleanup
    private static String tidy(String t) {
        t = t.replaceAll("\\s+", " ");
        t = t.replaceAll("\\s+([,.!?:])", "$1");
        t = t.replaceAll("([,:])\\s*(?:[,:]\\s*)+", "$1 ");
        t = t.replaceAll("^[,.:]\\s*", "");
        t = t.replaceAll(",\\s*([.!?])", "$1");
        t = t.replaceAll("([,.!?])(?=[A-Za-z])", "$1 ");
        return t.trim();
    }

    /** Every spoken line ends with a mark so the engine lands the sentence instead of trailing off. */
    private static String finish(String t) {
        if (t.isEmpty()) return t;
        char last = t.charAt(t.length() - 1);
        if (last == ']') return t;                       // ends in a cue such as [laugh]
        if (".!?\u2026\u3002\uFF1F\uFF01\u0964".indexOf(last) >= 0) return t;
        if (last == ',' || last == ':') return t.substring(0, t.length() - 1) + ".";
        return t + ".";
    }
}
