package com.aistorystudio.pipeline.promptbuilder;

/**
 * Converts the user-facing visual-style choice into a diffusion-friendly
 * rendering profile. The profile is deliberately explicit: SD/SDXL-style
 * checkpoints do not "understand" a UI label such as Anime, so every style
 * expands into positive anchors plus cross-style negatives.
 */
public class StylePromptBuilder {

    private static final String SHARED_COMPOSITION =
            "cinematic composition, clear subject hierarchy, clean silhouettes, "
          + "foreground and background separation, coherent environment design, "
          + "single frame from a finished animated film, high visual clarity";

    private static final String SHARED_QUALITY =
            "high detail, coherent anatomy, believable scale, sharp subject focus, "
          + "intentional lighting, clean edges, polished final render";

    public String build(String visualStyle, String colorPalette) {
        String style = normalize(visualStyle);
        StringBuilder sb = new StringBuilder();
        append(sb, profile(style));
        if (colorPalette != null && !colorPalette.isBlank()) {
            append(sb, "locked color palette: " + colorPalette);
        }
        append(sb, SHARED_COMPOSITION);
        append(sb, SHARED_QUALITY);
        return sb.toString();
    }

    public String profile(String visualStyle) {
        String style = normalize(visualStyle);
        return switch (style) {
            case "3D REALISTIC" ->
                "photorealistic-quality cinematic 3D animation, lifelike stylized 3D characters, "
              + "natural realistic proportions, physically based materials, detailed skin fur cloth and hair, "
              + "realistic eyes, natural facial expressions, physically plausible environment, "
              + "volumetric lighting, soft global illumination, cinematic lens, realistic depth of field";
            case "3D ANIMATED FEATURE" ->
                "high-end 3D animated feature film, polished stylized 3D characters, appealing proportions, "
              + "expressive faces, physically coherent materials, detailed environments, soft global illumination, "
              + "cinematic family-animation lighting, feature-film quality";
            case "CINEMATIC REALISTIC" ->
                "cinematic live-action realism, natural human and animal proportions, realistic materials and textures, "
              + "film-grade lighting, physically believable environments, realistic lens depth of field";
            case "ANIME", "ANIME-INSPIRED" ->
                "Japanese anime film frame, unmistakable 2D anime illustration, clean confident line art, "
              + "cel shading, anime facial construction, large expressive anime eyes, stylized anime proportions, "
              + "hand-painted anime background, controlled highlights, crisp inked contours, anime color design, "
              + "high-quality modern Japanese animation keyframe";
            case "CARTOON" ->
                "professional 2D cartoon animation frame, unmistakable cartoon rendering, bold clean outlines, "
              + "simplified readable shapes, expressive exaggerated faces, appealing stylized proportions, "
              + "flat-to-soft cel shading, colorful animated background, polished television animation";
            case "STORYBOOK" ->
                "premium illustrated children's storybook frame, hand-painted character art, visible brush texture, "
              + "warm illustrated backgrounds, soft edges, layered paper-like depth, charming storybook composition";
            case "WATERCOLOR" ->
                "detailed watercolor illustration, translucent pigment washes, visible paper grain, soft bleeding edges, "
              + "hand-painted characters, delicate ink accents, harmonious painted environment";
            case "2D ANIMATION" ->
                "high-quality hand-drawn 2D animation frame, clean character line art, expressive faces, "
              + "controlled cel shading, painted background layers, animation keyframe quality, strong silhouettes";
            case "CLAYMATION" ->
                "professional stop-motion clay animation frame, tactile clay characters, visible handcrafted surface, "
              + "miniature practical set, studio stop-motion lighting, shallow depth of field, physically present materials";
            case "COMIC BOOK" ->
                "premium comic-book illustration, confident ink outlines, graphic shapes, controlled color blocks, "
              + "dynamic panel composition, expressive characters, detailed comic background, polished sequential-art finish";
            case "FANTASY ILLUSTRATION" ->
                "premium cinematic fantasy illustration, detailed painterly characters, richly rendered environments, "
              + "atmospheric perspective, magical lighting, controlled brushwork, storybook fantasy concept-art quality";
            case "REALISTIC" ->
                "photographic realism, natural proportions, realistic materials and textures, realistic skin fur hair, "
              + "natural lighting, physically believable environment, cinematic camera and lens";
            default ->
                "cinematic 3D animated film, appealing character design, coherent materials, detailed environment, expressive face";
        };
    }

    /** Strong cross-style exclusions are important when the selected style is 2D. */
    public String negativeProfile(String visualStyle) {
        String common =
                "different character design, identity drift, altered face, altered body proportions, wrong age, "
              + "wrong clothing, wrong clothing colors, missing clothing, changed accessories, extra character, "
              + "duplicate character, extra limbs, extra fingers, malformed hands, malformed feet, deformed face, "
              + "cropped character, text, captions, watermark, logo, signature, blurry, low resolution, artifacts";
        String style = normalize(visualStyle);
        return switch (style) {
            case "ANIME", "ANIME-INSPIRED" -> common
                    + ", photorealistic, live action, realistic photography, 3D render, CGI, western cartoon, "
                    + "plastic toy, clay, watercolor wash, painterly oil painting, comic-book ink-heavy rendering";
            case "CARTOON" -> common
                    + ", photorealistic, live action, photographic skin, realistic human anatomy, anime, manga, "
                    + "3D render, claymation, watercolor";
            case "2D ANIMATION" -> common
                    + ", photorealistic, live action, photographic, 3D render, claymation, anime-specific eye design";
            case "CLAYMATION" -> common
                    + ", photorealistic, live action, flat 2D illustration, anime, watercolor, smooth CGI plastic";
            case "WATERCOLOR" -> common
                    + ", photorealistic, live action, 3D render, cel shading, hard ink outlines, plastic CGI";
            case "STORYBOOK", "FANTASY ILLUSTRATION" -> common
                    + ", photorealistic, live action, 3D render, anime-specific rendering, plastic CGI";
            case "3D REALISTIC" -> common
                    + ", flat 2D illustration, anime, cartoon, comic book, watercolor, claymation, paper cutout, "
                    + "toy-like plastic CGI, low-poly game asset";
            case "3D ANIMATED FEATURE" -> common
                    + ", live action, photograph, flat 2D, anime, manga, watercolor, claymation, comic-book ink";
            case "CINEMATIC REALISTIC", "REALISTIC" -> common
                    + ", anime, cartoon, illustration, comic book, watercolor, claymation, obvious CGI, plastic skin";
            case "COMIC BOOK" -> common
                    + ", photorealistic, live action, 3D render, watercolor wash, claymation, soft photographic skin";
            default -> common;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private void append(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(value.trim());
        }
    }
}
