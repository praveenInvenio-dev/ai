package com.aistorystudio.conceptexplainer;

import java.util.regex.Pattern;

/**
 * Cleans lesson narration before it is spoken or shown: no greeting openers ("Hello, hello, hello!",
 * "Hi everyone", "Namaste friends") and no word repeated three or more times in a row. Voice models read
 * such openers out loud, and a cloned reference voice tends to echo them. Pure Java, unit-testable.
 */
public final class NarrationScrub {

    private NarrationScrub() { }

    private static final String GREET = "hello|hi|hey|hola|namaste|namaskar|namaskara|vanakkam|welcome|"
            + "\u0928\u092E\u0938\u094D\u0924\u0947|\u0CA8\u0CAE\u0CB8\u0CCD\u0C95\u0CBE\u0CB0|\u0BB5\u0BA3\u0B95\u0BCD\u0B95\u0BAE\u0BCD|\u0C28\u0C2E\u0C38\u0C4D\u0C15\u0C3E\u0C30\u0C02|\u0D28\u0D2E\u0D38\u0D4D\u0D15\u0D3E\u0D30\u0D02";
    private static final Pattern LEADING_GREETING = Pattern.compile(
            "^\\s*(?:(?:" + GREET + ")(?:\\s+(?:back|everyone|friends|guys|folks|there|dear\\s+\\p{L}+|students|learners))?\\s*[,!.\\-\u2013\u2014]*\\s*)+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TRIPLE_REPEAT = Pattern.compile(
            "(?<![\\p{L}\\p{M}])([\\p{L}\\p{M}']+)(?:[\\s,.!?\\-]+\\1(?![\\p{L}\\p{M}])){2,}", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Returns the cleaned text; may be empty when the line was only a greeting. */
    public static String clean(String s) {
        if (s == null) return "";
        String t = LEADING_GREETING.matcher(s).replaceFirst("");
        t = TRIPLE_REPEAT.matcher(t).replaceAll("$1");
        t = t.replaceAll("\\s+", " ").trim();
        if (!t.isEmpty() && Character.isLowerCase(t.charAt(0)) && !s.equals(t)) {
            t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
        return t;
    }
}
