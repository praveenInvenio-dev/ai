package com.aistorystudio.videoeditor.service;

import com.aistorystudio.qa.RenderQa;
import com.aistorystudio.videoeditor.domain.AudioTrack;
import com.aistorystudio.videoeditor.domain.VideoEditorProject;
import com.aistorystudio.videoeditor.domain.enums.AudioTrackKind;
import com.aistorystudio.videoeditor.editing.FinisherAssets;
import com.aistorystudio.videoeditor.editing.TimelineFinisher;
import com.aistorystudio.videoeditor.editing.TimelineRenderer;
import com.aistorystudio.videoeditor.repository.AudioTrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import com.aistorystudio.videoeditor.config.VideoEditorProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.time.Duration;

/**
 * The part of a render that happens AFTER the cut is joined: text overlays, zoom moments, sound effects, the voiceover with the
 * rest of the sound ducked under it, then (final renders only) the export container / resolution / frame rate and a quality
 * check. A failure here never fails the render: the cut is delivered without the extras and the reason goes in the note.
 */
@Component
public class FinishingStep {

    private static final Logger log = LoggerFactory.getLogger(FinishingStep.class);

    private final TimelineRenderer renderer;
    private final FinisherAssets assets;
    private final AudioTrackRepository audioTracks;
    private final VideoEditorStorageService storage;
    private final WebClient worker;
    private static final ObjectMapper JSON = new ObjectMapper();

    public FinishingStep(TimelineRenderer renderer, FinisherAssets assets, AudioTrackRepository audioTracks,
                         VideoEditorStorageService storage, WebClient.Builder builder,
                         VideoEditorProperties properties) {
        this.renderer = renderer;
        this.assets = assets;
        this.audioTracks = audioTracks;
        this.storage = storage;
        this.worker = builder.baseUrl(properties.getWorkerBaseUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)).build();
    }

    /** The voiceover file of a project, if one was added or generated. */
    public Optional<Path> voiceoverFile(UUID projectId) {
        return audioTracks.findFirstByProjectIdAndKind(projectId, AudioTrackKind.VOICEOVER)
                .map(t -> storage.resolveWithin(storage.audioDir(projectId), t.getStoredFilename()))
                .filter(Files::exists);
    }

    /** Part of the preview cache key: any change to these changes the pixels / sound. */
    public String cacheKey(VideoEditorProject project, UUID projectId) {
        String vo = audioTracks.findFirstByProjectIdAndKind(projectId, AudioTrackKind.VOICEOVER).map(AudioTrack::getStoredFilename).orElse("");
        return project.getLookIntensity() + "|" + project.getOverlaysJson() + "|" + vo;
    }

    /** Runs the content pass in place (previews and finals alike). Returns a note, or null when everything was applied. */
    public String applyContent(VideoEditorProject project, UUID projectId, Path file) {
        List<TimelineFinisher.Overlay> overlays = TimelineFinisher.parse(project.getOverlaysJson());
        Path voice = voiceoverFile(projectId).orElse(null);
        if (!TimelineFinisher.needed(overlays, voice, null)) return null;
        Path work = null;
        try {
            // Run the optional depth-aware overlays first. The worker segments the foreground
            // and composites text as background -> text -> person. Any failure is reported
            // without dropping the rest of the normal finishing pass.
            String depthNote = null;
            List<TimelineFinisher.Overlay> depthOverlays = overlays.stream()
                    .filter(o -> "text_behind_person".equals(o.type())).toList();
            for (int i = 0; i < depthOverlays.size(); i++) {
                TimelineFinisher.Overlay overlay = depthOverlays.get(i);
                Path out = file.resolveSibling("depth-" + i + "-" + file.getFileName());
                try {
                    Map<String, Object> payload = Map.of(
                            "source", file.toAbsolutePath().toString(),
                            "output", out.toAbsolutePath().toString(),
                            "text", overlay.text(),
                            "start", overlay.start(),
                            "duration", Math.max(0.2, overlay.end() - overlay.start()),
                            "x", overlay.xPercent(),
                            "y", overlay.yPercent(),
                            "fontSize", 72,
                            "color", depthColor(overlay.color()),
                            "animation", overlay.animation());
                    JsonNode response = worker.post().uri("/api/depth-text").bodyValue(payload)
                            .retrieve().bodyToMono(JsonNode.class).block(Duration.ofHours(2));
                    if (response == null || !"completed".equals(response.path("status").asText()) || !Files.isRegularFile(out)) {
                        throw new IllegalStateException(response == null ? "Worker returned no result." : response.path("error").asText("Depth compositor did not produce output."));
                    }
                    Files.move(out, file, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception depthFailure) {
                    try { Files.deleteIfExists(out); } catch (Exception ignored) { }
                    log.warn("Depth-aware overlay failed for project {}: {}", projectId, depthFailure.getMessage());
                    if (depthNote == null) depthNote = "One or more behind-person text overlays were skipped (" + firstLine(depthFailure.getMessage()) + ").";
                }
            }
            overlays = overlays.stream().filter(o -> !"text_behind_person".equals(o.type())).toList();
            if (overlays.isEmpty() && voice == null) return depthNote;
            TimelineRenderer.StreamInfo info = renderer.probeStreams(file);
            if (info.width() <= 0) return "Overlays and voiceover were skipped (could not read the video size).";
            work = Files.createTempDirectory("finish-");
            Path out = file.resolveSibling("fin-" + file.getFileName());
            List<String> cmd = TimelineFinisher.command(renderer.ffmpegBin(), file, out, info.width(), info.height(), overlays, voice, 1.0,
                    assets.sfxFiles(), null, assets.fontFile(), work, info.hasAudio(), renderer.probeDuration(file), project.isVoiceoverReplace());
            TimelineFinisher.run(cmd, 30);
            Files.move(out, file, StandardCopyOption.REPLACE_EXISTING);
            return depthNote;
        } catch (Exception e) {
            log.warn("Finishing pass failed for project {}: {}", projectId, e.getMessage());
            return "Rendered without overlays / voiceover (" + firstLine(e.getMessage()) + ")";
        } finally {
            deleteQuietly(work);
        }
    }

    /** Final renders only: container, resolution, frame rate. Returns the file to deliver (its extension may change). */
    public Path applyExport(VideoEditorProject project, Path file, UUID jobId, StringBuilder note) {
        TimelineFinisher.Export ex = new TimelineFinisher.Export(project.getExportFormat(), project.getExportHeight(), project.getExportFps());
        boolean needed = !"mp4".equals(ex.ext()) || ex.height() != null || ex.fps() != null;
        if (!needed) return file;
        Path out = file.resolveSibling(jobId + "." + ex.ext());
        try {
            TimelineFinisher.run(TimelineFinisher.exportCommand(renderer.ffmpegBin(), file, out, ex), 60);
            Files.deleteIfExists(file);
            return out;
        } catch (Exception e) {
            log.warn("Export conversion failed: {}", e.getMessage());
            note.append(note.length() > 0 ? " " : "").append("Export settings were not applied (").append(firstLine(e.getMessage())).append(").");
            try { Files.deleteIfExists(out); } catch (Exception ignored) { }
            return file;
        }
    }

    /**
     * Final renders only, LAST step: evens the volume out to the chosen target (two passes, linear gain, picture untouched). The old and
     * new loudness go in the note. A failure never fails the render; the file is then delivered as it was.
     */
    public void applyLoudness(VideoEditorProject project, Path file, StringBuilder note) {
        com.aistorystudio.qa.Loudness.Target t = com.aistorystudio.qa.Loudness.of(project.getLoudness());
        if (t == null) return;
        Path out = file.resolveSibling("ln-" + file.getFileName());
        try {
            Double before = com.aistorystudio.qa.Loudness.normalize(renderer.ffmpegBin(), file, out, t);
            if (before == null) { Files.deleteIfExists(out); return; }   // no audio or silence: nothing to even out
            Files.move(out, file, StandardCopyOption.REPLACE_EXISTING);
            note.append(note.length() > 0 ? " " : "").append(String.format(java.util.Locale.ROOT, "Loudness: %.1f LUFS -> %.0f LUFS (%s).", before, t.lufs(), t.id()));
        } catch (Exception e) {
            log.warn("Loudness step failed: {}", e.getMessage());
            note.append(note.length() > 0 ? " " : "").append("Loudness was not adjusted (").append(firstLine(e.getMessage())).append(").");
            try { Files.deleteIfExists(out); } catch (Exception ignored) { }
        }
    }

    /** Final renders only: a quick look for silent gaps, black frames, clipping. */
    public String qualityNote(Path file, double expectedSec) {
        List<RenderQa.Finding> f = RenderQa.review(renderer.ffmpegBin(), file, new RenderQa.Options(6.0, expectedSec, false));
        if (f.isEmpty()) return null;
        return "Quality check: " + String.join(" ", RenderQa.summarise(f, 3));
    }

    private static String depthColor(String color) {
        return switch (color == null ? "yellow" : color.toLowerCase(java.util.Locale.ROOT)) {
            case "white" -> "255,255,255";
            case "yellow" -> "255,214,10";
            case "black" -> "0,0,0";
            case "red" -> "255,77,77";
            case "green" -> "61,220,132";
            case "blue" -> "77,163,255";
            case "pink" -> "255,93,162";
            case "orange" -> "255,159,28";
            default -> "255,214,10";
        };
    }

    private static String firstLine(String m) {
        if (m == null) return "unknown error";
        int i = m.indexOf('\n');
        String s = i > 0 ? m.substring(0, i) : m;
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
        } catch (Exception ignored) { }
    }
}
