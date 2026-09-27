package com.aistorystudio.service;

import com.aistorystudio.domain.Character;
import com.aistorystudio.dto.CreateCharacterRequest;
import com.aistorystudio.repository.CharacterRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class CharacterService {

    private final CharacterRepository characterRepository;

    public CharacterService(CharacterRepository characterRepository) {
        this.characterRepository = characterRepository;
    }

    public Character create(CreateCharacterRequest req) {
        Character c = new Character();
        c.setUniverseId(req.universeId());
        c.setName(req.name());
        c.setSpecies(req.species());
        c.setAge(req.age());
        c.setPersonality(req.personality());
        c.setCanonicalDescription(req.canonicalDescription());
        c.setNegativeConstraints(req.negativeConstraints());
        c.setAttributesJson(req.attributesJson());
        return characterRepository.save(c);
    }

    public List<Character> listByUniverse(UUID universeId) {
        return characterRepository.findByUniverseId(universeId);
    }

    public List<Character> listByIds(List<UUID> ids) {
        return characterRepository.findAllById(ids);
    }

    public Character get(UUID id) {
        return characterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Character not found: " + id));
    }

    /** Canonical description may only change through this explicit, user-driven edit path. */
    public Character updateCanonicalDescription(UUID id, String newDescription) {
        Character c = get(id);
        if (c.isLocked()) {
            throw new IllegalStateException("Character is locked and cannot be edited: " + c.getName());
        }
        c.setCanonicalDescription(newDescription);
        c.setVersion(c.getVersion() + 1);
        return characterRepository.save(c);
    }

    public Character setLocked(UUID id, boolean locked) {
        Character c = get(id);
        c.setLocked(locked);
        return characterRepository.save(c);
    }

    /** Null clears the assignment (falls back to the app-wide default TTS
     *  provider) - not an error, a legitimate "unassigned" state. */
    public Character assignVoice(UUID id, UUID voiceProfileId) {
        Character c = get(id);
        c.setVoiceProfileId(voiceProfileId);
        return characterRepository.save(c);
    }

    public Character duplicate(UUID id, String newName) {
        Character source = get(id);
        Character copy = new Character();
        copy.setUniverseId(source.getUniverseId());
        copy.setName(newName);
        copy.setSpecies(source.getSpecies());
        copy.setAge(source.getAge());
        copy.setPersonality(source.getPersonality());
        copy.setCanonicalDescription(source.getCanonicalDescription());
        copy.setNegativeConstraints(source.getNegativeConstraints());
        copy.setAttributesJson(source.getAttributesJson());
        return characterRepository.save(copy);
    }
}
