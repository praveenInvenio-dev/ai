package com.aistorystudio.service;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.domain.Character;
import com.aistorystudio.domain.CharacterReference;
import com.aistorystudio.pipeline.promptbuilder.CharacterPromptBuilder;
import com.aistorystudio.pipeline.promptbuilder.NegativePromptBuilder;
import com.aistorystudio.pipeline.promptbuilder.StylePromptBuilder;
import com.aistorystudio.provider.ImageGenerationProvider;
import com.aistorystudio.provider.StorageProvider;
import com.aistorystudio.repository.CharacterReferenceRepository;
import com.aistorystudio.repository.CharacterRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Generates a single standalone reference image for one character, straight
 * from its canonical description - no story, no scenes, no episode required.
 * This exists specifically so ComfyUI/image-generation setup can be tested
 * and debugged in isolation: one simple prompt, one image, without waiting
 * through the full draft -> approve -> production pipeline first.
 */
@Service
public class CharacterImageService {

    private static final String DEFAULT_STYLE = "3D Realistic";

    private final CharacterRepository characterRepository;
    private final CharacterReferenceRepository characterReferenceRepository;
    private final ProviderGateway providerGateway;
    private final StorageProvider storageProvider;

    private final CharacterPromptBuilder characterPromptBuilder = new CharacterPromptBuilder();
    private final NegativePromptBuilder negativePromptBuilder = new NegativePromptBuilder();
    private final StylePromptBuilder stylePromptBuilder = new StylePromptBuilder();

    /** When the Qwen Image 2.1 tier is configured, master references are drawn
     *  by Qwen too: same model as the scenes = same rendering style, and a much
     *  cleaner identity source than a 4-step SDXL-Lightning turnaround sheet. */
    @org.springframework.beans.factory.annotation.Value("${studio.comfyui.highQualityWorkflow:}")
    private String highQualityWorkflow;
    @org.springframework.beans.factory.annotation.Value("${studio.comfyui.highQualityModel:}")
    private String highQualityModel;
    @org.springframework.beans.factory.annotation.Value("${studio.comfyui.highQualitySteps:30}")
    private int highQualitySteps;

    public CharacterImageService(CharacterRepository characterRepository,
                                  CharacterReferenceRepository characterReferenceRepository,
                                  ProviderGateway providerGateway, StorageProvider storageProvider) {
        this.characterRepository = characterRepository;
        this.characterReferenceRepository = characterReferenceRepository;
        this.providerGateway = providerGateway;
        this.storageProvider = storageProvider;
    }

    public CharacterReference generateReferenceImage(UUID characterId, String visualStyleOverride, String customPrompt) {
        Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found: " + characterId));

        String canon = characterPromptBuilder.build(List.of(character));
        String style = stylePromptBuilder.build(
                visualStyleOverride != null && !visualStyleOverride.isBlank() ? visualStyleOverride : DEFAULT_STYLE,
                null);
        String supplied = customPrompt == null ? "" : customPrompt.trim();
        String positivePrompt = (supplied.isBlank() ? canon : supplied) + ". " + style
                + ". MASTER CHARACTER REFERENCE for this character. Full body front view, 3/4 view, side/profile view, "
                + "close-up face, happy/sad/surprised/excited expressions, neutral studio background, clear silhouette, "
                + "exact clothing and signature accessories, consistent proportions, clean character-development artwork. "
                + "No other characters. Prioritize identity and repeatability over background detail.";
        boolean qwen = highQualityWorkflow != null
                && highQualityWorkflow.trim().toLowerCase(java.util.Locale.ROOT).startsWith("qwen-image-2-1-16gb")
                && highQualityModel != null && !highQualityModel.isBlank();
        if (qwen) {
            // Qwen uses this image directly as <image1> in every scene. A single,
            // clean, full-body figure transfers far better than a multi-view
            // collage (which invites duplicate characters in the scene).
            positivePrompt = (supplied.isBlank() ? canon : supplied) + ". " + style
                    + ". Official character design reference: ONE single character, full body, standing, "
                    + "front three-quarter view, friendly neutral expression, entire figure visible from head to shoes, "
                    + "centered, plain soft light-grey background, even studio lighting, crisp clean details, "
                    + "exact clothing, colors and signature accessories. No other characters, no text, no extra views.";
        }
        positivePrompt = positivePrompt.trim();
        String negativePrompt = negativePromptBuilder.build(character.getNegativeConstraints(), stylePromptBuilder.negativeProfile(visualStyleOverride != null && !visualStyleOverride.isBlank() ? visualStyleOverride : DEFAULT_STYLE));

        // Zeros mean "use the configured defaults" (studio.comfyui.width/height/steps/cfg)
        // so this debug path costs the same as a real scene image and stays tunable
        // from .env rather than needing a rebuild.
        var request = qwen
                ? new ImageGenerationProvider.ImageGenerationRequest(
                        positivePrompt, negativePrompt, 1024, 1024, highQualitySteps, 1.0, null,
                        highQualityWorkflow.trim(), highQualityModel.trim(), null, null)
                : new ImageGenerationProvider.ImageGenerationRequest(
                        positivePrompt, negativePrompt, 0, 0, 0, 0, null, null, null, null, null);
        var result = providerGateway.generateImage(request);

        String relative = "characters/" + characterId + "/reference-" + System.currentTimeMillis() + "." + result.fileExtension();
        Path path = storageProvider.store(relative, result.imageBytes());

        boolean hasExisting = !characterReferenceRepository.findByCharacterId(characterId).isEmpty();

        CharacterReference ref = new CharacterReference();
        ref.setCharacterId(characterId);
        ref.setImagePath(path.toString());
        ref.setImageHash(Integer.toHexString(java.util.Arrays.hashCode(result.imageBytes())));
        ref.setSource("GENERATED");
        ref.setPrimary(!hasExisting);
        ref.setLocked(false);
        ref.setPromptText(supplied.isBlank() ? positivePrompt : supplied);
        return characterReferenceRepository.save(ref);
    }
}
