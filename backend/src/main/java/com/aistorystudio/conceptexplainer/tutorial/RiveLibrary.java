package com.aistorystudio.conceptexplainer.tutorial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A folder of Rive animations the studio may use, chosen by topic.
 *
 * <p>Rive files are designed in the Rive editor; the app cannot invent them. Put {@code .riv} files in the library folder and describe each one in
 * {@code manifest.json}: {@code {"assets":[{"file":"loader.riv","title":"Loading spinner","keywords":["waiting","loading","freeze"]}]}}.
 * A scene whose title / narration contains a keyword (whole word) gets that animation. Only plain file names inside the folder are ever used
 * (no paths), files are capped at 25 MB, and a missing or broken manifest just means "no library", never a failed lesson.</p>
 */
public final class RiveLibrary {
    public record Entry(String file, String title, List<Pattern> keywords, List<String> keywordText) { }

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,80}\\.riv");
    static final long MAX_BYTES = 25L * 1024 * 1024;

    private final Path dir;
    private final List<Entry> entries;

    private RiveLibrary(Path dir, List<Entry> entries) { this.dir = dir; this.entries = entries; }

    public static RiveLibrary empty() { return new RiveLibrary(null, List.of()); }

    /** Loads {@code dir/manifest.json}; never throws. Entries whose file name is unsafe, missing or too big are skipped. */
    public static RiveLibrary load(String directory) {
        if (directory == null || directory.isBlank()) return empty();
        try {
            Path d = Path.of(directory).toAbsolutePath().normalize();
            Path manifest = d.resolve("manifest.json");
            if (!Files.isRegularFile(manifest)) return empty();
            JsonNode root = new ObjectMapper().readTree(Files.readString(manifest));
            List<Entry> out = new ArrayList<>();
            for (JsonNode a : root.path("assets")) {
                String file = a.path("file").asText("");
                if (!SAFE_NAME.matcher(file).matches()) continue;
                Path f = d.resolve(file).normalize();
                if (!f.startsWith(d) || !Files.isRegularFile(f) || Files.size(f) > MAX_BYTES) continue;
                List<Pattern> kws = new ArrayList<>(); List<String> text = new ArrayList<>();
                for (JsonNode k : a.path("keywords")) {
                    String w = k.asText("").trim().toLowerCase(Locale.ROOT);
                    if (w.length() < 3) continue;
                    kws.add(Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(w).replace(" ", "\\E[\\s-]+\\Q") + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE));
                    text.add(w);
                }
                if (!kws.isEmpty()) out.add(new Entry(file, a.path("title").asText(file), kws, text));
            }
            return new RiveLibrary(d, out);
        } catch (IOException | RuntimeException e) {
            return empty();
        }
    }

    public boolean isEmpty() { return entries.isEmpty(); }
    public int size() { return entries.size(); }

    /** The best library file name for this text (most distinct keyword hits, at least one), or empty. */
    public Optional<String> match(String text) {
        if (text == null || text.isBlank() || entries.isEmpty()) return Optional.empty();
        Entry best = null; int bestHits = 0;
        for (Entry e : entries) {
            int hits = 0;
            for (Pattern p : e.keywords()) if (p.matcher(text).find()) hits++;
            if (hits > bestHits) { best = e; bestHits = hits; }
        }
        return best == null ? Optional.empty() : Optional.of(best.file());
    }

    /** The file for a name returned by {@link #match}; empty if the name is not a plain library file. */
    public Optional<Path> resolve(String file) {
        if (dir == null || file == null || !SAFE_NAME.matcher(file).matches()) return Optional.empty();
        Path f = dir.resolve(file).normalize();
        return f.startsWith(dir) && Files.isRegularFile(f) ? Optional.of(f) : Optional.empty();
    }
}
