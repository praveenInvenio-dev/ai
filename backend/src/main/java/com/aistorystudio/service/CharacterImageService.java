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

    /** Master references are drawn by the same model as the scenes (Qwen Image 2.1). */
    @org.springframework.beans.factory.annotation.Value("${studio.comfyui.qwen.steps:30}")
    private int qwenSteps;

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
        // Qwen uses this image directly as <image1> in every scene. A single, clean,
        // full-body figure transfers far better than a multi-view collage (which
        // invites duplicate characters in the scene).
        String positivePrompt = (supplied.isBlank() ? canon : supplied) + ". " + style
                + ". Official character design reference: ONE single character, full body, standing, "
                + "front three-quarter view, friendly neutral expression, entire figure visible from head to shoes, "
                + "centered, plain soft light-grey background, even studio lighting, crisp clean details, "
                + "exact clothing, colors and signature accessories. No other characters, no text, no extra views.";
        positivePrompt = positivePrompt.trim();
        String negativePrompt = negativePromptBuilder.build(character.getNegativeConstraints(), stylePromptBuilder.negativeProfile(visualStyleOverride != null && !visualStyleOverride.isBlank() ? visualStyleOverride : DEFAULT_STYLE));

        // Square 1024x1024 -> 2048x2048 after the 2x upscale; refs get resized by the encoder.
        var request = new ImageGenerationProvider.ImageGenerationRequest(
                positivePrompt, negativePrompt, 1024, 1024, qwenSteps, 0, null, null, null, null, null);
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
