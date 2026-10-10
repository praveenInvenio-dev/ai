package com.aistorystudio.videoeditor.service;

import com.aistorystudio.videoeditor.domain.AudioTrack;
import com.aistorystudio.videoeditor.domain.TimelineClip;
import com.aistorystudio.videoeditor.domain.VideoClip;
import com.aistorystudio.videoeditor.domain.enums.AudioTrackKind;
import com.aistorystudio.videoeditor.repository.AudioTrackRepository;
import com.aistorystudio.videoeditor.repository.VideoClipRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Beat sync (CapCut-style): finds the beat grid of the project's music (video-worker /api/beats) and
 * nudges every cut onto the nearest beat, so the edit "dances" with the track.
 * A cut only moves when it can do so safely: small shift, shot stays long enough, the source clip has
 * footage left to extend into. Locked rows are never touched.
 */
@Service
public class BeatSyncService {

    private static final Logger log = LoggerFactory.getLogger(BeatSyncService.class);

    private final AudioTrackRepository audioTracks;
    private final VideoClipRepository clips;
    private final VideoEditorStorageService storage;
    private final String workerBaseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public BeatSyncService(AudioTrackRepository audioTracks, VideoClipRepository clips, VideoEditorStorageService storage,
                           @Value("${studio.video-editor.worker-base-url:http://video-worker:5010}") String workerBaseUrl) {
        this.audioTracks = audioTracks;
        this.clips = clips;
        this.storage = storage;
        this.workerBaseUrl = workerBaseUrl;
    }

    /** One cut in the pure snapping step. */
    public static final class Cut {
        public final double start, end, speed, maxEnd;
        public double newEnd;
        public boolean locked;

        public Cut(double start, double end, double speed, double maxEnd, boolean locked) {
            this.start = start; this.end = end; this.newEnd = end; this.speed = speed > 0 ? speed : 1.0;
            this.maxEnd = maxEnd; this.locked = locked;
        }
        double duration() { return (newEnd - start) / speed; }
    }

    /**
     * Moves each boundary (except after the last shot) to the nearest beat when |shift| <= maxShift,
     * the shot stays >= minShot, and the source has room. Returns how many cuts moved.
     */
    public static int snap(List<Cut> cuts, List<Double> beats, double maxShift, double minShot) {
        if (beats == null || beats.isEmpty()) return 0;
        int moved = 0;
        double t = 0;
        for (int i = 0; i < cuts.size(); i++) {
            Cut c = cuts.get(i);
            double cut = t + c.duration();
            if (i < cuts.size() - 1 && !c.locked) {
                double best = Double.NaN;
                for (double b : beats) if (Double.isNaN(best) || Math.abs(b - cut) < Math.abs(best - cut)) best = b;
                double delta = best - cut;
                double newEnd = c.newEnd + delta * c.speed;
                boolean ok = Math.abs(delta) > 0.012 && Math.abs(delta) <= Math.min(maxShift, 0.35 * c.duration())
                        && c.duration() + delta >= minShot && newEnd <= c.maxEnd && newEnd > c.start;
                if (ok) {
                    c.newEnd = newEnd;
                    cut = t + c.duration();
                    moved++;
                }
            }
            t = cut;
        }
        return moved;
    }

    /** Applies beat sync to the planned rows in place. Returns a note for the plan rationale ("" = nothing to say). */
    public String apply(UUID projectId, List<TimelineClip> rows) {
        AudioTrack music = audioTracks.findFirstByProjectIdAndKind(projectId, AudioTrackKind.MUSIC).orElse(null);
        if (music == null) return "Beat sync: add a music track first - cuts were left as they are.";
        Path file = storage.resolveWithin(storage.audioDir(projectId), music.getStoredFilename());
        if (!Files.exists(file)) return "Beat sync: the music file is missing - cuts were left as they are.";
        try {
            var body = mapper.createObjectNode().put("source", file.toAbsolutePath().toString());
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(workerBaseUrl + "/api/beats"))
                    .timeout(Duration.ofMinutes(3)).header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var resp = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            JsonNode n = mapper.readTree(resp.body());
            if (resp.statusCode() >= 300) return "Beat sync skipped: " + n.path("error").asText("beat detector failed");
            double tempo = n.path("tempo").asDouble(), conf = n.path("confidence").asDouble();
            List<Double> beats = new ArrayList<>();
            for (JsonNode b : n.path("beats")) beats.add(b.asDouble());
            if (beats.size() < 4 || tempo <= 0 || conf < 1.1) {
                return "Beat sync: no clear beat found in the music - cuts were left as they are.";
            }
            Map<UUID, Double> sourceLen = new HashMap<>();
            for (VideoClip c : clips.findByProjectIdOrderBySortOrderAsc(projectId)) {
                sourceLen.put(c.getId(), c.getDurationSec() == null ? Double.MAX_VALUE : c.getDurationSec());
            }
            List<Cut> cuts = new ArrayList<>();
            for (TimelineClip r : rows) {
                cuts.add(new Cut(r.getSourceStartSec(), r.getSourceEndSec(), r.getSpeed(),
                        sourceLen.getOrDefault(r.getClipId(), Double.MAX_VALUE) - 0.05, r.isLocked()));
            }
            int moved = snap(cuts, beats, 0.28, 0.7);
            for (int i = 0; i < rows.size(); i++) rows.get(i).setSourceEndSec(cuts.get(i).newEnd);
            return String.format(java.util.Locale.ROOT, "Beat sync: %d of %d cuts moved onto the beat (music %.0f BPM).",
                    moved, Math.max(0, rows.size() - 1), tempo);
        } catch (Exception e) {
            log.warn("Beat sync failed for project {}: {}", projectId, e.getMessage());
            return "Beat sync skipped: " + e.getMessage();
        }
    }
}
