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

    /** Stores a user-supplied character image as the identity reference for H3/R2V. */
    @org.springframework.transaction.annotation.Transactional
    public CharacterReference uploadReferenceImage(UUID characterId, org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Please choose a character reference image.");
        String original = file.getOriginalFilename() == null ? "reference.png" : file.getOriginalFilename();
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT) : "png";
        if (!java.util.Set.of("png", "jpg", "jpeg", "webp").contains(ext)) {
            throw new IllegalArgumentException("Character reference must be PNG, JPG, JPEG or WEBP.");
        }
        Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found: " + characterId));
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (java.io.IOException e) { throw new IllegalStateException("Could not read character reference image.", e); }
        String relative = "characters/" + characterId + "/uploaded-reference-" + System.currentTimeMillis() + "." + ext;
        Path path = storageProvider.store(relative, bytes);
        boolean hasExisting = !characterReferenceRepository.findByCharacterId(characterId).isEmpty();
        CharacterReference ref = new CharacterReference();
        ref.setCharacterId(characterId);
        ref.setImagePath(path.toString());
        ref.setImageHash(Integer.toHexString(java.util.Arrays.hashCode(bytes)));
        ref.setSource("UPLOADED");
        ref.setPrimary(!hasExisting);
        ref.setLocked(false);
        ref.setPromptText("User-uploaded identity reference for " + character.getName() + ". Preserve facial identity, hair, skin tone, age, body proportions, clothing and defining features.");
        return characterReferenceRepository.save(ref);
    }

    public CharacterReference generateReferenceImage(UUID characterId, String visualStyleOverride, String customPrompt) {
        Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found: " + characterId));

        String canon = characterPromptBuilder.build(List.of(character));
        String style = stylePromptBuilder.build(
                visualStyleOverride != null && !visualStyleOverride.isBlank() ? visualStyleOverride : DEFAULT_STYLE,
                null);
        String supplied = customPrompt == null ? "" : customPrompt.trim();

        // If the user uploaded an identity photo/reference, use it as an actual
        // Qwen reference image rather than simply saving/returning that photo.
        // Qwen's reference-conditioned graph generates a NEW canonical character
        // image from the reference + prompt, preserving the face while applying
        // the requested character design, clothing, proportions and style.
        CharacterReference sourceReference = characterReferenceRepository.findByCharacterId(characterId).stream()
                .filter(r -> "UPLOADED".equalsIgnoreCase(r.getSource()))
                .sorted(java.util.Comparator.comparing(CharacterReference::getCreatedAt).reversed())
                .findFirst()
                .orElse(null);

        String identityInstruction = sourceReference != null
                ? "Use the supplied reference image as the PRIMARY IDENTITY SOURCE. Preserve the same person/character's facial identity: face shape, eyes, eyebrows, nose, mouth, jawline, skin tone, hairline, hairstyle, apparent age and distinctive facial features. Do NOT return or copy the uploaded photograph. Create a NEW clean character-design image that applies the written character prompt and requested visual style to that identity. The reference controls WHO the character is; the written prompt controls HOW the character is designed and presented."
                : "Create a NEW canonical character-design image from the written character description; do not copy an unrelated source image.";

        String positivePrompt = identityInstruction + "\n\n"
                + (supplied.isBlank() ? canon : supplied) + ". " + style
                + ". Official character design reference: ONE single character, full body, standing, "
                + "front three-quarter view, friendly neutral expression, entire figure visible from head to shoes, "
                + "centered, plain soft light-grey background, even studio lighting, crisp clean details, "
                + "apply exact clothing, colors and signature accessories described in the prompt. "
                + "No other characters, no text, no collage, no duplicate views, no photographic background.";
        positivePrompt = positivePrompt.trim();
        String negativePrompt = negativePromptBuilder.build(character.getNegativeConstraints(), stylePromptBuilder.negativeProfile(visualStyleOverride != null && !visualStyleOverride.isBlank() ? visualStyleOverride : DEFAULT_STYLE));

        // Square 1024x1024 -> 2048x2048 after the 2x upscale; refs get resized by the encoder.
        String sourceImagePath = sourceReference != null ? sourceReference.getImagePath() : null;
        var request = new ImageGenerationProvider.ImageGenerationRequest(
                positivePrompt, negativePrompt, 1024, 1024, qwenSteps, 0, null, null, null, sourceImagePath, null);
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
