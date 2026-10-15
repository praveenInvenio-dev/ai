package com.aistorystudio.qa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loudness normalisation (EBU R128 / ITU-R BS.1770 via FFmpeg's loudnorm), so every video plays at the same volume whichever voice
 * engine or music made it. Two passes: measure the whole file, then apply a LINEAR gain (no pumping or squashing of the voice).
 * Video is copied untouched, only the audio is re-encoded.
 */
public final class Loudness {

    private Loudness() { }

    /** @param lufs integrated loudness goal, @param truePeak ceiling in dBTP, @param lra allowed loudness range */
    public record Target(String id, String label, double lufs, double truePeak, double lra) { }

    public static final List<Target> TARGETS = List.of(
            new Target("social", "Social / YouTube (-14 LUFS)", -14, -1.5, 11),
            new Target("podcast", "Podcast / lessons (-16 LUFS)", -16, -1.5, 11),
            new Target("broadcast", "Broadcast, EBU R128 (-23 LUFS)", -23, -1.0, 15));

    public static Target of(String id) {
        if (id == null) return null;
        for (Target t : TARGETS) if (t.id().equalsIgnoreCase(id.trim())) return t;
        return null;
    }

    private static final Pattern JSON = Pattern.compile("\\{[^{}]*\"input_i\"[^{}]*\\}", Pattern.DOTALL);
    private static final Pattern INTEGRATED = Pattern.compile("\\bI:\\s*(-?[0-9.]+|-inf)\\s*LUFS");

    /** Integrated loudness of the audio in LUFS, or null when there is no audio / it is silent. */
    public static Double measure(String ffmpeg, Path file) throws IOException, InterruptedException {
        return measure(ffmpeg, file, 0);
    }

    /** @param capSeconds only the first N seconds are measured (0 = the whole file) */
    public static Double measure(String ffmpeg, Path file, int capSeconds) throws IOException, InterruptedException {
        if (!hasAudio(file)) return null;
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-nostdin"));
        if (capSeconds > 0) cmd.addAll(List.of("-t", String.valueOf(capSeconds)));
        cmd.addAll(List.of("-i", file.toString(), "-vn", "-af", "ebur128=peak=true", "-f", "null", "-"));
        String log = run(cmd, 10);
        Matcher m = INTEGRATED.matcher(log);
        Double v = null;
        while (m.find()) v = m.group(1).equals("-inf") ? null : Double.parseDouble(m.group(1));   // the last one is the summary
        return v;
    }

    /**
     * Normalises {@code in} to {@code target} into {@code out}. Returns the loudness measured BEFORE (LUFS), or null when nothing
     * was done (no audio, or silence), in which case {@code out} is not written.
     */
    public static Double normalize(String ffmpeg, Path in, Path out, Target target) throws IOException, InterruptedException {
        if (!hasAudio(in)) return null;
        String pass1 = run(List.of(ffmpeg, "-hide_banner", "-nostdin", "-i", in.toString(), "-vn", "-af",
                "loudnorm=I=" + fmt(target.lufs()) + ":TP=" + fmt(target.truePeak()) + ":LRA=" + fmt(target.lra()) + ":print_format=json", "-f", "null", "-"), 10);
        Matcher m = JSON.matcher(pass1);
        String block = null;
        while (m.find()) block = m.group();
        if (block == null) return null;
        double inputI = num(block, "input_i"), inputTp = num(block, "input_tp"), lra = num(block, "input_lra"),
                thresh = num(block, "input_thresh"), offset = num(block, "target_offset");
        if (Double.isNaN(inputI) || Double.isInfinite(inputI) || inputI < -70) return null;     // silence: nothing to normalise
        String af = "loudnorm=I=" + fmt(target.lufs()) + ":TP=" + fmt(target.truePeak()) + ":LRA=" + fmt(target.lra())
                + ":measured_I=" + fmt(inputI) + ":measured_TP=" + fmt(inputTp) + ":measured_LRA=" + fmt(lra)
                + ":measured_thresh=" + fmt(thresh) + ":offset=" + fmt(offset) + ":linear=true";
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-y", "-hide_banner", "-nostdin", "-i", in.toString(), "-map", "0:v?", "-map", "0:a",
                "-c:v", "copy", "-af", af, "-ar", "48000"));
        String name = out.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".webm")) cmd.addAll(List.of("-c:a", "libopus", "-b:a", "160k"));      // WebM cannot hold AAC
        else cmd.addAll(List.of("-c:a", "aac", "-b:a", "192k"));
        if (name.endsWith(".mp4") || name.endsWith(".mov")) cmd.addAll(List.of("-movflags", "+faststart"));
        cmd.add(out.toString());
        run(cmd, 30);
        if (!Files.isRegularFile(out) || Files.size(out) < 500) throw new IOException("loudness pass produced no file");
        return inputI;
    }

    private static boolean hasAudio(Path file) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=codec_type", "-of", "csv=p=0", file.toString())
                .redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(30, TimeUnit.SECONDS);
        return s.contains("audio");
    }

    private static double num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?(-?[0-9.]+|-inf|inf)\"?").matcher(json);
        if (!m.find()) return Double.NaN;
        String v = m.group(1);
        return v.equals("-inf") ? Double.NEGATIVE_INFINITY : v.equals("inf") ? Double.POSITIVE_INFINITY : Double.parseDouble(v);
    }

    private static String fmt(double d) { return String.format(Locale.ROOT, "%.2f", d); }

    private static String run(List<String> cmd, int minutes) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(minutes, TimeUnit.MINUTES)) { p.destroyForcibly(); throw new IOException("audio analysis timed out"); }
        if (p.exitValue() != 0) throw new IOException("audio tool failed: " + log.substring(Math.max(0, log.length() - 300)).replaceAll("\\s+", " "));
        return log;
    }
}
