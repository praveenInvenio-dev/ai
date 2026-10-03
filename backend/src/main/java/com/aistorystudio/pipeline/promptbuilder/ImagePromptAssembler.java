package com.aistorystudio.pipeline.promptbuilder;

import com.aistorystudio.domain.Character;
import com.aistorystudio.domain.Scene;

import java.util.List;

/**
 * Combines CHARACTER CANON + SCENE ACTION + CONTINUITY + STYLE into the final
 * positive/negative prompt pair sent to the ImageGenerationProvider.
 *
 * <h2>Why this is not a "master consistency instruction" block</h2>
 *
 * A natural way to write this prompt is as an instruction sheet:
 *
 * <pre>
 *   MASTER CHARACTER CONSISTENCY - DO NOT CHANGE
 *   Use the previously established character reference as the source of truth.
 *   Do not change clothing. Do not change colors. Do not add accessories.
 * </pre>
 *
 * That format works on LLM-conditioned generators (Midjourney, Gemini, GPT-image)
 * and fails on CLIP-conditioned ones (SD1.5, SDXL) for three concrete reasons:
 *
 * <ol>
 *   <li><b>CLIP has no memory.</b> "the previously established reference" points
 *       at nothing - each generation is independent. Real reference conditioning
 *       needs IPAdapter or ControlNet, which is a workflow change, not wording.</li>
 *   <li><b>CLIP has no negation.</b> "Do not add accessories" embeds the concept
 *       <i>accessories</i> with no negation applied, making them MORE likely. Every
 *       prohibition must move to the negative prompt, which is what negative
 *       conditioning is actually for. {@link NegativePromptBuilder} holds them.</li>
 *   <li><b>CLIP fades past 75 tokens.</b> Prompts are encoded in 75-token chunks
 *       and later chunks influence the image far less. An instruction preamble
 *       pushes the scene description into chunk five or six, where it is close to
 *       ignored - so the model draws the style and not the scene.</li>
 * </ol>
 *
 * So the intent of that template is preserved and the mechanism is changed:
 * identity first, action second, style last, prohibitions in the negative prompt,
 * and the whole thing budgeted so the parts that matter land in chunk one.
 */
public class ImagePromptAssembler {

    private final CharacterPromptBuilder characterPromptBuilder = new CharacterPromptBuilder();
    private final ScenePromptBuilder scenePromptBuilder = new ScenePromptBuilder();
    private final ContinuityPromptBuilder continuityPromptBuilder = new ContinuityPromptBuilder();
    private final StylePromptBuilder stylePromptBuilder = new StylePromptBuilder();
    private final NegativePromptBuilder negativePromptBuilder = new NegativePromptBuilder();

    /**
     * Budget split across the two chunks. Character identity gets the largest
     * share because it is what has to stay stable between scenes; style gets the
     * smallest because style tokens are high-frequency and work even when weakly
     * weighted. Continuity is last and is allowed to be dropped entirely on a
     * crowded scene - losing "still carrying the red backpack" costs less than
     * losing the action.
     */
    private static final int CHARACTER_TOKENS = 58;
    private static final int SCENE_TOKENS = 56;
    private static final int STYLE_TOKENS = 40;
    private static final int CONTINUITY_TOKENS = 12;

    // SDXL's dual text encoder (CLIP-L + OpenCLIP-G, each with its own 75-token
    // budget) has meaningfully more effective capacity than SD1.5's single
    // CLIP-L - the SD1.5-tier budget above was sized for that single-encoder
    // ceiling. Give SDXL runs more room, especially for continuity (background/
    // environment locking - see ContinuityPromptBuilder's locationContinuity)
    // and scene detail, which is exactly what was previously most likely to get
    // cut. This does NOT change output for an SD1.5 checkpoint - existing
    // budgets above are untouched for that case.
    private static final int SDXL_CHARACTER_TOKENS = 80;
    private static final int SDXL_SCENE_TOKENS = 78;
    private static final int SDXL_STYLE_TOKENS = 55;
    private static final int SDXL_CONTINUITY_TOKENS = 24;

    // Qwen Image 2.1 has a much larger text-conditioning window than the
    // legacy CLIP paths. On the 16GB preset we deliberately spend that room
    // on concrete environment/lighting/material detail rather than adding a
    // second prompt-enhancer model, which would consume another large chunk
    // of VRAM.
    private static final int QWEN_CHARACTER_TOKENS = 120;
    private static final int QWEN_SCENE_TOKENS = 120;
    private static final int QWEN_STYLE_TOKENS = 90;
    private static final int QWEN_CONTINUITY_TOKENS = 50;

    public record AssembledPrompt(String positivePrompt, String negativePrompt) {}

    /** Back-compat overload - no checkpoint hint means the SD1.5-tier budget,
     *  same as before this change. Prefer the 6-arg overload when the caller
     *  knows which checkpoint is configured. */
    public AssembledPrompt assemble(Scene scene, List<Character> charactersInScene,
                                     List<String> continuityFacts, String visualStyle, String colorPalette) {
        return assemble(scene, charactersInScene, continuityFacts, visualStyle, colorPalette, null);
    }

    public AssembledPrompt assemble(Scene scene, List<Character> charactersInScene,
                                     List<String> continuityFacts, String visualStyle, String colorPalette,
                                     String checkpointName) {
        String modelName = checkpointName == null ? "" : checkpointName.toLowerCase(java.util.Locale.ROOT);
        boolean qwen = modelName.contains("qwen_image") || modelName.contains("qwen-image");
        boolean sdxl = modelName.contains("xl");
        int characterTokens = qwen ? QWEN_CHARACTER_TOKENS : (sdxl ? SDXL_CHARACTER_TOKENS : CHARACTER_TOKENS);
        int sceneTokens = qwen ? QWEN_SCENE_TOKENS : (sdxl ? SDXL_SCENE_TOKENS : SCENE_TOKENS);
        int styleTokens = qwen ? QWEN_STYLE_TOKENS : (sdxl ? SDXL_STYLE_TOKENS : STYLE_TOKENS);
        int continuityTokens = qwen ? QWEN_CONTINUITY_TOKENS : (sdxl ? SDXL_CONTINUITY_TOKENS : CONTINUITY_TOKENS);

        // Order is the whole point: identity, then what is happening, then how it
        // is rendered. Reordering these silently degrades character consistency.
        // Budget passed in, not applied afterwards: the builder splits it evenly
        // between characters so a two-hander does not lose the second one entirely.
        String characterCanon = characterPromptBuilder.build(charactersInScene, characterTokens);
        String sceneFragment = PromptTokenBudget.fit(
                scenePromptBuilder.build(scene), sceneTokens);
        String styleFragment = PromptTokenBudget.fit(
                stylePromptBuilder.build(visualStyle, colorPalette), styleTokens);
        String continuitySource = (continuityFacts == null || continuityFacts.isEmpty())
                ? continuityPromptBuilder.buildFromJson(scene.getContinuityJson())
                : continuityPromptBuilder.build(continuityFacts);
        String continuityFragment = PromptTokenBudget.fit(continuitySource, continuityTokens);

        StringBuilder positive = new StringBuilder();
        appendPart(positive, characterCanon);
        appendPart(positive, sceneFragment);
        appendPart(positive, continuityFragment);
        appendPart(positive, styleFragment);

        String negative = negativePromptBuilder.build(
                characterPromptBuilder.buildNegativeConstraints(charactersInScene),
                joinNegative(stylePromptBuilder.negativeProfile(visualStyle), scene.getNegativePrompt()));

        return new AssembledPrompt(positive.toString(), negative);
    }

    /**
     * Estimated CLIP token count of the assembled positive prompt. Useful for
     * logging or asserting in tests that a prompt still fits its budget.
     */
    public int estimateTokens(AssembledPrompt prompt) {
        return PromptTokenBudget.estimate(prompt.positivePrompt());
    }

    private String joinNegative(String first, String second) {
        if (first == null || first.isBlank()) return second == null ? "" : second;
        if (second == null || second.isBlank()) return first;
        return first + ", " + second;
    }

    private void appendPart(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(part.trim());
        }
    }
}
