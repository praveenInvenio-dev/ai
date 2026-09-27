package com.aistorystudio.pipeline.promptbuilder;

import com.aistorystudio.domain.Character;

import java.util.List;

/**
 * Builds a single structured "master character reference sheet" prompt
 * (multi-pose, multi-expression, all of a universe's characters in one clean
 * studio-style image) from whatever's already in each Character's own
 * fields - name, species, age, personality, canonicalDescription. Not a new
 * data model: this reuses what Character Studio already collects, just
 * assembled into the same shape of prompt a well-written manual one would
 * be (long-form, view/expression checklist, explicit consistency rules).
 *
 * One prompt for the whole universe at once (not one per character) on
 * purpose - a master sheet's whole point is showing every character
 * together at their correct relative size/proportions, which a per-character
 * prompt can't establish.
 *
 * This produces a TEXT PROMPT, not an image. Feed it into an image
 * generation call the same way any other prompt is used (ComfyUI txt2img,
 * or an external tool) - it deliberately doesn't assume a particular image
 * provider, since a reference sheet is often generated once, by hand,
 * outside the regular per-scene pipeline, then the resulting image is what
 * gets locked as each character's CharacterReference.
 */
public class CharacterReferenceSheetPromptBuilder {

    public String build(String storyTitle, List<Character> characters) {
        StringBuilder sb = new StringBuilder();

        sb.append("Create a MASTER CHARACTER REFERENCE SHEET for ");
        sb.append(storyTitle == null || storyTitle.isBlank() ? "an original children's animated story" : "\"" + storyTitle + "\"");
        sb.append(".\n\n");
        sb.append("This image is the permanent visual reference for all future story illustrations, ")
          .append("animations and videos. Every character must have a clear, memorable design reproducible ")
          .append("across different scenes, poses, camera angles, facial expressions, lighting and environments.\n\n");

        for (int i = 0; i < characters.size(); i++) {
            appendCharacterBlock(sb, characters.get(i), i == 0);
        }

        appendVisualStyleBlock(sb);
        appendSheetLayoutBlock(sb, characters);
        appendConsistencyRuleBlock(sb, characters);

        sb.append("IMAGE QUALITY: Ultra-detailed, polished cinematic children's animation, professional ")
          .append("character-development artwork, clean composition, beautiful soft lighting, highly expressive ")
          .append("faces, sharp character details, 4K-quality appearance.\n\n")
          .append("IMPORTANT: Prioritize character identity and consistency over background detail - the ")
          .append("characters themselves are the most important part of this image.");

        return sb.toString();
    }

    private void appendCharacterBlock(StringBuilder sb, Character c, boolean isMain) {
        sb.append(isMain ? "### MAIN CHARACTER" : "### FRIEND").append(" - ")
          .append(c.getName() == null ? "Unnamed" : c.getName().toUpperCase()).append("\n\n");

        if (c.getSpecies() != null && !c.getSpecies().isBlank()) {
            sb.append("A ").append(c.getSpecies());
            if (c.getAge() != null && !c.getAge().isBlank()) {
                sb.append(", approximately ").append(c.getAge()).append(" in human-equivalent terms");
            }
            sb.append(".\n\n");
        }

        sb.append("Character design:\n");
        if (c.getCanonicalDescription() != null && !c.getCanonicalDescription().isBlank()) {
            // The canonical description is already the character's real,
            // hand-written (or previously locked) visual detail - reused
            // verbatim as the core of this block rather than re-derived,
            // since it's the single most authoritative source of truth this
            // app has for what the character actually looks like.
            sb.append("* ").append(c.getCanonicalDescription().trim().replace("\n", "\n* ")).append("\n");
        }
        if (c.getPersonality() != null && !c.getPersonality().isBlank()) {
            sb.append("* Personality: ").append(c.getPersonality()).append("\n");
        }
        if (c.getNegativeConstraints() != null && !c.getNegativeConstraints().isBlank()) {
            sb.append("* Avoid: ").append(c.getNegativeConstraints()).append("\n");
        }
        sb.append("\n");
    }

    private void appendVisualStyleBlock(StringBuilder sb) {
        sb.append("### VISUAL STYLE\n\n")
          .append("Premium 3D animated children's movie style: high-end cinematic 3D animation, cute stylized ")
          .append("proportions, soft rounded shapes, detailed but child-friendly textures, beautiful expressive ")
          .append("faces, large readable eyes, warm natural lighting, vibrant but pleasant colors, soft cinematic ")
          .append("depth of field, subtle subsurface scattering on skin/fur, high-quality fur/feather detail, ")
          .append("wholesome magical storybook feeling. No photorealism, no anime style, no horror, no dark or ")
          .append("scary appearance, no exaggerated cartoon distortion.\n\n");
    }

    private void appendSheetLayoutBlock(StringBuilder sb, List<Character> characters) {
        String names = String.join(", ", characters.stream().map(c -> c.getName() == null ? "the character" : c.getName()).toList());
        sb.append("### CHARACTER REFERENCE SHEET\n\n")
          .append("Show ").append(names).append(" together in a clean studio environment. Include:\n\n")
          .append("1. Full-body front view of every character\n")
          .append("2. 3/4 view of every character\n")
          .append("3. Side/profile view of every character\n")
          .append("4. Close-up facial portrait\n")
          .append("5. Happy expression\n")
          .append("6. Sad expression\n")
          .append("7. Surprised expression\n")
          .append("8. Excited expression\n")
          .append("9. Curious expression\n")
          .append("10. Walking/standing pose\n")
          .append("11. One group pose showing their relative height and proportions\n\n")
          .append("Keep the characters clearly separated so every important design feature can be studied.\n\n");
    }

    private void appendConsistencyRuleBlock(StringBuilder sb, List<Character> characters) {
        sb.append("### CONSISTENCY RULE\n\n")
          .append("This image is the MASTER CHARACTER REFERENCE. For every future image, animation, video ")
          .append("frame or story scene:\n\n");
        for (Character c : characters) {
            sb.append(c.getName() == null ? "Each character" : c.getName())
              .append(" must always remain the same ").append(c.getName() == null ? "character" : c.getName()).append(".\n");
        }
        sb.append("\nDo not redesign, reinterpret, age, recolor, replace or modify their fundamental appearance. ")
          .append("Maintain exact fur/feather/skin colors, exact eye color, exact facial structure, exact ear ")
          .append("shape, exact body proportions, exact clothing/accessories, exact signature features, ")
          .append("consistent relative height, consistent age appearance and consistent animation style. ")
          .append("Characters may change pose, expression, camera angle, lighting and environment, but their ")
          .append("underlying identity must remain unchanged.\n\n");
    }
}
