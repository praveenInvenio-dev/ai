package com.aistorystudio.pipeline.promptbuilder;

import com.aistorystudio.domain.Character;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders the immutable "character canon" fragment of an image prompt.
 * The story/scene generator is allowed to decide what a character is DOING or
 * FEELING, but never allowed to silently rewrite what a character LOOKS LIKE -
 * that only happens through an explicit, user-approved character edit.
 */
public class CharacterPromptBuilder {

    public String build(List<Character> charactersInScene) {
        return build(charactersInScene, Integer.MAX_VALUE);
    }

    /**
     * Renders the canon for every character in the scene within a shared token
     * budget, splitting it evenly rather than first-come-first-served.
     *
     * The naive approach - concatenate everything, then truncate the result -
     * spends the whole budget on character one and cuts character two off
     * mid-sentence. In a two-hander that means the second character arrives with
     * no clothing or colour described at all, and the model invents them fresh
     * every scene. Even shares degrade both characters a little instead of
     * destroying one, which is what consistency actually needs.
     *
     * Descriptions are written most-important-first (species, colour, eyes, then
     * clothing), so trimming the tail of each is the least damaging cut available.
     */
    public String build(List<Character> charactersInScene, int tokenBudget) {
        if (charactersInScene == null || charactersInScene.isEmpty()) {
            return "";
        }
        int count = charactersInScene.size();
        // -2 tokens per character for the name and its separators.
        int perCharacter = Math.max(8, (tokenBudget / count) - 2);

        List<String> parts = new ArrayList<>(count);
        for (Character c : charactersInScene) {
            String description = c.getCanonicalDescription() == null ? "" : c.getCanonicalDescription().trim();
            String fitted = PromptTokenBudget.fit(description, perCharacter);
            if (fitted.isBlank()) {
                parts.add(c.getName());
            } else {
                parts.add(c.getName() + ": " + fitted);
            }
        }
        return String.join("; ", parts);
    }

    public String buildNegativeConstraints(List<Character> charactersInScene) {
        if (charactersInScene == null || charactersInScene.isEmpty()) {
            return "";
        }
        return charactersInScene.stream()
                .map(Character::getNegativeConstraints)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(", "));
    }
}
