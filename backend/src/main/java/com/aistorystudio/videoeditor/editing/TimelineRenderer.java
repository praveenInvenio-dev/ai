package com.aistorystudio.videoeditor.editing;

import com.aistorystudio.videoeditor.domain.TimelineClip;
import com.aistorystudio.videoeditor.domain.enums.AspectRatio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Renders a validated timeline to MP4.
 *
 * <h2>The AI boundary, enforced here</h2>
 *
 * This class never receives a string from the planner or the LLM. It receives
 * {@link TimelineClip} rows whose technique IDs have already been resolved
 * against {@link TechniqueLibrary}, and it switches on the resulting
 * {@link TechniqueLibrary.RenderKindEnum}. Every FFmpeg argument is assembled
 * here from typed values, in an argv LIST rather than a shell string - so no
 * filename, prompt, title or model output can be interpreted as shell syntax or
 * as a filter fragment.
 *
 * <h2>Two-pass structure</h2>
 *
 * Each timeline row is rendered to its own normalised segment first, then the
 * segments are joined. Doing it in one giant filter graph is possible but
 * pathological: mixed source resolutions, frame rates and rotations all have to
 * be reconciled, and a graph with twenty inputs is both unreadable and
 * impossible to attribute a failure to. Per-segment rendering also means a
 * failure names the shot that caused it.
 */
@Component
public class TimelineRenderer {

    private static final Logger log = LoggerFactory.getLogger(TimelineRenderer.class);

    /** Shared output frame rate. Mixing 30 and 60 fps sources without a common
     *  rate produces stutter at every join. */
    private static final int OUTPUT_FPS = 30;

    private final String ffmpegBin;
    private final TechniqueLibrary library;

    public TimelineRenderer(@Value("${studio.ffmpeg.binary:ffmpeg}") String ffmpegBin,
                            TechniqueLibrary library) {
        this.ffmpegBin = ffmpegBin;
        this.library = library;
    }

    /** One row plus the resolved source file to read it from. */
    public record Segment(TimelineClip clip, Path sourceFile) {}

    public record RenderRequest(
            List<Segment> segments,
            AspectRatio aspectRatio,
            int height,
            String x264Preset,
            int crf,
            Path musicFile,
            double musicGain,
            Path outputFile,
            int threads,
            boolean audioEnhancement,
            boolean smartReframing,
            String colorGrade) {}

    /**
     * Fixed, built-in color grade presets, each a plain FFmpeg eq/colorbalance
     * filter fragment. Deliberately a closed set keyed by a template-chosen ID
     * rather than a free-text field: the same "no arbitrary FFmpeg from
     * outside application code" boundary that applies to technique IDs
     * applies here, so a grade can never carry filter syntax from a project's
     * custom instructions or an LLM response.
     */
    private static final Map<String, String> COLOR_GRADE_PRESETS = Map.of(
            "none", "",
            // Lifted shadows toward teal, warm highlights, slightly desaturated
            // and higher contrast - the standard "cinematic" look.
            "cinematic_teal_orange",
                "eq=contrast=1.12:saturation=0.90:gamma=0.96,"
              + "colorbalance=rs=-0.06:gs=0.02:bs=0.10:rm=-0.02:bm=0.04:rh=0.08:bh=-0.06",
            // Punchy and saturated for short-form vertical video.
            "punchy_reel", "eq=contrast=1.20:saturation=1.32:brightness=0.02:gamma=1.02",
            // Slightly warm and gently lifted, close to a natural talking-head look.
            "warm_vlog", "eq=contrast=1.05:saturation=1.08:gamma=1.03,colorbalance=rm=0.05:bm=-0.03",
            // Softer contrast, mildly boosted saturation, nothing harsh or flashing.
            "soft_kids", "eq=contrast=0.97:saturation=1.10:brightness=0.03"
    );

    /** Resolves a template's colorGrade id to its FFmpeg filter fragment, or
     *  null if there is nothing to apply. Unknown ids fail open to no grade
     *  rather than failing the render. */
    private String colorGradeFilter(String presetId) {
        if (presetId == null) {
            return null;
        }
        String filter = COLOR_GRADE_PRESETS.get(presetId);
        return (filter == null || filter.isBlank()) ? null : filter;
    }

    /**
     * @param onProgress called with (percent, stage) as real work completes -
     *                   never on a timer.
     */
    public Path render(RenderRequest request, BiConsumer<Integer, String> onProgress) {
        if (request.segments().isEmpty()) {
            throw new IllegalStateException("Nothing to render: the timeline is empty.");
        }
        Path workDir;
        try {
            workDir = Files.createTempDirectory("video-editor-render-");
        } catch (IOException e) {
            throw new IllegalStateException("Could not create a working directory for the render", e);
        }

        try {
            int width = evenScaled(request.aspectRatio(), request.height());
            int height = even(request.height());

            List<Path> segmentFiles = new ArrayList<>();
            List<TimelineClip> rows = request.segments().stream().map(Segment::clip).toList();

            // Pass 1: normalise every shot to identical geometry, frame rate and
            // audio layout. Concatenation and xfade both require this.
            for (int i = 0; i < request.segments().size(); i++) {
                Segment segment = request.segments().get(i);
                Path out = workDir.resolve(String.format("seg-%03d.mp4", i));
                renderSegment(segment, width, height, request, out);
                segmentFiles.add(out);
                // 70% of the bar is segment rendering - it dominates the wall time.
                onProgress.accept((int) ((i + 1) * 70.0 / request.segments().size()),
                        "Rendering shot " + (i + 1) + " of " + request.segments().size());
            }

            // Pass 2: join, applying each row's outgoing transition.
            onProgress.accept(75, "Joining shots");
            Path joined = workDir.resolve("joined.mp4");
            joinSegments(segmentFiles, rows, width, height, request, joined);

            Path finalFile = request.outputFile();
            try {
                Path parent = finalFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                if (request.musicFile() != null) {
                onProgress.accept(90, "Mixing music");
                mixMusic(joined, request, finalFile);
                } else {
                    Files.move(joined, finalFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not finalize rendered video", e);
            }

            onProgress.accept(100, "Done");
            return finalFile;
        } finally {
            deleteTree(workDir);
        }
    }

    // ---- pass 1 ------------------------------------------------------------

    private void renderSegment(Segment segment, int width, int height,
                               RenderRequest request, Path out) {
        TimelineClip row = segment.clip();
        double duration = row.getSourceEndSec() - row.getSourceStartSec();

        List<String> filters = new ArrayList<>();

        // Scale to fit, pad to exact frame. Cover-crop would silently discard
        // the sides of a landscape shot in a 9:16 output; letterboxing keeps
        // the whole frame until smart reframing exists to do it deliberately.
        if (request.smartReframing()) {
            // Deterministic centre crop: keeps the requested canvas filled for
            // portrait/landscape conversions without pretending that a subject
            // detector is available. The crop is stable across preview/final.
            filters.add("scale=" + width + ":" + height + ":force_original_aspect_ratio=increase");
            filters.add("crop=" + width + ":" + height + ":(iw-" + width + ")/2:(ih-" + height + ")/2");
        } else {
            filters.add("scale=" + width + ":" + height + ":force_original_aspect_ratio=decrease");
            filters.add("pad=" + width + ":" + height + ":(ow-iw)/2:(oh-ih)/2:black");
        }
        filters.add("setsar=1");
        filters.add("fps=" + OUTPUT_FPS);

        String grade = colorGradeFilter(request.colorGrade());
        if (grade != null) {
            filters.add(grade);
        }

        // ZOOM is a motion technique on the incoming side of a shot.
        var incoming = resolve(row.getTechniqueIn());
        var outgoing = resolve(row.getTechniqueOut());
        for (var technique : new TechniqueLibrary.Technique[]{incoming, outgoing}) {
            if (technique != null && technique.renderKind() == TechniqueLibrary.RenderKindEnum.ZOOM) {
                double zoom = technique.param("zoomAmount", null);
                // zoompan needs an explicit output size or it defaults to
                // hd720 and silently changes the frame.
                filters.add("zoompan=z='min(zoom+" + String.format("%.5f", (zoom - 1.0) / (duration * OUTPUT_FPS))
                        + ",%.3f)".formatted(zoom)
                        + "':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'"
                        + ":d=1:s=" + width + "x" + height + ":fps=" + OUTPUT_FPS);
                break;
            }
        }

        double speed = row.getSpeed() > 0 ? row.getSpeed() : 1.0;
        var speedTechnique = outgoing != null
                && outgoing.renderKind() == TechniqueLibrary.RenderKindEnum.SPEED ? outgoing : null;
        if (speedTechnique != null) {
            speed = speedTechnique.param("speed", speed);
        }
        if (Math.abs(speed - 1.0) > 0.01) {
            filters.add("setpts=" + String.format("%.5f", 1.0 / speed) + "*PTS");
        }

        // All -i inputs must come before any output option (-vf, -c:v, -af, ...).
        // Previously the codec/filter flags were placed between the main -i and
        // the muted branch's second -i (the anullsrc silent track), so FFmpeg's
        // per-file option scoping attributed -vf etc. to that SECOND input and
        // refused to open it at all:
        //   Option vf cannot be applied to input url anullsrc=... -- you are
        //   trying to apply an input option to an output file or vice versa.
        //   Error opening input files: Invalid argument
        // Every muted shot hit this, i.e. every render whose plan included a
        // muted clip (routine - see EditRuleEngine's `muted = audio < AUDIBLE`)
        // failed before a single frame was encoded.
        List<String> args = new ArrayList<>(List.of(
                ffmpegBin, "-y",
                // -ss before -i seeks by keyframe and is far faster; the small
                // imprecision is irrelevant at shot granularity.
                "-ss", fmt(row.getSourceStartSec()),
                "-t", fmt(duration),
                "-i", segment.sourceFile().toString()));

        if (row.isMuted()) {
            // Silent track rather than no track: concat demands identical
            // stream layout across segments, and a missing audio stream on one
            // shot breaks the join for all of them.
            args.addAll(List.of("-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"));
        }

        args.addAll(List.of("-vf", String.join(",", filters)));

        if (row.isMuted()) {
            args.addAll(List.of("-shortest", "-c:a", "aac", "-b:a", "128k"));
        } else {
            List<String> audioFilters = new ArrayList<>();
            audioFilters.add("aresample=48000");
            if (request.audioEnhancement()) {
                audioFilters.add("highpass=f=80");
                audioFilters.add("lowpass=f=14000");
                audioFilters.add("acompressor=threshold=-18dB:ratio=3:attack=5:release=100:makeup=2");
            }
            if (Math.abs(speed - 1.0) > 0.01) {
                audioFilters.add("atempo=" + String.format("%.4f", clampTempo(speed)));
            }
            if (Math.abs(row.getVolume() - 1.0) > 0.01) {
                audioFilters.add("volume=" + String.format("%.3f", row.getVolume()));
            }
            args.addAll(List.of("-af", String.join(",", audioFilters),
                    "-c:a", "aac", "-b:a", "128k", "-ac", "2"));
        }

        args.addAll(List.of(
                "-c:v", "libx264", "-preset", request.x264Preset(),
                "-crf", String.valueOf(request.crf()),
                "-pix_fmt", "yuv420p",
                "-threads", String.valueOf(request.threads())));

        args.add(out.toString());
        run(args, null);
    }

    /** atempo accepts 0.5..2.0 per instance; clamping avoids a filter error on
     *  an aggressive speed setting. */
    private double clampTempo(double speed) {
        return Math.max(0.5, Math.min(2.0, speed));
    }

    // ---- pass 2 ------------------------------------------------------------

    private void joinSegments(List<Path> files, List<TimelineClip> rows,
                              int width, int height, RenderRequest request, Path out) {
        boolean anyTransition = rows.stream().anyMatch(r -> {
            var t = resolve(r.getTechniqueOut());
            return t != null && t.renderKind() == TechniqueLibrary.RenderKindEnum.XFADE;
        });

        if (!anyTransition || files.size() == 1) {
            // All hard cuts: the concat demuxer copies streams without
            // re-encoding, which is both faster and lossless.
            concatCopy(files, out, request);
            return;
        }
        xfadeChain(files, rows, request, out);
    }

    private void concatCopy(List<Path> files, Path out, RenderRequest request) {
        try {
            Path list = out.getParent().resolve("concat.txt");
            StringBuilder sb = new StringBuilder();
            for (Path f : files) {
                // Single quotes escaped per the concat demuxer's own syntax.
                sb.append("file '").append(f.toAbsolutePath().toString().replace("'", "'\\''"))
                  .append("'\n");
            }
            Files.writeString(list, sb.toString(), StandardCharsets.UTF_8);
            run(List.of(ffmpegBin, "-y", "-f", "concat", "-safe", "0",
                    "-i", list.toString(), "-c", "copy", out.toString()), null);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the concat list", e);
        }
    }

    /**
     * Chains xfade filters across all segments.
     *
     * xfade OVERLAPS clips, so each transition shortens the total by its own
     * duration. The running offset accounts for that; getting it wrong makes
     * later transitions fire at the wrong moment or not at all, which looks
     * like the transitions being ignored rather than mistimed.
     */
    private void xfadeChain(List<Path> files, List<TimelineClip> rows,
                            RenderRequest request, Path out) {
        List<String> args = new ArrayList<>(List.of(ffmpegBin, "-y"));
        for (Path f : files) {
            args.addAll(List.of("-i", f.toString()));
        }

        StringBuilder graph = new StringBuilder();
        String videoLabel = "0:v";
        String audioLabel = "0:a";
        double offset = probeDuration(files.get(0));

        for (int i = 1; i < files.size(); i++) {
            var technique = resolve(rows.get(i - 1).getTechniqueOut());
            boolean isXfade = technique != null
                    && technique.renderKind() == TechniqueLibrary.RenderKindEnum.XFADE;
            double transition = isXfade
                    ? (rows.get(i - 1).getTransitionSec() != null
                        ? rows.get(i - 1).getTransitionSec()
                        : technique.param("durationSeconds", null))
                    : 0.0;
            String kind = isXfade && technique.xfadeTransition() != null
                    ? technique.xfadeTransition() : "fade";

            String vOut = "v" + i;
            String aOut = "a" + i;

            if (transition > 0.05) {
                graph.append('[').append(videoLabel).append("][").append(i).append(":v]")
                     .append("xfade=transition=").append(kind)
                     .append(":duration=").append(fmt(transition))
                     .append(":offset=").append(fmt(Math.max(0, offset - transition)))
                     .append('[').append(vOut).append("];");
                graph.append('[').append(audioLabel).append("][").append(i).append(":a]")
                     .append("acrossfade=d=").append(fmt(transition))
                     .append('[').append(aOut).append("];");
                offset += probeDuration(files.get(i)) - transition;
            } else {
                graph.append('[').append(videoLabel).append("][").append(i).append(":v]")
                     .append("concat=n=2:v=1:a=0[").append(vOut).append("];");
                graph.append('[').append(audioLabel).append("][").append(i).append(":a]")
                     .append("concat=n=2:v=0:a=1[").append(aOut).append("];");
                offset += probeDuration(files.get(i));
            }
            videoLabel = vOut;
            audioLabel = aOut;
        }

        args.addAll(List.of(
                "-filter_complex", graph.toString(),
                "-map", "[" + videoLabel + "]", "-map", "[" + audioLabel + "]",
                "-c:v", "libx264", "-preset", request.x264Preset(),
                "-crf", String.valueOf(request.crf()),
                "-pix_fmt", "yuv420p",
                "-threads", String.valueOf(request.threads()),
                "-c:a", "aac", "-b:a", "192k",
                out.toString()));
        run(args, null);
    }

    // ---- music -------------------------------------------------------------

    /**
     * Mixes background music under the existing audio.
     *
     * sidechaincompress, not a fixed volume: music at a constant level either
     * drowns speech or is inaudible. Ducking keys the music's level off the
     * narration, so it drops only while someone is talking.
     */
    private void mixMusic(Path video, RenderRequest request, Path out) {
        String graph =
                "[1:a]volume=" + String.format("%.3f", request.musicGain()) + ",aloop=loop=-1:size=2e9[music];"
              + "[music][0:a]sidechaincompress=threshold=0.05:ratio=8:attack=20:release=400[ducked];"
              + "[0:a][ducked]amix=inputs=2:duration=first:dropout_transition=0[aout]";

        run(List.of(ffmpegBin, "-y",
                "-i", video.toString(),
                "-i", request.musicFile().toString(),
                "-filter_complex", graph,
                "-map", "0:v", "-map", "[aout]",
                "-c:v", "copy",
                "-c:a", "aac", "-b:a", "192k",
                "-shortest",
                out.toString()), null);
    }

    // ---- helpers -----------------------------------------------------------

    private TechniqueLibrary.Technique resolve(String techniqueId) {
        return techniqueId == null ? null : library.find(techniqueId).orElse(null);
    }

    public double probeDuration(Path file) {
        try {
            Process p = new ProcessBuilder("ffprobe", "-v", "error",
                    "-show_entries", "format=duration", "-of",
                    "default=noprint_wrappers=1:nokey=1", file.toString())
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                p.waitFor();
                return line == null || line.isBlank() ? 0 : Double.parseDouble(line.trim());
            }
        } catch (IOException | InterruptedException | NumberFormatException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Could not probe {}: {}", file.getFileName(), e.getMessage());
            return 0;
        }
    }

    private void run(List<String> args, BiConsumer<Integer, String> onProgress) {
        log.debug("ffmpeg {}", String.join(" ", args));
        try {
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            StringBuilder tail = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // Keep only the tail: an FFmpeg run emits thousands of
                    // progress lines and the useful error is always last.
                    tail.append(line).append('\n');
                    if (tail.length() > 4000) {
                        tail.delete(0, tail.length() - 4000);
                    }
                }
            }
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.HOURS)) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg timed out after 2 hours");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("FFmpeg failed: " + lastMeaningfulLine(tail.toString()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not start FFmpeg. Is it installed in this image?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Render interrupted", e);
        }
    }

    /** FFmpeg's real error is usually a line or two before the end, after the
     *  final progress spam - so pick the last line that is not a stats line. */
    private String lastMeaningfulLine(String output) {
        String[] lines = output.split("\n");
        for (int i = lines.length - 1; i >= 0 && i > lines.length - 15; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty() && !line.startsWith("frame=") && !line.startsWith("size=")) {
                return line;
            }
        }
        return output.isBlank() ? "no output" : lines[lines.length - 1];
    }

    private int evenScaled(AspectRatio ratio, int height) {
        return even(Math.round(height * (ratio.width() / (float) ratio.height())));
    }

    private int even(int value) {
        return value % 2 == 0 ? value : value + 1;
    }

    private String fmt(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", Math.max(0, seconds));
    }

    private void deleteTree(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Temp dir cleanup is best-effort; a leftover file is not
                    // worth failing a successful render over.
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean {}: {}", dir, e.getMessage());
        }
    }
}
