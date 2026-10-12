package com.aistorystudio.skit;

import com.aistorystudio.provider.StorageProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adventure series live on disk (adventure-series/&lt;id&gt;.json), unlike skit jobs which expire: a series
 * is meant to be continued over days. Each file holds the bible, the locked hero reference and every
 * episode's summary, cliffhanger, final video and last frame (the next episode starts from that frame).
 */
@Component
public class AdventureSeriesStore {

    private static final Logger log = LoggerFactory.getLogger(AdventureSeriesStore.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Episode {
        public int number;
        public String jobId, title, summary, cliffhanger, videoPath, lastFramePath, status;
        public Instant createdAt = Instant.now();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Series {
        public String id, title, logline, premise, language, style;
        public String characterId, characterReferenceId, characterReferencePath;
        public String bibleJson;
        public boolean narration = true;
        public List<Episode> episodes = new ArrayList<>();
        public Instant createdAt = Instant.now(), updatedAt = Instant.now();

        public Episode lastFinished() {
            for (int i = episodes.size() - 1; i >= 0; i--) {
                Episode e = episodes.get(i);
                if (e.videoPath != null) return e;
            }
            return null;
        }
    }

    private final StorageProvider storage;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, Series> cache = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public AdventureSeriesStore(StorageProvider storage) {
        this.storage = storage;
    }

    public synchronized Series create() {
        Series s = new Series();
        s.id = UUID.randomUUID().toString();
        cache.put(s.id, s);
        return s;
    }

    public Series get(String id) {
        loadAll();
        Series s = cache.get(id);
        if (s == null) throw new IllegalArgumentException("Series not found: " + id);
        return s;
    }

    public List<Series> list() {
        loadAll();
        List<Series> out = new ArrayList<>(cache.values());
        out.sort(Comparator.comparing((Series s) -> s.updatedAt).reversed());
        return out;
    }

    public synchronized void save(Series s) {
        s.updatedAt = Instant.now();
        cache.put(s.id, s);
        try {
            storage.store("adventure-series/" + s.id + ".json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(s));
        } catch (Exception e) {
            log.warn("Could not save series {}: {}", s.id, e.getMessage());
        }
    }

    private synchronized void loadAll() {
        if (loaded) return;
        loaded = true;
        try {
            Path dir = storage.resolve("adventure-series");
            if (!Files.isDirectory(dir)) return;
            try (var files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                    try {
                        Series s = mapper.readValue(p.toFile(), Series.class);
                        if (s.id != null) cache.putIfAbsent(s.id, s);
                    } catch (Exception e) {
                        log.warn("Skipping unreadable series file {}: {}", p, e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            log.warn("Could not load adventure series: {}", e.getMessage());
        }
    }
}
