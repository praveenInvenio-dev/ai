package com.aistorystudio.videoeditor.service;

import com.aistorystudio.videoeditor.domain.VideoClip;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Clean up speech" (Cardboard style): cuts dead air, filler words (um, uh ...) and retakes from talking
 * footage and builds the timeline from what is left. Uses word timings from {@link TranscriptService}.
 *
 * Fillers are English words; dead-air and retake detection work in any language.
 * The decision logic is pure ({@link #compute}) so it is unit-tested without any media.
 */
@Service
public class SpeechCleanupService {

    public record Word(String text, double start, double end) { }

    public record Seg(String text, double start, double end) { }

    public static final class Options {
        public double pauseMax = 0.70;      // silence longer than this is shortened
        public double keepPause = 0.25;     // ... to this much
        public double leadIn = 0.15;        // silence kept before the first word
        public double tailOut = 0.25;       // silence kept after the last word
        public boolean fillers = true;
        public boolean retakes = true;
        public double minKeep = 0.40;       // ranges shorter than this are dropped
    }

    /** Kept ranges (seconds, source time) plus what was removed. */
    public record Result(List<double[]> keep, int fillers, int retakes, double removedSeconds) { }

    private static final Set<String> FILLERS = Set.of("um", "umm", "uh", "uhh", "uhm", "er", "erm", "ah", "hmm", "hm", "mm", "mmm");
    private static final Pattern RESTART = Pattern.compile(
            "^(sorry|oops|no wait|wait|scratch that|let me (redo|start again|start over|try again|say that again|do that again|redo that)|"
                    + "take (two|2|three|3)|one more time|again|from the top)\\b.*", Pattern.CASE_INSENSITIVE);

    static String norm(String w) {
        return w == null ? "" : w.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    // ============================================================== pure logic

    public static Result compute(List<Word> words, List<Seg> segs, double duration, Options o) {
        int n = words.size();
        boolean[] removed = new boolean[n];
        int fillerCount = 0, retakeCount = 0;

        if (o.fillers) {
            for (int i = 0; i < n; i++) {
                String t = norm(words.get(i).text());
                if (FILLERS.contains(t)) { removed[i] = true; fillerCount++; continue; }
                // "you know" / "i mean" used as verbal tics: only when set off by a comma or a pause
                if (i + 1 < n && ((t.equals("you") && norm(words.get(i + 1).text()).equals("know"))
                        || (t.equals("i") && norm(words.get(i + 1).text()).equals("mean")))) {
                    String second = words.get(i + 1).text();
                    double after = i + 2 < n ? words.get(i + 2).start() - words.get(i + 1).end() : 1.0;
                    boolean setOff = second.endsWith(",") || after >= 0.30;
                    if (setOff && !removed[i]) { removed[i] = true; removed[i + 1] = true; fillerCount++; i++; }
                }
            }
        }

        if (o.retakes && segs != null) {
            for (int i = 0; i + 1 < segs.size(); i++) {
                Seg a = segs.get(i), b = segs.get(i + 1);
                if (a.end() - a.start() > 25) continue;
                List<String> ta = tokens(a.text()), tb = tokens(b.text());
                boolean similar = ta.size() >= 3 && tb.size() >= 3 && jaccard(ta, tb) >= 0.60;
                Matcher m = RESTART.matcher(b.text().trim());
                boolean marker = m.matches();
                if (similar || marker) {
                    retakeCount++;
                    markSegment(words, removed, a);
                    if (marker) { // the "sorry, let me redo that" words themselves go too
                        int k = restartWordCount(b.text());
                        markLeadingWords(words, removed, b, k);
                    }
                }
            }
        }

        // keep-ranges around the surviving words
        List<double[]> keep = new ArrayList<>();
        int first = -1, last = -1;
        for (int i = 0; i < n; i++) if (!removed[i]) { if (first < 0) first = i; last = i; }
        if (first < 0) return new Result(keep, fillerCount, retakeCount, duration);

        double rangeStart = Math.max(0, words.get(first).start() - o.leadIn);
        int prev = first;
        for (int i = first + 1; i <= last; i++) {
            if (removed[i]) continue;
            Word a = words.get(prev), b = words.get(i);
            boolean forced = false;
            for (int k = prev + 1; k < i; k++) if (removed[k]) { forced = true; break; }
            double gap = b.start() - a.end();
            if (forced || gap > o.pauseMax) {
                double pad = forced ? 0.05 : o.keepPause / 2.0;
                pad = Math.min(pad, Math.max(0.0, gap / 2.0));
                keep.add(new double[]{rangeStart, a.end() + pad});
                rangeStart = b.start() - pad;
            }
            prev = i;
        }
        keep.add(new double[]{rangeStart, Math.min(duration, words.get(last).end() + o.tailOut)});

        // tidy: clamp, merge touching ranges, drop slivers
        List<double[]> tidy = new ArrayList<>();
        for (double[] r : keep) {
            double s = Math.max(0, r[0]), e = Math.min(duration, r[1]);
            if (e <= s) continue;
            if (!tidy.isEmpty() && s - tidy.get(tidy.size() - 1)[1] < 0.08) tidy.get(tidy.size() - 1)[1] = Math.max(e, tidy.get(tidy.size() - 1)[1]);
            else tidy.add(new double[]{s, e});
        }
        List<double[]> out = new ArrayList<>();
        for (double[] r : tidy) if (r[1] - r[0] >= o.minKeep) out.add(r);
        if (out.isEmpty() && !tidy.isEmpty()) out.add(tidy.get(0));
        double kept = 0;
        for (double[] r : out) kept += r[1] - r[0];
        return new Result(out, fillerCount, retakeCount, Math.max(0, duration - kept));
    }

    private static List<String> tokens(String text) {
        List<String> t = new ArrayList<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("\\s+")) { String x = norm(w); if (!x.isEmpty()) t.add(x); }
        return t;
    }

    private static double jaccard(List<String> a, List<String> b) {
        Set<String> sa = new HashSet<>(a), sb = new HashSet<>(b), inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0 : inter.size() / (double) union.size();
    }

    private static void markSegment(List<Word> words, boolean[] removed, Seg s) {
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            if (w.start() >= s.start() - 0.02 && w.end() <= s.end() + 0.02) removed[i] = true;
        }
    }

    private static void markLeadingWords(List<Word> words, boolean[] removed, Seg s, int count) {
        int done = 0;
        for (int i = 0; i < words.size() && done < count; i++) {
            Word w = words.get(i);
            if (w.start() >= s.start() - 0.02 && w.end() <= s.end() + 0.02) { removed[i] = true; done++; }
        }
    }

    private static final Pattern MARKER_PREFIX = Pattern.compile(
            "^(sorry|oops|no wait|wait|scratch that|let me (?:redo that|redo|start again|start over|try again|say that again|do that again)|"
                    + "take (?:two|2|three|3)|one more time|again|from the top)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * How many words the opening restart talk spans. Phrases chain ("Sorry, let me redo that"), so keep
     * consuming markers until the real sentence starts.
     */
    private static int restartWordCount(String text) {
        String rest = text.trim();
        int total = 0;
        Matcher m;
        while ((m = MARKER_PREFIX.matcher(rest)).find()) {
            total += m.group(1).trim().split("\\s+").length;
            rest = rest.substring(m.end()).replaceFirst("^[\\s,.;:!?-]+", "");
            if (rest.isEmpty()) break;
        }
        return Math.max(1, total);
    }

    // ============================================================== job

    private final TranscriptService transcripts;
    private final VideoEditorProjectService projects;
    private final ProgressReporter progress;

    public SpeechCleanupService(TranscriptService transcripts, VideoEditorProjectService projects, ProgressReporter progress) {
        this.transcripts = transcripts;
        this.projects = projects;
        this.progress = progress;
    }

    /** Builds the timeline from the cleaned speech of every clip in order. Returns a summary for the user. */
    public String run(UUID projectId, UUID jobId, boolean jumpZoom) throws Exception {
        List<VideoClip> clips = projects.listClips(projectId);
        if (clips.isEmpty()) throw new IllegalStateException("Add at least one video first.");
        for (VideoClip c : clips) {
            if (!c.isAnalyzed() || c.getDurationSec() == null) {
                throw new IllegalStateException("Run Analyse first - it measures your clips so the cleanup knows where they end.");
            }
        }
        List<VideoEditorProjectService.TimelineEntry> entries = new ArrayList<>();
        int fillers = 0, retakes = 0;
        double before = 0, after = 0;
        int done = 0;
        for (VideoClip c : clips) {
            progress.report(jobId, "Cleaning speech", 5 + 85 * done / clips.size(), "Listening to \"" + c.getDisplayName() + "\"");
            done++;
            before += c.getDurationSec();
            if (!c.isHasAudio()) { // nothing to clean: keep the clip whole
                entries.add(entry(c.getId(), 0, c.getDurationSec(), null));
                after += c.getDurationSec();
                continue;
            }
            JsonNode t = transcripts.get(projectId, c);
            List<Word> words = new ArrayList<>();
            for (JsonNode w : t.path("words")) words.add(new Word(w.path("word").asText(""), w.path("start").asDouble(), w.path("end").asDouble()));
            List<Seg> segs = new ArrayList<>();
            for (JsonNode s : t.path("segments")) segs.add(new Seg(s.path("text").asText(""), s.path("start").asDouble(), s.path("end").asDouble()));
            Options o = new Options();
            if (!"en".equalsIgnoreCase(t.path("language").asText("en"))) o.fillers = false; // filler list is English only
            Result r = compute(words, segs, c.getDurationSec(), o);
            if (r.keep().isEmpty()) { // no speech found: keep the clip whole rather than lose it
                entries.add(entry(c.getId(), 0, c.getDurationSec(), null));
                after += c.getDurationSec();
                continue;
            }
            fillers += r.fillers();
            retakes += r.retakes();
            for (double[] k : r.keep()) {
                // alternate a subtle punch-in on jump cuts (the classic talking-head rhythm)
                String tech = jumpZoom && entries.size() % 2 == 1 ? "punch_zoom" : null;
                entries.add(entry(c.getId(), k[0], k[1], tech));
                after += k[1] - k[0];
            }
        }
        progress.report(jobId, "Cleaning speech", 95, "Building the timeline");
        projects.replaceTimeline(projectId, entries);
        return String.format(Locale.ROOT, "Cleaned up: %d filler word(s), %d retake(s), %.0f s of dead air/filler removed (%s -> %s), %d cut(s).",
                fillers, retakes, Math.max(0, before - after), clock(before), clock(after), entries.size());
    }

    private static VideoEditorProjectService.TimelineEntry entry(UUID clipId, double s, double e, String technique) {
        return new VideoEditorProjectService.TimelineEntry(clipId, s, e, technique, null, null, 1.0, 1.0, false, false);
    }

    private static String clock(double sec) {
        long t = Math.round(sec);
        return (t / 60) + ":" + String.format("%02d", t % 60);
    }
}
