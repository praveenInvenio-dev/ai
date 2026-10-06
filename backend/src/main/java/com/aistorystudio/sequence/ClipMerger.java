package com.aistorystudio.sequence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Joins the per-scene clips of a sequence into one long video.
 *
 * Clips from different engines differ in size, fps and audio (H3 has native audio, Wan has
 * none), so every clip is first normalised to ONE format (size, 24 fps, yuv420p, AAC 48 kHz
 * stereo - a silent track is added when a clip has no audio), then the normalised clips are
 * either stream-copied together (hard cuts) or chained with xfade/acrossfade (crossfade).
 *
 * No Spring / logging dependencies on purpose: this class is only ffmpeg arguments and
 * process handling, so it can be exercised on its own.
 */
public final class ClipMerger {

    private static final int FPS = 24;

    private ClipMerger() {
    }

    /** Merges {@code clips} (in order) into {@code output}. Returns the output path. */
    public static Path merge(List<Path> clips, Path output, int width, int height, double crossfadeSeconds) {
        if (clips == null || clips.isEmpty()) {
            throw new IllegalArgumentException("There are no finished clips to merge.");
        }
        Path work = null;
        try {
            work = Files.createTempDirectory("sequence-merge-");
            List<Path> normalized = new ArrayList<>();
            List<Double> durations = new ArrayList<>();
            for (int i = 0; i < clips.size(); i++) {
                Path out = work.resolve(String.format(Locale.ROOT, "n%03d.mp4", i));
                run(normalizeArgs(clips.get(i), out, width, height, hasAudio(clips.get(i))));
                normalized.add(out);
                durations.add(probeDuration(out));
            }
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.deleteIfExists(output);
            double cf = Math.max(0, crossfadeSeconds);
            double shortest = durations.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            if (normalized.size() == 1) {
                Files.copy(normalized.get(0), output);
            } else if (cf <= 0.01 || shortest < cf * 3) {
                run(concatCopyArgs(work, normalized, output));
            } else {
                try {
                    run(crossfadeArgs(normalized, durations, output, cf));
                } catch (RuntimeException transitionFailure) {
                    // Production fallback: never lose a complete scene sequence because one
                    // xfade/acrossfade graph is incompatible with a clip's timestamps.
                    run(concatCopyArgs(work, normalized, output));
                }
            }
            return output;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not merge the sequence clips", e);
        } finally {
            if (work != null) {
                deleteRecursively(work);
            }
        }
    }

    // ---- argument builders (package-private for tests) ----

    static List<String> normalizeArgs(Path in, Path out, int w, int h, boolean hasAudio) {
        List<String> a = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-i", in.toString()));
        String vf = "scale=" + w + ":" + h + ":force_original_aspect_ratio=decrease:flags=lanczos,"
                + "pad=" + w + ":" + h + ":(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=" + FPS + ",format=yuv420p";
        if (hasAudio) {
            a.addAll(List.of("-vf", vf, "-af", "aresample=48000,aformat=channel_layouts=stereo",
                    "-map", "0:v:0", "-map", "0:a:0"));
        } else {
            a.addAll(List.of("-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-vf", vf,
                    "-map", "0:v:0", "-map", "1:a:0", "-shortest"));
        }
        a.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2", "-movflags", "+faststart", out.toString()));
        return a;
    }

    static List<String> concatCopyArgs(Path work, List<Path> clips, Path output) throws IOException {
        StringBuilder list = new StringBuilder();
        for (Path c : clips) {
            list.append("file '").append(c.toAbsolutePath().toString().replace("'", "'\\''")).append("'\n");
        }
        Path listFile = work.resolve("list.txt");
        Files.writeString(listFile, list.toString(), StandardCharsets.UTF_8);
        return List.of("ffmpeg", "-nostdin", "-y", "-f", "concat", "-safe", "0", "-i", listFile.toString(),
                "-c", "copy", "-movflags", "+faststart", output.toString());
    }

    static List<String> crossfadeArgs(List<Path> clips, List<Double> durations, Path output, double cf) {
        List<String> a = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y"));
        for (Path c : clips) {
            a.addAll(List.of("-i", c.toString()));
        }
        StringBuilder g = new StringBuilder();
        String vPrev = "[0:v]";
        String aPrev = "[0:a]";
        double offset = 0;
        for (int i = 1; i < clips.size(); i++) {
            offset += durations.get(i - 1) - cf;
            String vOut = i == clips.size() - 1 ? "[vout]" : "[v" + i + "]";
            String aOut = i == clips.size() - 1 ? "[aout]" : "[a" + i + "]";
            g.append(vPrev).append('[').append(i).append(":v]xfade=transition=fade:duration=")
                    .append(fmt(cf)).append(":offset=").append(fmt(offset)).append(vOut).append(';');
            g.append(aPrev).append('[').append(i).append(":a]acrossfade=d=").append(fmt(cf)).append(aOut).append(';');
            vPrev = vOut;
            aPrev = aOut;
        }
        g.setLength(g.length() - 1);
        a.addAll(List.of("-filter_complex", g.toString(), "-map", "[vout]", "-map", "[aout]",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", output.toString()));
        return a;
    }

    // ---- process helpers ----

    static boolean hasAudio(Path file) {
        try {
            String out = capture(List.of("ffprobe", "-v", "error", "-select_streams", "a:0",
                    "-show_entries", "stream=codec_type", "-of", "csv=p=0", file.toString()));
            return out.trim().startsWith("audio");
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static double probeDuration(Path file) {
        String out = capture(List.of("ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", file.toString()));
        try {
            return Double.parseDouble(out.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Could not read the duration of " + file.getFileName());
        }
    }

    /**
     * Replaces any model-generated audio with the supplied narration/dialogue WAV.
     * The video stream is copied and the external audio is padded/trimmed to the
     * exact visual duration. Story production uses this so H3's native speech is
     * never allowed to shorten a scene or mispronounce an Indian language.
     */
    public static Path muxExternalAudio(Path video, Path audio, Path output, double durationSeconds) {
        if (video == null || audio == null) throw new IllegalArgumentException("Video and audio are required.");
        double duration = Math.max(0.1, durationSeconds);
        run(List.of("ffmpeg", "-nostdin", "-y",
                "-i", video.toAbsolutePath().toString(),
                "-i", audio.toAbsolutePath().toString(),
                "-filter_complex", "[1:a]apad=whole_dur=" + fmt(duration) + "[a]",
                "-map", "0:v:0", "-map", "[a]",
                "-t", fmt(duration),
                "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
                "-movflags", "+faststart", output.toAbsolutePath().toString()));
        return output;
    }

    /** Last frame of a clip as a PNG - the start image for the next scene in CHAIN mode. */
    public static Path extractLastFrame(Path clip, Path outPng) {
        run(List.of("ffmpeg", "-nostdin", "-y", "-sseof", "-0.15", "-i", clip.toString(),
                "-frames:v", "1", "-update", "1", outPng.toString()));
        try {
            if (!Files.isRegularFile(outPng) || Files.size(outPng) == 0) {
                // very short clip: fall back to the first frame
                run(List.of("ffmpeg", "-nostdin", "-y", "-i", clip.toString(), "-frames:v", "1", "-update", "1",
                        outPng.toString()));
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return outPng;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String capture(List<String> args) {
        try {
            Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException(args.get(0) + " timed out");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException(args.get(0) + " failed: " + out);
            }
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Could not run " + args.get(0), e);
        }
    }

    private static void run(List<String> args) {
        try {
            Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IllegalStateException("ffmpeg timed out while merging clips");
            }
            if (p.exitValue() != 0) {
                String tail = out.length() > 1500 ? out.substring(out.length() - 1500) : out;
                throw new IllegalStateException("ffmpeg failed (exit " + p.exitValue() + "): " + tail);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Could not run ffmpeg", e);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort temp cleanup
        }
    }
}
