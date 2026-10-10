package com.aistorystudio.videoeditor.service;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.videoeditor.domain.VideoClip;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * "Find the best moments" (Cardboard style) for long recordings: picks self-contained 15-60 s moments with a
 * strong start from the transcript. The story LLM chooses (by transcript line numbers, so times are exact);
 * a transparent scoring heuristic is the fallback when no model answers. Nothing is invented: every moment is
 * a real range of the recording, snapped to spoken sentence boundaries.
 */
@Service
public class HighlightService {

    private static final Logger log = LoggerFactory.getLogger(HighlightService.class);

    public static final double MIN_LEN = 15, MAX_LEN = 60;

    public record Moment(UUID clipId, String clipName, double start, double end, String title, String reason, int score) { }

    private final TranscriptService transcripts;
    private final VideoEditorProjectService projects;
    private final ProgressReporter progress;
    private final ProviderGateway gateway;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, List<Moment>> results = new ConcurrentHashMap<>();

    public HighlightService(TranscriptService transcripts, VideoEditorProjectService projects,
                            ProgressReporter progress, ProviderGateway gateway) {
        this.transcripts = transcripts;
        this.projects = projects;
        this.progress = progress;
        this.gateway = gateway;
    }

    public List<Moment> results(UUID projectId) {
        return results.getOrDefault(projectId, List.of());
    }

    // ============================================================== pure logic

    /** One transcript line. */
    public record Seg(String text, double start, double end) { }

    /**
     * Segment index range -> time range inside [MIN_LEN, MAX_LEN]: extends over following sentences when too
     * short, drops trailing sentences when too long. Returns null when no valid range exists.
     */
    public static double[] snapRange(List<Seg> segs, int startSeg, int endSeg, double duration) {
        if (segs.isEmpty()) return null;
        int a = Math.max(0, Math.min(startSeg, segs.size() - 1));
        int b = Math.max(a, Math.min(endSeg, segs.size() - 1));
        while (b + 1 < segs.size() && segs.get(b).end() - segs.get(a).start() < MIN_LEN) b++;
        while (b > a && segs.get(b).end() - segs.get(a).start() > MAX_LEN) b--;
        double s = Math.max(0, segs.get(a).start() - 0.15), e = Math.min(duration, segs.get(b).end() + 0.25);
        double len = e - s;
        if (len < MIN_LEN * 0.8 || len > MAX_LEN + 3) return null;
        return new double[]{s, e};
    }

    private static final Pattern HOOK_WORDS = Pattern.compile(
            "\\b(why|how|secret|mistake|never|always|biggest|worst|best|important|truth|nobody|everyone|stop|learn|surprising|actually|problem|trick|rule)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WEAK_START = Pattern.compile("^(and|but|so|then|also|because|which|that|or)\\b", Pattern.CASE_INSENSITIVE);

    /** Heuristic fallback: windows of 22-45 s scored by questions, numbers, hook words, pace; best non-overlapping first. */
    public static List<Moment> heuristic(UUID clipId, String name, List<Seg> segs, double duration, int count) {
        List<Moment> all = new ArrayList<>();
        for (int a = 0; a < segs.size(); a++) {
            if (WEAK_START.matcher(segs.get(a).text().trim()).find()) continue;   // would start mid-thought
            int b = a;
            while (b + 1 < segs.size() && segs.get(b).end() - segs.get(a).start() < 22) b++;
            double len = segs.get(b).end() - segs.get(a).start();
            if (len < 20 || len > 48) continue;
            double score = 0;
            int words = 0;
            for (int k = a; k <= b; k++) {
                String t = segs.get(k).text();
                words += t.trim().isEmpty() ? 0 : t.trim().split("\\s+").length;
                score += 2.0 * t.chars().filter(c -> c == '?').count() + 1.0 * t.chars().filter(c -> c == '!').count();
                score += 0.6 * t.chars().filter(Character::isDigit).count() / 2.0;
                var m = HOOK_WORDS.matcher(t);
                while (m.find()) score += 1.5;
            }
            if (HOOK_WORDS.matcher(segs.get(a).text()).find() || segs.get(a).text().contains("?")) score += 3;   // a hook right at the start
            double pace = words / len;                                       // words per second: lively > slow
            score += Math.max(0, Math.min(3, (pace - 1.8) * 2));
            double[] r = snapRange(segs, a, b, duration);
            if (r == null) continue;
            int s10 = (int) Math.max(1, Math.min(10, Math.round(3 + score / 2.0)));
            all.add(new Moment(clipId, name, r[0], r[1], titleOf(segs.get(a).text()), "Strong start and lively delivery (picked by scoring, no AI model).", s10));
        }
        return topNonOverlapping(all, count);
    }

    private static String titleOf(String firstLine) {
        String t = firstLine.trim().replaceAll("[\\s]+", " ");
        String[] w = t.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, w.length); i++) sb.append(i == 0 ? "" : " ").append(w[i]);
        return sb.toString().replaceAll("[,;:\\-]+$", "") + (w.length > 8 ? "..." : "");
    }

    /** Highest score first; a moment overlapping an already picked one by more than 25 % is dropped. */
    public static List<Moment> topNonOverlapping(List<Moment> in, int count) {
        List<Moment> sorted = new ArrayList<>(in);
        sorted.sort(Comparator.comparingInt(Moment::score).reversed().thenComparingDouble(Moment::start));
        List<Moment> out = new ArrayList<>();
        for (Moment m : sorted) {
            boolean clash = false;
            for (Moment p : out) {
                if (!p.clipId().equals(m.clipId())) continue;
                double overlap = Math.min(p.end(), m.end()) - Math.max(p.start(), m.start());
                if (overlap > 0.25 * Math.min(p.end() - p.start(), m.end() - m.start())) { clash = true; break; }
            }
            if (!clash) out.add(m);
            if (out.size() >= count) break;
        }
        out.sort(Comparator.comparingDouble(Moment::start));
        return out;
    }

    // ============================================================== job

    /** Finds moments in every clip that is long enough and has speech. Returns a summary. */
    public String find(UUID projectId, UUID jobId) throws Exception {
        List<VideoClip> clips = projects.listClips(projectId);
        if (clips.isEmpty()) throw new IllegalStateException("Add at least one video first.");
        for (VideoClip c : clips) {
            if (!c.isAnalyzed() || c.getDurationSec() == null) {
                throw new IllegalStateException("Run Analyse first - it measures your clips so moments can be placed.");
            }
        }
        List<Moment> all = new ArrayList<>();
        boolean usedModel = false;
        int done = 0;
        for (VideoClip c : clips) {
            progress.report(jobId, "Finding moments", 5 + 85 * done / clips.size(), "Listening to \"" + c.getDisplayName() + "\"");
            done++;
            if (!c.isHasAudio() || c.getDurationSec() < 45) continue;
            JsonNode t = transcripts.get(projectId, c);
            List<Seg> segs = new ArrayList<>();
            for (JsonNode s : t.path("segments")) segs.add(new Seg(s.path("text").asText("").trim(), s.path("start").asDouble(), s.path("end").asDouble()));
            segs.removeIf(s -> s.text().isEmpty());
            if (segs.size() < 4) continue;
            List<Moment> fromModel = askModel(c, segs);
            if (!fromModel.isEmpty()) { all.addAll(fromModel); usedModel = true; }
            else all.addAll(heuristic(c.getId(), c.getDisplayName(), segs, c.getDurationSec(), 6));
        }
        List<Moment> top = topNonOverlapping(all, 6);
        if (top.isEmpty()) throw new IllegalStateException("No suitable moments found: highlights need a recording of at least 45 s with clear speech.");
        results.put(projectId, top);
        return top.size() + " moment(s) found" + (usedModel ? " by the story model." : " by scoring (the story model did not answer).");
    }

    private List<Moment> askModel(VideoClip clip, List<Seg> segs) {
        List<Moment> out = new ArrayList<>();
        final int chunk = 70;
        for (int from = 0; from < segs.size(); from += chunk) {
            int to = Math.min(segs.size(), from + chunk);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) sb.append('[').append(i).append("] ").append(clock(segs.get(i).start())).append(' ').append(segs.get(i).text()).append('\n');
            try {
                String raw = gateway.llm().generateStructured(SYSTEM, "Transcript lines:\n" + sb);
                int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
                JsonNode n = mapper.readTree(a >= 0 && b > a ? raw.substring(a, b + 1) : raw);
                for (JsonNode m : n.path("moments")) {
                    int s = m.path("startSeg").asInt(-1), e = m.path("endSeg").asInt(-1);
                    if (s < from || e < s || e >= to) continue;                         // only lines that were in this chunk
                    double[] r = snapRange(segs, s, e, clip.getDurationSec());
                    if (r == null) continue;
                    out.add(new Moment(clip.getId(), clip.getDisplayName(), r[0], r[1], text(m, "title", titleOf(segs.get(s).text())),
                            text(m, "reason", "Picked by the story model."), Math.max(1, Math.min(10, m.path("score").asInt(5)))));
                }
            } catch (Exception ex) {
                log.warn("Highlight model call failed for chunk {}-{}: {}", from, to, ex.getMessage());
                return List.of();   // caller falls back to the heuristic for the whole clip
            }
        }
        return out;
    }

    private static String text(JsonNode n, String f, String d) {
        String v = n.path(f).asText("").trim();
        return v.isEmpty() ? d : v;
    }

    private static final String SYSTEM = """
            You find the most shareable short-video moments in a recording's transcript (Reels / Shorts / TikTok).
            Each moment is a run of consecutive transcript lines that:
            - starts with a hook (a question, a bold claim, a surprise, a story start) - never mid-sentence or with 'and/so/but';
            - is self-contained: a viewer who saw nothing else understands it;
            - ends on a complete thought, ideally a punchline, lesson or conclusion;
            - lasts 15-60 seconds.
            Return up to 2 moments for these lines, best first. Titles are short (max 8 words), in the transcript's language.
            Return JSON only: {"moments":[{"startSeg":<first line number>,"endSeg":<last line number>,"title":"...","reason":"one sentence","score":<1-10>}]}
            Use ONLY the line numbers shown. If nothing is good enough return {"moments":[]}.
            """;

    private static String clock(double sec) {
        long t = Math.round(sec);
        return String.format(Locale.ROOT, "%d:%02d", t / 60, t % 60);
    }

    /** Builds a one-clip timeline from moment {@code index} (0-based) and prepares it as a vertical short with captions. */
    public String use(UUID projectId, int index, boolean vertical, boolean captions) {
        List<Moment> list = results(projectId);
        if (index < 0 || index >= list.size()) throw new IllegalArgumentException("There is no moment " + (index + 1) + " - run \"Find best moments\" first.");
        Moment m = list.get(index);
        projects.prepareShort(projectId, vertical, captions);
        projects.replaceTimeline(projectId, List.of(new VideoEditorProjectService.TimelineEntry(
                m.clipId(), m.start(), m.end(), null, null, null, 1.0, 1.0, false, false)));
        return "Timeline set to \"" + m.title() + "\" (" + clock(m.start()) + " - " + clock(m.end()) + ")"
                + (vertical ? ", vertical" : "") + (captions ? ", captions on" : "") + ". Press Preview or Render.";
    }
}
