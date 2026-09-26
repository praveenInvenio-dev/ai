package com.aistorystudio.pipeline.promptbuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * CLIP (SD1.5 / SDXL) encodes text in chunks of 75 tokens plus 2 special tokens.
 * ComfyUI does not error on longer prompts - it splits them into chunks and
 * concatenates the embeddings - but each successive chunk influences the image
 * noticeably less than the one before it. A 400-token prompt therefore does not
 * produce a "very detailed" image; it produces an image driven almost entirely
 * by its first ~75 tokens, with everything after that faded out.
 *
 * That is why prompt order is a correctness concern here and not a style choice.
 * The scene description has to sit inside the first chunk, next to the character
 * canon, or the model simply will not draw the scene that was written.
 *
 * This class estimates CLIP token counts and trims fragments to a budget on
 * comma boundaries, so a truncated fragment stays readable rather than ending
 * mid-phrase.
 */
public final class PromptTokenBudget {

    /** Usable tokens in one CLIP chunk (77 minus BOS/EOS). */
    public static final int CHUNK = 75;

    /**
     * Total budget. Two chunks is a deliberate compromise: everything important
     * lives in chunk one, and chunk two carries style/quality tags whose weaker
     * influence is acceptable. Going beyond this buys nothing.
     */
    public static final int DEFAULT_BUDGET = CHUNK * 2;

    private PromptTokenBudget() {
    }

    /**
     * Approximates CLIP's BPE tokeniser. Real tokenisation needs the vocabulary,
     * but for budgeting we only need to avoid under-counting: English averages
     * ~1.3 BPE tokens per word, long/rare words more, and punctuation costs one
     * token each. Deliberately errs on the high side.
     */
    public static int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int tokens = 0;
        for (String word : text.trim().split("\\s+")) {
            String bare = word.replaceAll("[^A-Za-z0-9']", "");
            tokens += word.length() - bare.length();   // punctuation
            if (bare.isEmpty()) {
                continue;
            }
            // Short words are usually one token; longer ones split into pieces.
            tokens += bare.length() <= 4 ? 1 : 1 + (bare.length() - 4 + 3) / 4;
        }
        return tokens;
    }

    /**
     * Trims text to at most {@code maxTokens}, cutting on comma boundaries so the
     * result reads as a complete list of phrases. Returns the input untouched when
     * it already fits.
     */
    public static String fit(String text, int maxTokens) {
        if (text == null || text.isBlank() || maxTokens <= 0) {
            return "";
        }
        if (estimate(text) <= maxTokens) {
            return text.trim();
        }
        List<String> kept = new ArrayList<>();
        int used = 0;
        for (String phrase : text.split(",")) {
            String trimmed = phrase.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int cost = estimate(trimmed) + 1; // +1 for the separating comma
            if (used + cost > maxTokens) {
                break;
            }
            kept.add(trimmed);
            used += cost;
        }
        if (kept.isEmpty()) {
            // A single phrase larger than the whole budget - fall back to words.
            return fitByWords(text, maxTokens);
        }
        return String.join(", ", kept);
    }

    private static String fitByWords(String text, int maxTokens) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (String word : text.trim().split("\\s+")) {
            int cost = estimate(word);
            if (used + cost > maxTokens) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(word);
            used += cost;
        }
        return sb.toString();
    }
}
