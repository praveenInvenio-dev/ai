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

    public CharacterImageService(CharacterRepository characterRepository,
                                  CharacterReferenceRepository characterReferenceRepository,
                                  ProviderGateway providerGateway, StorageProvider storageProvider) {
        this.characterRepository = characterRepository;
        this.characterReferenceRepository = characterReferenceRepository;
        this.providerGateway = providerGateway;
        this.storageProvider = storageProvider;
    }

    public CharacterReference generateReferenceImage(UUID characterId, String visualStyleOverride) {
        Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found: " + characterId));

        String canon = characterPromptBuilder.build(List.of(character));
        String style = stylePromptBuilder.build(
                visualStyleOverride != null && !visualStyleOverride.isBlank() ? visualStyleOverride : DEFAULT_STYLE,
                null);
        String positivePrompt = (canon + ". " + style
                + ". Portrait, character reference sheet, plain neutral background, centered.").trim();
        String negativePrompt = negativePromptBuilder.build(character.getNegativeConstraints(), stylePromptBuilder.negativeProfile(visualStyleOverride != null && !visualStyleOverride.isBlank() ? visualStyleOverride : DEFAULT_STYLE));

        // Zeros mean "use the configured defaults" (studio.comfyui.width/height/steps/cfg)
        // so this debug path costs the same as a real scene image and stays tunable
        // from .env rather than needing a rebuild.
        var request = new ImageGenerationProvider.ImageGenerationRequest(
                positivePrompt, negativePrompt, 0, 0, 0, 0, null, null, null, null);
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
        return characterReferenceRepository.save(ref);
    }
}
