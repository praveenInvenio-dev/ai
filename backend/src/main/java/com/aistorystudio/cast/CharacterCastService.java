package com.aistorystudio.cast;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.provider.ImageGenerationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Upload → master prompt → reference sheet → lock. The reference sheet is made from the upload with the Qwen
 * reference workflow and preserves identity AND outfit ("same character, cleaner picture"); if image generation is
 * not available the upload itself becomes the reference, so the feature still works.
 */
@Service
public class CharacterCastService {
    private static final Logger log = LoggerFactory.getLogger(CharacterCastService.class);
    private static final long MAX_UPLOAD = 15L * 1024 * 1024;

    private final CharacterCastStore store;
    private final ProviderGateway gateway;
    /** One at a time: the reference sheet shares the GPU with everything else. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "character-cast-reference");
        t.setDaemon(true);
        return t;
    });

    public CharacterCastService(CharacterCastStore store, ProviderGateway gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    public List<CastCharacter> list() { return store.list(); }

    public CastCharacter get(String id) {
        return store.get(id).orElseThrow(() -> new IllegalArgumentException("Character not found."));
    }

    public CastCharacter create(String name, String masterPrompt, String aliases, MultipartFile file, boolean generate) {
        String n = cleanName(name);
        if (store.findByName(n).isPresent()) throw new IllegalArgumentException("A character called \"" + n + "\" already exists. Names must be unique so every scene picks the right reference.");
        CastCharacter c = new CastCharacter();
        c.id = UUID.randomUUID().toString();
        c.name = n;
        c.masterPrompt = cleanPrompt(masterPrompt);
        c.aliases = parseAliases(aliases, n);
        c.createdAt = System.currentTimeMillis();
        c.uploadPath = storeUpload(c.id, file);
        // The first character is the story's hero until the user says otherwise.
        c.alwaysInClip = store.list().stream().noneMatch(o -> o.alwaysInClip);
        if (c.masterPrompt.isBlank()) c.masterPrompt = describe(c);
        store.save(c);
        if (generate) return generateReference(c.id, false);
        return useUploadAsReference(c.id);
    }

    public CastCharacter update(String id, String name, String masterPrompt, String aliases, Boolean enabled, Boolean alwaysInClip) {
        CastCharacter c = get(id);
        if (name != null) {
            String n = cleanName(name);
            store.findByName(n).filter(o -> !o.id.equals(id)).ifPresent(o -> { throw new IllegalArgumentException("Another character is already called \"" + n + "\"."); });
            c.name = n;
        }
        if (masterPrompt != null) {
            if (c.locked && !cleanPrompt(masterPrompt).equals(c.masterPrompt)) throw new IllegalStateException("Unlock " + c.name + " before changing the master prompt.");
            c.masterPrompt = cleanPrompt(masterPrompt);
        }
        if (aliases != null) c.aliases = parseAliases(aliases, c.name);
        if (enabled != null) c.enabled = enabled;
        if (alwaysInClip != null) c.alwaysInClip = alwaysInClip;
        store.save(c);
        return c;
    }

    public CastCharacter replaceImage(String id, MultipartFile file) {
        CastCharacter c = get(id);
        if (c.locked) throw new IllegalStateException("Unlock " + c.name + " before replacing the uploaded image.");
        String old = c.uploadPath;
        c.uploadPath = storeUpload(c.id, file);
        if (old != null && !old.equals(c.uploadPath)) store.deleteQuietly(old);
        c.referenceStatus = CastCharacter.ReferenceStatus.NONE;
        c.referenceError = null;
        store.save(c);
        return c;
    }

    /** Remembers which character-library entry mirrors this cast reference (Story-to-H3 keyframes). */
    public CastCharacter linkLibrary(String id, String libraryCharacterId) {
        CastCharacter c = get(id);
        c.libraryCharacterId = libraryCharacterId;
        c.librarySyncedReference = c.referencePath;
        store.save(c);
        return c;
    }

    /** (Re)writes the master prompt from the uploaded image with the vision model. */
    public CastCharacter autoDescribe(String id) {
        CastCharacter c = get(id);
        if (c.locked) throw new IllegalStateException("Unlock " + c.name + " before rewriting the master prompt.");
        String d = describe(c);
        if (d.isBlank()) throw new IllegalStateException("The vision model could not describe the image (is a vision model pulled in Ollama?). Write the master prompt by hand.");
        c.masterPrompt = d;
        store.save(c);
        return c;
    }

    /**
     * Precise look description from the upload: hair (colour, texture, length), skin tone, eyes, age, outfit with
     * colours and print, accessories. This is what H3 reads next to the reference image on every clip.
     */
    private String describe(CastCharacter c) {
        try {
            Path p = store.resolvePath(c.uploadPath);
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            String mime = name.endsWith(".png") ? "image/png" : name.endsWith(".webp") ? "image/webp" : "image/jpeg";
            String d = gateway.vision().describeCharacterImage(Files.readAllBytes(p), mime);
            return d == null ? "" : cleanPrompt(d.replaceAll("\\s+", " "));
        } catch (Exception e) {
            log.info("H3 cast: auto-description unavailable for {}: {}", c.name, e.getMessage());
            return "";
        }
    }

    public CastCharacter setLocked(String id, boolean locked) {
        CastCharacter c = get(id);
        if (locked && c.activeReferencePath() == null) throw new IllegalStateException("Generate a reference for " + c.name + " before locking it.");
        c.locked = locked;
        store.save(c);
        return c;
    }

    /** Uses the upload, unchanged, as the reference (for already-clean character sheets). Locks it. */
    public CastCharacter useUploadAsReference(String id) {
        CastCharacter c = get(id);
        if (c.locked) throw new IllegalStateException(c.name + " is locked. Unlock to change the reference.");
        c.referencePath = c.uploadPath;
        c.referenceIsUpload = true;
        c.referenceStatus = CastCharacter.ReferenceStatus.READY;
        c.referenceError = null;
        c.locked = true;
        store.save(c);
        return c;
    }

    /** Starts reference-sheet generation in the background; returns immediately with status GENERATING. */
    public CastCharacter generateReference(String id, boolean force) {
        CastCharacter c = get(id);
        if (c.locked && !force) throw new IllegalStateException(c.name + " is locked, so the reference cannot change. Unlock it first.");
        if (c.referenceStatus == CastCharacter.ReferenceStatus.GENERATING) return c;
        c.referenceStatus = CastCharacter.ReferenceStatus.GENERATING;
        c.referenceError = null;
        c.locked = false;
        store.save(c);
        worker.submit(() -> runGeneration(id));
        return c;
    }

    public void delete(String id) {
        CastCharacter c = get(id);
        store.remove(id);
        store.deleteQuietly(c.uploadPath);
        if (c.referencePath != null && !c.referencePath.equals(c.uploadPath)) store.deleteQuietly(c.referencePath);
    }

    public Path imagePath(String id, String kind) {
        CastCharacter c = get(id);
        String rel = "upload".equals(kind) ? c.uploadPath : c.referencePath;
        Path p = store.resolvePath(rel);
        if (p == null || !Files.isRegularFile(p)) throw new IllegalArgumentException("No " + kind + " image for " + c.name + ".");
        return p;
    }

    // ───────────────────────────────────────────────────────────────── internals

    private void runGeneration(String id) {
        CastCharacter c = store.get(id).orElse(null);
        if (c == null) return;
        try {
            Path upload = store.resolvePath(c.uploadPath);
            String prompt = "Use the supplied reference image as the ONLY identity and costume source. Recreate EXACTLY the same "
                    + "character - identical face, eyes, nose, mouth, skin tone, hairstyle, hair colour, apparent age, body build, "
                    + "clothing, colours, patterns and accessories. Do not redesign, restyle, beautify, age or change anything "
                    + "about the character; only clean up the picture.\n\n"
                    + (c.masterPrompt.isBlank() ? "" : "Character: " + c.name + ". " + c.masterPrompt + "\n\n")
                    + "Official character reference sheet: ONE single character, full body from head to shoes, standing, front "
                    + "three-quarter view, relaxed neutral expression, centered, plain soft light-grey background, even studio "
                    + "lighting, crisp details. No other people, no text, no collage, no extra views, no background scenery.";
            String negative = "different person, changed face, changed outfit, different clothes, different hairstyle, different colours, "
                    + "extra characters, text, watermark, collage, multiple views, cropped feet, blurry, deformed hands";
            var result = gateway.generateImage(new ImageGenerationProvider.ImageGenerationRequest(
                    prompt, negative, 1024, 1024, 0, 0, null, null, null, upload.toString(), null));
            // The gateway can fall back to a placeholder picture when ComfyUI is down; a placeholder must never
            // become a locked identity reference.
            if ("mock".equals(result.workflowUsed()) || String.valueOf(result.modelUsed()).startsWith("mock")) {
                throw new IllegalStateException("ComfyUI image generation is not available (got a placeholder image)");
            }
            String ext = result.fileExtension() == null ? "png" : result.fileExtension();
            String rel = CharacterCastStore.DIR + "/" + c.id + "/reference-" + System.currentTimeMillis() + "." + ext;
            store.store(rel, result.imageBytes());
            CastCharacter fresh = store.get(id).orElse(null);
            if (fresh == null) { store.deleteQuietly(rel); return; }
            if (fresh.referencePath != null && !fresh.referencePath.equals(fresh.uploadPath)) store.deleteQuietly(fresh.referencePath);
            fresh.referencePath = rel;
            fresh.referenceIsUpload = false;
            fresh.referenceStatus = CastCharacter.ReferenceStatus.READY;
            fresh.referenceError = null;
            fresh.locked = true;   // "use it consistently without modifying it"
            store.save(fresh);
            log.info("H3 cast: reference sheet ready for {}", fresh.name);
        } catch (Exception e) {
            log.warn("H3 cast: reference generation failed for {}: {}", c.name, e.getMessage());
            CastCharacter fresh = store.get(id).orElse(null);
            if (fresh == null) return;
            fresh.referenceStatus = CastCharacter.ReferenceStatus.FAILED;
            fresh.referenceError = "Reference generation failed (" + e.getMessage() + "). You can use the upload as the reference instead.";
            store.save(fresh);
        }
    }

    private String storeUpload(String id, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Choose an image of the character.");
        if (file.getSize() > MAX_UPLOAD) throw new IllegalArgumentException("Image is larger than 15 MB.");
        String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        String ext = ct.contains("png") ? "png" : ct.contains("webp") ? "webp" : ct.contains("jpeg") || ct.contains("jpg") ? "jpg" : null;
        if (ext == null) throw new IllegalArgumentException("Upload a PNG, JPG or WebP image.");
        try {
            String rel = CharacterCastStore.DIR + "/" + id + "/upload-" + System.currentTimeMillis() + "." + ext;
            store.store(rel, file.getBytes());
            return rel;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the uploaded image.", e);
        }
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw new IllegalArgumentException("Give the character a name - scenes find their reference by this name.");
        if (n.length() > 40) throw new IllegalArgumentException("Keep the name under 40 characters.");
        return n;
    }

    private static String cleanPrompt(String p) {
        String s = p == null ? "" : p.trim();
        if (s.length() > 800) s = s.substring(0, 800);
        return s;
    }

    private static List<String> parseAliases(String aliases, String name) {
        List<String> out = new ArrayList<>();
        if (aliases == null) return out;
        for (String a : aliases.split(",")) {
            String t = a.trim().replaceAll("\\s+", " ");
            if (t.length() >= 2 && t.length() <= 40 && !t.equalsIgnoreCase(name) && out.stream().noneMatch(t::equalsIgnoreCase)) out.add(t);
            if (out.size() >= 6) break;
        }
        return out;
    }
}
