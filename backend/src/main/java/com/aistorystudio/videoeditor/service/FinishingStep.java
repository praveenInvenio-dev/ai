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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    public FinishingStep(TimelineRenderer renderer, FinisherAssets assets, AudioTrackRepository audioTracks,
                         VideoEditorStorageService storage) {
        this.renderer = renderer;
        this.assets = assets;
        this.audioTracks = audioTracks;
        this.storage = storage;
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
            TimelineRenderer.StreamInfo info = renderer.probeStreams(file);
            if (info.width() <= 0) return "Overlays and voiceover were skipped (could not read the video size).";
            work = Files.createTempDirectory("finish-");
            Path out = file.resolveSibling("fin-" + file.getFileName());
            List<String> cmd = TimelineFinisher.command(renderer.ffmpegBin(), file, out, info.width(), info.height(), overlays, voice, 1.0,
                    assets.sfxFiles(), null, assets.fontFile(), work, info.hasAudio(), renderer.probeDuration(file), project.isVoiceoverReplace());
            TimelineFinisher.run(cmd, 30);
            Files.move(out, file, StandardCopyOption.REPLACE_EXISTING);
            return null;
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
