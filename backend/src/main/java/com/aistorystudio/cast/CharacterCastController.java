package com.aistorystudio.cast;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** H3 character references: named, locked reference images that every H3 clip mentioning the name receives. */
@RestController
@RequestMapping("/api/character-cast")
public class CharacterCastController {
    private final CharacterCastService service;

    public CharacterCastController(CharacterCastService service) {
        this.service = service;
    }

    @GetMapping
    public List<CastCharacter> list() { return service.list(); }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(@RequestParam("name") String name,
                                    @RequestParam(value = "masterPrompt", required = false) String masterPrompt,
                                    @RequestParam(value = "aliases", required = false) String aliases,
                                    @RequestParam(value = "generate", defaultValue = "true") boolean generate,
                                    @RequestParam("file") MultipartFile file) {
        return run(() -> service.create(name, masterPrompt, aliases, file, generate));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return run(() -> service.update(id,
                (String) body.get("name"), (String) body.get("masterPrompt"), (String) body.get("aliases"),
                body.get("enabled") instanceof Boolean b ? b : null));
    }

    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> replaceImage(@PathVariable String id, @RequestParam("file") MultipartFile file) {
        return run(() -> service.replaceImage(id, file));
    }

    @PostMapping("/{id}/reference")
    public ResponseEntity<?> generate(@PathVariable String id) { return run(() -> service.generateReference(id, false)); }

    @PostMapping("/{id}/use-upload")
    public ResponseEntity<?> useUpload(@PathVariable String id) { return run(() -> service.useUploadAsReference(id)); }

    @PostMapping("/{id}/lock")
    public ResponseEntity<?> lock(@PathVariable String id, @RequestParam("locked") boolean locked) {
        return run(() -> service.setLocked(id, locked));
    }

    @PostMapping("/{id}/library-link")
    public ResponseEntity<?> linkLibrary(@PathVariable String id, @RequestParam("characterId") String characterId) {
        return run(() -> service.linkLibrary(id, characterId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return run(() -> { service.delete(id); return Map.of("deleted", id); });
    }

    /** kind = upload | reference */
    @GetMapping("/{id}/image/{kind}")
    public ResponseEntity<?> image(@PathVariable String id, @PathVariable String kind) {
        try {
            Path p = service.imagePath(id, kind);
            return ResponseEntity.ok()
                    .contentType(MediaTypeFactory.getMediaType(p.getFileName().toString()).orElse(MediaType.IMAGE_PNG))
                    .cacheControl(CacheControl.noCache())
                    .body(new FileSystemResource(p));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private interface Action { Object get(); }

    private static ResponseEntity<?> run(Action a) {
        try {
            return ResponseEntity.ok(a.get());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }
}
