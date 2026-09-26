package com.aistorystudio.pipeline.promptbuilder;

import java.util.LinkedHashSet;
import java.util.Set;

public class NegativePromptBuilder {

    private static final String IDENTITY_NEGATIVE =
            "character redesign, identity drift, different character, altered face, wrong eye color, "
          + "wrong fur or skin color, wrong clothing, missing clothing, changed accessories, altered proportions, "
          + "different age, adult proportions, merged characters, duplicate character, extra character";

    private static final String ANATOMY_NEGATIVE =
            "extra limbs, extra fingers, malformed hands, malformed feet, deformed anatomy, distorted face, "
          + "asymmetrical eyes, crossed eyes, cropped character, missing tail, incorrect horns or wings";

    private static final String ARTIFACT_NEGATIVE =
            "text, letters, captions, logo, watermark, signature, low resolution, noisy image, artifacts, blurry";

    public String build(String characterNegativeConstraints, String sceneSpecificNegative) {
        Set<String> terms = new LinkedHashSet<>();
        addAll(terms, characterNegativeConstraints);
        addAll(terms, sceneSpecificNegative);
        addAll(terms, IDENTITY_NEGATIVE);
        addAll(terms, ANATOMY_NEGATIVE);
        addAll(terms, ARTIFACT_NEGATIVE);
        return PromptTokenBudget.fit(String.join(", ", terms), PromptTokenBudget.DEFAULT_BUDGET);
    }

    private void addAll(Set<String> target, String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) return;
        for (String term : commaSeparated.split(",")) {
            String trimmed = term.trim().toLowerCase();
            if (!trimmed.isEmpty()) target.add(trimmed);
        }
    }
}
