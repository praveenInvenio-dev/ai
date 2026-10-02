package com.aistorystudio.service;

import com.aistorystudio.domain.Character;
import com.aistorystudio.dto.CreateCharacterRequest;
import com.aistorystudio.repository.CharacterRepository;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
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
        c.setEpisodeId(req.episodeId());
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

    public List<Character> listByEpisode(UUID episodeId) {
        return characterRepository.findByEpisodeId(episodeId);
    }

    /** Creates episode-scoped Character records from the Story Bible for standalone stories.
     *  Existing locked records are preserved. This also bootstraps older drafts created
     *  before episode-scoped characters were introduced. */
    public List<Character> syncEpisodeCharacters(UUID episodeId, JsonNode charactersNode) {
        if (charactersNode == null || !charactersNode.isArray()) return characterRepository.findByEpisodeId(episodeId);
        List<Character> existing = characterRepository.findByEpisodeId(episodeId);
        Map<String, Character> byName = new HashMap<>();
        for (Character c : existing) if (c.getName() != null) byName.put(c.getName().trim().toLowerCase(Locale.ROOT), c);
        List<Character> toSave = new ArrayList<>();
        for (JsonNode n : charactersNode) {
            String name = text(n, "name");
            if (name == null || name.isBlank()) continue;
            String key = name.trim().toLowerCase(Locale.ROOT);
            Character c = byName.get(key);
            if (c == null) {
                c = new Character();
                c.setEpisodeId(episodeId);
                c.setName(name.trim());
            }
            if (!c.isLocked()) {
                c.setSpecies(text(n, "species"));
                c.setAge(text(n, "age"));
                c.setPersonality(text(n, "personality"));
                String canonical = text(n, "canonicalDescription");
                String role = text(n, "role");
                if (canonical == null || canonical.isBlank()) {
                    canonical = (role == null || role.isBlank())
                            ? "A recurring story character named " + name.trim() + ". Preserve the exact visual identity established by the story scenes."
                            : name.trim() + " is " + role + ". Preserve the exact visual identity established by the story scenes.";
                }
                c.setCanonicalDescription(canonical);
                c.setNegativeConstraints(text(n, "negativeConstraints"));
                JsonNode attrs = n.path("attributes");
                if (attrs.isObject() || attrs.isArray()) c.setAttributesJson(attrs.toString());
            }
            toSave.add(c);
        }
        if (!toSave.isEmpty()) characterRepository.saveAll(toSave);
        return characterRepository.findByEpisodeId(episodeId);
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText(null);
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
