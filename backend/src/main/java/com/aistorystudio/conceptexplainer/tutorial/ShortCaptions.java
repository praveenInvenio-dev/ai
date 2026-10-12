package com.aistorystudio.conceptexplainer.tutorial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Word-by-word captions for the 60-second short, as in the reference reel: a short chunk of the
 * sentence at the bottom, words already spoken in white, the rest of the chunk in grey.
 *
 * <p>Written as an ASS subtitle file with karaoke tags ({@code \k}): libass draws each word in the
 * secondary colour (grey) until its time, then in the primary colour (white). The narration is
 * synthesised sentence by sentence, so sentence start/length are exact; word times inside a sentence
 * are spread by word length (good enough for a 2-5 word chunk).</p>
 */
public final class ShortCaptions {

    private ShortCaptions() { }

    public static final int MAX_WORDS = 4, MAX_CHARS = 24;
    /** Modern geometric heavy sans used by most explainer reels (OFL, bundled in concept-fonts). */
    public static final String FONT = "Poppins ExtraBold";

    // ASS colours are &HBBGGRR
    private static final String SPOKEN = "&HFFFFFF&", CURRENT = "&H80DE4A&", ALPHA_UPCOMING = "&HA0&";

    /**
     * Three visible states per word, so it is obvious where the voice is:
     * already spoken = solid white, being spoken = green and 8 % larger, still to come = white at 37 % opacity.
     * One subtitle event per word (the chunk is redrawn with the next word lit), soft shadow for any background.
     *
     * @param sentences English caption sentences (one per spoken sentence)
     * @param starts    start of each spoken sentence in the scene clip (seconds)
     * @param lengths   spoken length of each sentence (seconds)
     */
    public static String ass(List<String> sentences, List<Double> starts, List<Double> lengths, int width, int height, String font) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Script Info]\nScriptType: v4.00+\nPlayResX: ").append(width).append("\nPlayResY: ").append(height)
                .append("\nWrapStyle: 0\nScaledBorderAndShadow: yes\n\n");
        sb.append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, "
                + "StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n");
        int size = Math.round(height * 0.037f);                 // ~71 px on 1920
        int marginV = Math.round(height * 0.16f);
        sb.append("Style: Cap,").append(font == null ? FONT : font).append(',').append(size)
                .append(",&H00FFFFFF,&H00FFFFFF,&H00101010,&H96000000,0,0,0,0,100,100,1,0,1,2,4,2,80,80,").append(marginV).append(",1\n\n");
        sb.append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        int n = Math.min(sentences.size(), Math.min(starts.size(), lengths.size()));
        for (int i = 0; i < n; i++) {
            List<String> words = words(sentences.get(i));
            if (words.isEmpty()) continue;
            double start = starts.get(i), len = Math.max(0.3, lengths.get(i));
            double weight = 0;
            for (String w : words) weight += w.length() + 2;
            double t = start;
            for (List<String> chunk : chunks(words)) {
                double cw = 0;
                for (String w : chunk) cw += w.length() + 2;
                double chunkLen = len * cw / weight;
                double wt = t;
                for (int k = 0; k < chunk.size(); k++) {
                    double wl = chunkLen * (chunk.get(k).length() + 2) / cw;
                    StringBuilder line = new StringBuilder();
                    if (k == 0) line.append("{\\fad(70,0)}");        // the chunk pops in softly
                    for (int j = 0; j < chunk.size(); j++) {
                        if (j < k) line.append("{\\1c").append(SPOKEN).append("\\1a&H00&\\fscx100\\fscy100}");
                        else if (j == k) line.append("{\\1c").append(CURRENT).append("\\1a&H00&\\fscx108\\fscy108}");
                        else line.append("{\\1c").append(SPOKEN).append("\\1a").append(ALPHA_UPCOMING).append("\\fscx100\\fscy100}");
                        line.append(escape(chunk.get(j))).append(j < chunk.size() - 1 ? " " : "");
                    }
                    double end = k == chunk.size() - 1 ? t + chunkLen : wt + wl;
                    sb.append("Dialogue: 0,").append(ts(wt)).append(',').append(ts(end)).append(",Cap,,0,0,0,,").append(line).append('\n');
                    wt += wl;
                }
                t += chunkLen;
            }
        }
        return sb.toString();
    }

    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        String clean = s.replaceAll("\\[[^\\]]*\\]", " ").replaceAll("\\s+", " ").trim();   // drop [pause] style tags
        if (clean.isEmpty()) return out;
        for (String w : clean.split(" ")) if (!w.isBlank()) out.add(w);
        return out;
    }

    /** 2-5 words per chunk, broken after punctuation when possible, max ~28 characters. */
    static List<List<String>> chunks(List<String> words) {
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int chars = 0;
        for (String w : words) {
            if (!cur.isEmpty() && (cur.size() >= MAX_WORDS || chars + w.length() + 1 > MAX_CHARS)) {
                out.add(cur);
                cur = new ArrayList<>();
                chars = 0;
            }
            cur.add(w);
            chars += w.length() + 1;
            if (cur.size() >= 2 && w.matches(".*[,;:.!?\u0964]$")) {
                out.add(cur);
                cur = new ArrayList<>();
                chars = 0;
            }
        }
        if (!cur.isEmpty()) {
            if (cur.size() == 1 && !out.isEmpty() && out.get(out.size() - 1).size() < MAX_WORDS) out.get(out.size() - 1).addAll(cur);
            else out.add(cur);
        }
        return out;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("{", "(").replace("}", ")");
    }

    static String ts(double t) {
        if (t < 0) t = 0;
        int cs = (int) Math.round(t * 100);
        int h = cs / 360000, m = cs / 6000 % 60, sec = cs / 100 % 60, c = cs % 100;
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", h, m, sec, c);
    }
}
