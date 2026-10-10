package com.aistorystudio.videoeditor.service;

import com.aistorystudio.videoeditor.domain.VideoClip;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * "Search what was said": finds moments in the project's footage by their spoken words and returns clip + timecodes,
 * ready to preview or insert on the timeline. Works on the cached transcripts (index them once with the Index job).
 * Keyword search with prefix matching and light accent folding; works for any language whisper transcribed.
 */
@Service
public class TranscriptSearchService {

    public record Line(UUID clipId, String clipName, double start, double end, String text) { }

    public record Hit(UUID clipId, String clipName, double start, double end, String text, double score) { }

    public record Status(int indexedClips, int totalClips) { }

    private static final Set<String> STOP = Set.of("a", "an", "the", "of", "to", "in", "on", "and", "or", "is", "are", "was", "it", "that", "this", "for", "with", "at", "by");

    // ============================================================== pure logic

    /** Lowercase; Latin letters lose their accents, other scripts (Indian languages) are left untouched. */
    static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(n.length());
        char prevBase = 0;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            boolean mark = Character.getType(c) == Character.NON_SPACING_MARK;
            if (mark && prevBase < 0x250) continue;               // accent on a Latin letter: drop it
            sb.append(c);
            if (!mark) prevBase = c;
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC);
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String w : fold(s).split("[^\\p{L}\\p{N}\\p{M}]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** Best matches first. All words must match (as whole words or word starts); if none do, lines matching most words. */
    public static List<Hit> search(List<Line> lines, String query, int limit) {
        List<String> q = tokens(query);
        if (q.isEmpty()) return List.of();
        List<String> meaningful = new ArrayList<>(q);
        if (q.size() >= 3) meaningful.removeIf(STOP::contains);
        if (meaningful.isEmpty()) meaningful = q;
        String phrase = String.join(" ", q);
        List<Hit> strong = new ArrayList<>(), weak = new ArrayList<>();
        for (Line l : lines) {
            List<String> w = tokens(l.text());
            String joined = String.join(" ", w);
            double score = 0;
            int matched = 0;
            for (String t : meaningful) {
                if (w.contains(t)) { score += 2; matched++; }
                else if (w.stream().anyMatch(x -> x.startsWith(t) && t.length() >= 3)) { score += 1; matched++; }
            }
            if (matched == 0) continue;
            if (joined.contains(phrase)) score += 3;
            Hit h = new Hit(l.clipId(), l.clipName(), l.start(), l.end(), l.text(), score);
            if (matched == meaningful.size()) strong.add(h);
            else if (matched * 2 >= meaningful.size()) weak.add(new Hit(h.clipId(), h.clipName(), h.start(), h.end(), h.text(), score - 0.5));
        }
        List<Hit> pool = strong.isEmpty() ? weak : strong;
        pool.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::clipName).thenComparingDouble(Hit::start));
        return pool.size() > limit ? new ArrayList<>(pool.subList(0, limit)) : pool;
    }

    // ============================================================== service

    private final TranscriptService transcripts;
    private final VideoEditorProjectService projects;
    private final ProgressReporter progress;

    public TranscriptSearchService(TranscriptService transcripts, VideoEditorProjectService projects, ProgressReporter progress) {
        this.transcripts = transcripts;
        this.projects = projects;
        this.progress = progress;
    }

    /** Transcribes every clip with audio that has no cached transcript yet. */
    public String index(UUID projectId, UUID jobId) throws Exception {
        List<VideoClip> clips = projects.listClips(projectId);
        if (clips.isEmpty()) throw new IllegalStateException("Add at least one video first.");
        int done = 0, words = 0;
        for (VideoClip c : clips) {
            progress.report(jobId, "Indexing speech", 5 + 90 * done / clips.size(), "Listening to \"" + c.getDisplayName() + "\"");
            done++;
            if (!c.isHasAudio()) continue;
            JsonNode t = transcripts.get(projectId, c);
            words += t.path("words").size();
        }
        return "Indexed the speech of " + clips.size() + " clip(s) (" + words + " words). You can now search what was said.";
    }

    public Status status(UUID projectId) {
        List<VideoClip> clips = projects.listClips(projectId);
        int withAudio = 0, indexed = 0;
        for (VideoClip c : clips) {
            if (c.isHasAudio() || !c.isAnalyzed()) withAudio++;
            else continue;
            if (transcripts.cached(projectId, c).isPresent()) indexed++;
        }
        return new Status(indexed, withAudio);
    }

    public List<Hit> search(UUID projectId, String query, int limit) {
        List<Line> lines = new ArrayList<>();
        for (VideoClip c : projects.listClips(projectId)) {
            transcripts.cached(projectId, c).ifPresent(t -> {
                for (JsonNode s : t.path("segments")) {
                    String text = s.path("text").asText("").trim();
                    if (!text.isEmpty()) lines.add(new Line(c.getId(), c.getDisplayName(), s.path("start").asDouble(), s.path("end").asDouble(), text));
                }
            });
        }
        return search(lines, query, Math.max(1, Math.min(limit, 50)));
    }
}
