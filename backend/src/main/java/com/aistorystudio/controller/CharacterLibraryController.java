package com.aistorystudio.controller;

import com.aistorystudio.domain.Character;
import com.aistorystudio.domain.CharacterReference;
import com.aistorystudio.domain.Episode;
import com.aistorystudio.provider.StorageProvider;
import com.aistorystudio.repository.CharacterReferenceRepository;
import com.aistorystudio.repository.CharacterRepository;
import com.aistorystudio.repository.EpisodeRepository;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * One place to find every character whose identity was LOCKED anywhere: Create Story (story-scoped
 * characters), Character Studio (universe characters) or an upload saved from Funny Skits.
 * Before this, story characters (universe = null) were invisible to Funny Skits / other tools,
 * so a lock looked "not saved".
 */
@RestController
@RequestMapping("/api/character-library")
public class CharacterLibraryController {

    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp");

    private final CharacterRepository characters;
    private final CharacterReferenceRepository references;
    private final EpisodeRepository episodes;
    private final StorageProvider storage;

    public CharacterLibraryController(CharacterRepository characters, CharacterReferenceRepository references,
                                      EpisodeRepository episodes, StorageProvider storage) {
        this.characters = characters;
        this.references = references;
        this.episodes = episodes;
        this.storage = storage;
    }

    public record LibraryItem(UUID characterId, String name, String description, String origin, UUID referenceId,
                              boolean locked, Instant updatedAt) { }

    /** includeUnlocked=true also lists characters that only have a primary (not locked) reference. */
    @GetMapping
    public List<LibraryItem> list(@RequestParam(defaultValue = "false") boolean includeUnlocked) {
        List<LibraryItem> out = new ArrayList<>();
        for (Character c : characters.findAll()) {
            List<CharacterReference> refs = references.findByCharacterId(c.getId());
            CharacterReference ref = refs.stream().filter(CharacterReference::isLocked).findFirst()
                    .orElse(includeUnlocked ? refs.stream().filter(CharacterReference::isPrimary).findFirst().orElse(null) : null);
            if (ref == null) continue;
            String origin = c.getEpisodeId() != null
                    ? "Story: " + episodes.findById(c.getEpisodeId()).map(Episode::getTitle).orElse("(deleted story)")
                    : c.getUniverseId() != null ? "Character Studio" : "Uploaded";
            out.add(new LibraryItem(c.getId(), c.getName(), c.getCanonicalDescription(), origin, ref.getId(), ref.isLocked(),
                    c.getUpdatedAt()));
        }
        out.sort(Comparator.comparing(LibraryItem::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** Save an uploaded photo as a reusable, locked character (e.g. from Funny Skits). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public LibraryItem upload(@RequestParam("image") MultipartFile image, @RequestParam("name") String name,
                              @RequestParam(value = "description", required = false) String description) throws IOException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Give the character a name.");
        String fn = image.getOriginalFilename() == null ? "character.png" : image.getOriginalFilename();
        String ext = fn.contains(".") ? fn.substring(fn.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "png";
        if (!IMAGE_EXT.contains(ext)) throw new IllegalArgumentException("Character image must be PNG, JPG or WEBP.");
        Character c = new Character();
        c.setName(name.trim());
        c.setCanonicalDescription(description == null || description.isBlank()
                ? "Uploaded character reference: keep exactly this face, hair, skin tone, age, outfit and proportions."
                : description.trim());
        c = characters.save(c);
        var stored = storage.store("characters/" + c.getId() + "/upload-" + UUID.randomUUID() + "." + ext, image.getBytes());
        CharacterReference ref = new CharacterReference();
        ref.setCharacterId(c.getId());
        ref.setImagePath(stored.toString());
        ref.setSource("UPLOADED");
        ref.setPrimary(true);
        ref.setLocked(true);
        ref = references.save(ref);
        return new LibraryItem(c.getId(), c.getName(), c.getCanonicalDescription(), "Uploaded", ref.getId(), true, c.getUpdatedAt());
    }
}
