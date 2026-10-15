package com.aistorystudio.qa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-render quality check (idea borrowed from OpenMontage's "self-review before presenting": never hand over a file that was
 * not looked at). One FFmpeg analysis pass over the finished video reports what a viewer would notice at once:
 * <ul>
 *   <li>long silent gaps (a failed or runaway voice line, a missing audio track);</li>
 *   <li>black picture in the middle of the video (a scene that did not render);</li>
 *   <li>clipping (peaks at 0 dB) and a mix that is far too quiet;</li>
 *   <li>a length that is far from what was planned;</li>
 *   <li>no audio at all.</li>
 * </ul>
 * Findings are plain sentences with a time, so they can be shown to the user as they are. The check never throws: if the
 * analysis cannot run it returns a single "could not check" note.
 */
public final class RenderQa {

    private RenderQa() { }

    public enum Severity { WARN, ERROR }

    public record Finding(Severity severity, String code, String message, double atSeconds) { }

    /**
     * @param minSilenceSec  a silent stretch at least this long is reported (set it above the longest pause you planned)
     * @param expectedSec    planned length, or 0 when unknown
     * @param expectAudio    false for deliberately silent videos
     */
    public record Options(double minSilenceSec, double expectedSec, boolean expectAudio) {
        public static Options spoken(double expectedSec) { return new Options(4.0, expectedSec, true); }
    }

    private static final Pattern SIL_START = Pattern.compile("silence_start:\\s*(-?[0-9.]+)");
    private static final Pattern SIL_END = Pattern.compile("silence_end:\\s*([0-9.]+)\\s*\\|\\s*silence_duration:\\s*([0-9.]+)");
    private static final Pattern BLACK = Pattern.compile("black_start:([0-9.]+)\\s+black_end:([0-9.]+)\\s+black_duration:([0-9.]+)");
    private static final Pattern MAXV = Pattern.compile("max_volume:\\s*(-?[0-9.]+|-inf)\\s*dB");
    private static final Pattern MEANV = Pattern.compile("mean_volume:\\s*(-?[0-9.]+|-inf)\\s*dB");
    private static final Pattern DUR = Pattern.compile("Duration:\\s*(\\d+):(\\d+):([0-9.]+)");

    public static List<Finding> review(String ffmpeg, Path video, Options opt) {
        List<Finding> out = new ArrayList<>();
        try {
            boolean hasAudio = hasAudio(video);
            List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-nostdin", "-i", video.toString(),
                    "-vf", "blackdetect=d=0.6:pix_th=0.01:pic_th=0.99"));
            if (hasAudio) cmd.addAll(List.of("-af", "silencedetect=noise=-45dB:d=" + fmt(Math.max(0.5, opt.minSilenceSec())) + ",volumedetect"));
            cmd.addAll(List.of("-f", "null", "-"));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(10, TimeUnit.MINUTES)) { p.destroyForcibly(); return List.of(note("Quality check timed out.")); }

            double duration = duration(log);
            if (opt.expectedSec() > 5 && duration > 0 && Math.abs(duration - opt.expectedSec()) / opt.expectedSec() > 0.15) {
                out.add(new Finding(Severity.WARN, "length", String.format(Locale.ROOT,
                        "The video is %.0f s long but about %.0f s was planned.", duration, opt.expectedSec()), 0));
            }
            if (!hasAudio) {
                if (opt.expectAudio()) out.add(new Finding(Severity.ERROR, "no-audio", "The video has no audio track.", 0));
            } else {
                // silent gaps
                Double pendingStart = null;
                for (String line : log.split("\\R")) {
                    Matcher s = SIL_START.matcher(line);
                    if (s.find()) pendingStart = Math.max(0, Double.parseDouble(s.group(1)));
                    Matcher e = SIL_END.matcher(line);
                    if (e.find()) {
                        double len = Double.parseDouble(e.group(2)), end = Double.parseDouble(e.group(1));
                        out.add(silence(Math.max(0, end - len), len));
                        pendingStart = null;
                    }
                }
                if (pendingStart != null && duration > pendingStart + opt.minSilenceSec())   // silence running to the very end
                    out.add(silence(pendingStart, duration - pendingStart));
                Double max = lastDb(MAXV, log), mean = lastDb(MEANV, log);
                if (max != null && max >= -0.1) out.add(new Finding(Severity.WARN, "clipping", "The audio peaks at 0 dB, so it may be distorted (clipping).", 0));
                if (mean != null && mean < -38) out.add(new Finding(Severity.WARN, "quiet", String.format(Locale.ROOT,
                        "The audio is very quiet (average %.0f dB). Raise the voice or music level.", mean), 0));
            }
            // black frames (the first / last half second may fade from or to black on purpose)
            Matcher b = BLACK.matcher(log);
            while (b.find()) {
                double st = Double.parseDouble(b.group(1)), en = Double.parseDouble(b.group(2)), len = Double.parseDouble(b.group(3));
                boolean atEdge = st < 0.6 || (duration > 0 && en > duration - 0.6);
                if (atEdge && len < 1.5) continue;
                out.add(new Finding(len >= 3 ? Severity.ERROR : Severity.WARN, "black", String.format(Locale.ROOT,
                        "Black picture for %.1f s at %s - a scene may not have rendered.", len, clock(st)), st));
            }
        } catch (IOException e) {
            return List.of(note("Quality check could not run (" + e.getMessage() + ")."));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of(note("Quality check was interrupted."));
        } catch (RuntimeException e) {
            return List.of(note("Quality check could not read the video (" + e.getMessage() + ")."));
        }
        out.sort((x, y) -> Double.compare(x.atSeconds(), y.atSeconds()));
        return out;
    }

    /** One line per finding, errors first, at most {@code max} lines (a summary line says how many were left out). */
    public static List<String> summarise(List<Finding> findings, int max) {
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort((a, b) -> a.severity() == b.severity() ? Double.compare(a.atSeconds(), b.atSeconds()) : a.severity() == Severity.ERROR ? -1 : 1);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(max, sorted.size()); i++) lines.add(sorted.get(i).message());
        if (sorted.size() > max) lines.add("... and " + (sorted.size() - max) + " more quality notes.");
        return lines;
    }

    private static Finding silence(double start, double len) {
        return new Finding(len >= 8 ? Severity.ERROR : Severity.WARN, "silence", String.format(Locale.ROOT,
                "Silent for %.0f s from %s - a voice line may have failed or run on.", len, clock(start)), start);
    }

    private static Finding note(String m) { return new Finding(Severity.WARN, "check-skipped", m, 0); }

    private static boolean hasAudio(Path video) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=codec_type",
                "-of", "csv=p=0", video.toString()).redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(30, TimeUnit.SECONDS);
        return s.contains("audio");
    }

    private static double duration(String log) {
        Matcher m = DUR.matcher(log);
        return m.find() ? Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Double.parseDouble(m.group(3)) : 0;
    }

    private static Double lastDb(Pattern p, String log) {
        Matcher m = p.matcher(log);
        Double v = null;
        while (m.find()) v = m.group(1).equals("-inf") ? -99.0 : Double.parseDouble(m.group(1));
        return v;
    }

    private static String clock(double sec) { int s = (int) Math.round(sec); return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60); }

    private static String fmt(double d) { return String.format(Locale.ROOT, "%.2f", d); }
}
