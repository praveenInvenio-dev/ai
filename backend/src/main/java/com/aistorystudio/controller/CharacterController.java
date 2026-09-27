package com.aistorystudio.controller;

import com.aistorystudio.domain.Character;
import com.aistorystudio.domain.CharacterReference;
import com.aistorystudio.dto.CreateCharacterRequest;
import com.aistorystudio.pipeline.promptbuilder.CharacterReferenceSheetPromptBuilder;
import com.aistorystudio.repository.CharacterReferenceRepository;
import com.aistorystudio.service.CharacterImageService;
import com.aistorystudio.service.CharacterService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

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
    public List<Character> listByUniverse(@RequestParam UUID universeId) {
        return characterService.listByUniverse(universeId);
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
    @PostMapping("/{id}/generate-reference")
    public CharacterReference generateReference(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        String visualStyle = body != null ? body.get("visualStyle") : null;
        return characterImageService.generateReferenceImage(id, visualStyle);
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
