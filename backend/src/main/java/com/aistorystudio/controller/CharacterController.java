package com.aistorystudio.controller;

import com.aistorystudio.domain.Character;
import com.aistorystudio.domain.CharacterReference;
import com.aistorystudio.dto.CreateCharacterRequest;
import com.aistorystudio.pipeline.promptbuilder.CharacterReferenceSheetPromptBuilder;
import com.aistorystudio.repository.CharacterReferenceRepository;
import org.springframework.transaction.annotation.Transactional;
import com.aistorystudio.service.CharacterImageService;
import com.aistorystudio.service.CharacterService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/characters")
public class CharacterController {

    private final CharacterService characterService;
    private final CharacterImageService characterImageService;
    private final CharacterReferenceRepository characterReferenceRepository;
    private final CharacterReferenceSheetPromptBuilder referenceSheetPromptBuilder = new CharacterReferenceSheetPromptBuilder();

    public CharacterController(CharacterService characterService, CharacterImageService characterImageService,
                                CharacterReferenceRepository characterReferenceRepository) {
        this.characterService = characterService;
        this.characterImageService = characterImageService;
        this.characterReferenceRepository = characterReferenceRepository;
    }

    @PostMapping
    public Character create(@Valid @RequestBody CreateCharacterRequest req) {
        return characterService.create(req);
    }

    @GetMapping
    public List<Character> list(@RequestParam(required = false) UUID universeId,
                                 @RequestParam(required = false) UUID episodeId) {
        if (universeId != null) return characterService.listByUniverse(universeId);
        if (episodeId != null) return characterService.listByEpisode(episodeId);
        throw new IllegalArgumentException("Provide either universeId or episodeId.");
    }

    @GetMapping("/{id}")
    public Character get(@PathVariable UUID id) {
        return characterService.get(id);
    }

    @PutMapping("/{id}/description")
    public Character updateDescription(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return characterService.updateCanonicalDescription(id, body.get("canonicalDescription"));
    }

    @PostMapping("/{id}/lock")
    public Character lock(@PathVariable UUID id, @RequestParam boolean locked) {
        return characterService.setLocked(id, locked);
    }

    /** Assigns a reusable VoiceProfile (Phase 1) to this character - every
     *  future episode's narration for this character uses it. Pass a null/
     *  absent voiceProfileId to clear the assignment. */
    @PutMapping("/{id}/voice")
    public Character assignVoice(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String raw = body.get("voiceProfileId");
        UUID voiceProfileId = (raw == null || raw.isBlank()) ? null : UUID.fromString(raw);
        return characterService.assignVoice(id, voiceProfileId);
    }

    @PostMapping("/{id}/duplicate")
    public Character duplicate(@PathVariable UUID id, @RequestParam String newName) {
        return characterService.duplicate(id, newName);
    }

    /**
     * Generates one standalone reference image from the character's canonical
     * description - a simple, single-prompt test of the image provider (ComfyUI)
     * that doesn't require going through story creation/approval/production.
     * Useful specifically for verifying/debugging ComfyUI setup in isolation.
     */
    @PostMapping(value = "/{id}/upload-reference", consumes = "multipart/form-data")
    public CharacterReference uploadReference(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return characterImageService.uploadReferenceImage(id, file);
    }

    @PostMapping("/{id}/generate-reference")
    public CharacterReference generateReference(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        String visualStyle = body != null ? body.get("visualStyle") : null;
        String prompt = body != null ? body.get("prompt") : null;
        return characterImageService.generateReferenceImage(id, visualStyle, prompt);
    }

    @PostMapping("/{id}/reference-prompt")
    public Map<String, String> referencePrompt(@PathVariable UUID id) {
        Character c = characterService.get(id);
        StringBuilder p = new StringBuilder();
        p.append("Create the permanent MASTER CHARACTER REFERENCE for ")
         .append(c.getName()).append(". ");
        if (c.getSpecies() != null && !c.getSpecies().isBlank()) p.append("Species: ").append(c.getSpecies()).append(". ");
        if (c.getAge() != null && !c.getAge().isBlank()) p.append("Age: ").append(c.getAge()).append(". ");
        p.append("Exact character design: ").append(c.getCanonicalDescription()).append(". ");
        if (c.getPersonality() != null && !c.getPersonality().isBlank()) p.append("Personality reflected subtly in expression: ").append(c.getPersonality()).append(". ");
        if (c.getNegativeConstraints() != null && !c.getNegativeConstraints().isBlank()) p.append("Avoid: ").append(c.getNegativeConstraints()).append(". ");
        p.append("Show full-body front, 3/4 and side/profile views plus face close-up and happy, sad, surprised, excited and curious expressions. Clean neutral studio background. Keep exact colors, facial structure, proportions, clothing, accessories and signature features unchanged across every view. Premium 3D children's animation character-development artwork, soft cinematic lighting, highly readable silhouette, no other characters.");
        return Map.of("prompt", p.toString());
    }

    @PostMapping("/{id}/references/{referenceId}/lock")
    @Transactional
    public CharacterReference lockReference(@PathVariable UUID id, @PathVariable UUID referenceId, @RequestParam boolean locked) {
        CharacterReference target = characterReferenceRepository.findById(referenceId)
                .filter(r -> id.equals(r.getCharacterId()))
                .orElseThrow(() -> new IllegalArgumentException("Character reference not found"));
        List<CharacterReference> refs = characterReferenceRepository.findByCharacterId(id);
        if (locked) {
            refs.forEach(r -> { r.setLocked(false); r.setPrimary(false); });
            target.setLocked(true);
            target.setPrimary(true);
        } else {
            target.setLocked(false);
        }
        characterReferenceRepository.saveAll(refs);
        return characterReferenceRepository.save(target);
    }

    @GetMapping("/{id}/references")
    public List<CharacterReference> listReferences(@PathVariable UUID id) {
        return characterReferenceRepository.findByCharacterId(id);
    }

    /**
     * Assembles the multi-character "master reference sheet" prompt (see
     * CharacterReferenceSheetPromptBuilder) from every character in a
     * universe, for the user to generate a locked reference image from -
     * either via generate-reference above, or an external image tool.
     * Text only; does not itself call any image provider.
     */
    @GetMapping("/reference-sheet-prompt")
    public Map<String, String> referenceSheetPrompt(@RequestParam UUID universeId,
                                                      @RequestParam(required = false) String storyTitle) {
        List<Character> characters = characterService.listByUniverse(universeId);
        if (characters.isEmpty()) {
            throw new IllegalArgumentException("This universe has no characters yet - add at least one first.");
        }
        return Map.of("prompt", referenceSheetPromptBuilder.build(storyTitle, characters));
    }
}
