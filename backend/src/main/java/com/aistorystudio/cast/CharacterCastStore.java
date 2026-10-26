package com.aistorystudio.cast;

import com.aistorystudio.provider.StorageProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON-file store for the H3 character cast ({storage root}/character-cast/cast.json) plus the name → reference
 * resolver used by the video provider. Deliberately has NO dependency on ProviderGateway, so the video provider can
 * depend on it without a Spring cycle.
 */
@Component
public class CharacterCastStore {
    private static final Logger log = LoggerFactory.getLogger(CharacterCastStore.class);
    static final String DIR = "character-cast";
    private static final String FILE = DIR + "/cast.json";
    /** MiniMaxH3ReferenceToVideo: two character slots per clip (the start image takes a third). */
    public static final int MAX_PER_CLIP = 2;

    private final StorageProvider storage;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private List<CastCharacter> cache;

    /** STUDIO_H3_CAST_ENABLED=false turns automatic reference injection off (the library stays). */
    private boolean injectionEnabled = true;

    public CharacterCastStore(StorageProvider storage) {
        this.storage = storage;
    }

    @org.springframework.beans.factory.annotation.Value("${studio.h3.cast.enabled:true}")
    public void setInjectionEnabled(boolean enabled) {
        this.injectionEnabled = enabled;
    }

    public record Resolved(CastCharacter character, Path image) {}

    public synchronized List<CastCharacter> list() {
        load();
        List<CastCharacter> out = new ArrayList<>(cache);
        out.sort(Comparator.comparingLong(c -> c.createdAt));
        return out;
    }

    public synchronized Optional<CastCharacter> get(String id) {
        load();
        return cache.stream().filter(c -> c.id.equals(id)).findFirst();
    }

    public synchronized Optional<CastCharacter> findByName(String name) {
        load();
        String n = norm(name);
        return cache.stream().filter(c -> norm(c.name).equals(n)).findFirst();
    }

    public synchronized void save(CastCharacter c) {
        load();
        c.updatedAt = System.currentTimeMillis();
        cache.removeIf(x -> x.id.equals(c.id));
        cache.add(c);
        flush();
    }

    public synchronized void remove(String id) {
        load();
        cache.removeIf(x -> x.id.equals(id));
        flush();
    }

    public Path resolvePath(String relative) {
        return relative == null ? null : storage.resolve(relative);
    }

    public Path store(String relative, byte[] bytes) {
        return storage.store(relative, bytes);
    }

    public void deleteQuietly(String relative) {
        if (relative == null) return;
        try { storage.delete(relative); } catch (Exception e) { log.debug("Could not delete {}: {}", relative, e.getMessage()); }
    }

    /**
     * Characters (max {@link #MAX_PER_CLIP}) whose name or alias appears in the prompt, in order of first mention.
     * Only enabled characters with a READY reference image are returned.
     */
    public synchronized List<Resolved> resolve(String prompt) {
        if (!injectionEnabled || prompt == null || prompt.isBlank()) return List.of();
        load();
        record Hit(CastCharacter c, int at) {}
        List<Hit> hits = new ArrayList<>();
        for (CastCharacter c : cache) {
            if (!c.enabled || c.activeReferencePath() == null) continue;
            int first = Integer.MAX_VALUE;
            List<String> names = new ArrayList<>();
            names.add(c.name);
            if (c.aliases != null) names.addAll(c.aliases);
            for (String n : names) {
                if (n == null || n.isBlank()) continue;
                Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(n.trim()) + "(?![\\p{L}\\p{N}])",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(prompt);
                if (m.find()) first = Math.min(first, m.start());
            }
            if (first != Integer.MAX_VALUE) hits.add(new Hit(c, first));
        }
        hits.sort(Comparator.comparingInt(Hit::at));
        if (hits.size() > MAX_PER_CLIP) {
            log.warn("H3 cast: {} characters named in one clip; H3 takes {} references, using the first {} mentioned ({}).",
                    hits.size(), MAX_PER_CLIP, MAX_PER_CLIP,
                    hits.stream().map(h -> h.c().name).toList());
        }
        List<Resolved> out = new ArrayList<>();
        for (Hit h : hits) {
            if (out.size() >= MAX_PER_CLIP) break;
            Path p = storage.resolve(h.c().activeReferencePath());
            if (p != null && Files.isRegularFile(p)) out.add(new Resolved(h.c(), p));
            else log.warn("H3 cast: reference image for '{}' is missing on disk ({})", h.c().name, p);
        }
        return out;
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private void load() {
        if (cache != null) return;
        cache = new ArrayList<>();
        try {
            if (storage.exists(FILE)) {
                String json = Files.readString(storage.resolve(FILE), StandardCharsets.UTF_8);
                cache = new ArrayList<>(mapper.readValue(json, new TypeReference<List<CastCharacter>>() {}));
            }
        } catch (Exception e) {
            log.error("Could not read {} - starting with an empty cast: {}", FILE, e.getMessage());
        }
        // A restart in the middle of generation leaves GENERATING behind; nothing is running any more.
        for (CastCharacter c : cache) {
            if (c.referenceStatus == CastCharacter.ReferenceStatus.GENERATING) {
                c.referenceStatus = c.referencePath != null ? CastCharacter.ReferenceStatus.READY : CastCharacter.ReferenceStatus.FAILED;
                if (c.referencePath == null) c.referenceError = "Interrupted by a restart - generate again.";
            }
        }
    }

    private void flush() {
        try {
            storage.store(FILE, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(cache));
        } catch (Exception e) {
            throw new IllegalStateException("Could not save the character cast: " + e.getMessage(), e);
        }
    }
}
