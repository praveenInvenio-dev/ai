package com.aistorystudio.reference;

import com.aistorystudio.qa.Loudness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Make mine feel like this one": measures a reference video (cut pace, loudness, silence, colour, shape) and turns the numbers into
 * suggested settings for the editor and for lessons. Everything is measured with FFmpeg, nothing is guessed from the file name or
 * uploaded to anywhere. Only the first {@link #CAP_SECONDS} seconds are analysed.
 */
public final class ReferenceAnalyzer {

    private ReferenceAnalyzer() { }

    public static final int CAP_SECONDS = 150;

    public record Colour(String hex, int percent) { }

    public record Profile(double durationSec, boolean truncated, int width, int height, double fps, String aspect, boolean hasAudio,
                          Double loudnessLufs, double silencePercent, int cuts, double cutsPerMinute, double avgShotSec,
                          int beats, double beatsPerMinute, double activityPercent,
                          double brightness, double contrast, double saturation, double warmth, List<Colour> palette) { }

    /** Settings to copy. Null fields mean "no change suggested". */
    public record Suggestions(String look, String intensity, String aspectRatio, String loudness, Integer exportShortSide, Integer exportFps,
                              String lessonStyle, String lessonMotion, List<String> notes) { }

    // ------------------------------------------------------------------------------------------------------------ analysis

    public static Profile analyse(String ffmpeg, String ffprobe, Path video) throws IOException, InterruptedException {
        Map<String, String> probe = probe(ffprobe, video);
        double duration = parse(probe.get("duration"), 0);
        int w = (int) parse(probe.get("width"), 0), h = (int) parse(probe.get("height"), 0);
        if (duration <= 0 || w <= 0 || h <= 0) throw new IOException("This does not look like a video file.");
        double fps = fraction(probe.get("r_frame_rate"));
        boolean audio = probe.containsKey("audio");
        double span = Math.min(duration, CAP_SECONDS);
        String cap = String.valueOf(CAP_SECONDS);
        int frames = 16, side = 64;

        // the passes are independent, so they run side by side (the slowest one decides the time, not the sum)
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5);
        try {
            var cutsF = pool.submit(() -> run(List.of(ffmpeg, "-hide_banner", "-nostdin", "-t", cap, "-i", video.toString(),
                    "-vf", "scale=320:-2,select='gt(scene,0.30)',showinfo", "-an", "-f", "null", "-"), 10));
            var beatsF = pool.submit(() -> runBytes(List.of(ffmpeg, "-hide_banner", "-nostdin", "-v", "error", "-t", cap, "-i", video.toString(),
                    "-vf", "fps=3,scale=64:64:flags=area,format=gray", "-f", "rawvideo", "-"), 10));
            var colourF = pool.submit(() -> runBytes(List.of(ffmpeg, "-hide_banner", "-nostdin", "-v", "error", "-t", cap, "-i", video.toString(),
                    "-vf", String.format(Locale.ROOT, "fps=%.5f,scale=%d:%d:flags=area,format=rgb24", frames / span, side, side),
                    "-frames:v", String.valueOf(frames), "-f", "rawvideo", "-"), 10));
            var lufsF = pool.submit(() -> audio ? Loudness.measure(ffmpeg, video, CAP_SECONDS) : null);
            var silF = pool.submit(() -> audio ? run(List.of(ffmpeg, "-hide_banner", "-nostdin", "-t", cap, "-i", video.toString(), "-vn",
                    "-af", "silencedetect=noise=-40dB:d=0.4", "-f", "null", "-"), 10) : "");

            int cuts = 0;
            Matcher m = Pattern.compile("pts_time:([0-9.]+)").matcher(get(cutsF));
            while (m.find()) if (Double.parseDouble(m.group(1)) > 0.4) cuts++;       // the very first frame is not a cut
            double perMinute = cuts / span * 60.0;
            double avgShot = span / (cuts + 1);
            // "something new appears": spikes in how many pixels changed, against the recent baseline. Catches elements appearing on a
            // calm canvas (explainers, reels), which soft scene scores cannot see; steady motion (live footage) is not a beat.
            double[] pace = beats(get(beatsF), 64 * 64, 3);
            Double lufs = get(lufsF);
            double silencePct = 0;
            if (audio) {
                double total = 0, pendingStart = -1;
                Matcher sm = Pattern.compile("silence_start:\\s*(-?[0-9.]+)|silence_duration:\\s*([0-9.]+)").matcher(get(silF));
                while (sm.find()) {
                    if (sm.group(1) != null) pendingStart = Double.parseDouble(sm.group(1));
                    else { total += Double.parseDouble(sm.group(2)); pendingStart = -1; }
                }
                if (pendingStart >= 0 && span > pendingStart) total += span - pendingStart;      // silence running to the end
                silencePct = Math.min(100, total / span * 100);
            }
            byte[] raw = get(colourF);
            double[] stats = colourStats(raw, side * side * 3);
            Colour[] palette = palette(raw, side * side * 3).toArray(new Colour[0]);

            return new Profile(round(duration, 1), duration > CAP_SECONDS, w, h, round(fps, 1), nearestAspect(w, h), audio,
                    lufs == null ? null : round(lufs, 1), round(silencePct, 1), cuts, round(perMinute, 1), round(avgShot, 1),
                    (int) pace[0], round(pace[0] / span * 60.0, 1), round(pace[1], 0),
                    round(stats[0], 0), round(stats[1], 0), round(stats[2], 0), round(stats[3], 2), List.of(palette));
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> T get(java.util.concurrent.Future<T> f) throws IOException, InterruptedException {
        try {
            return f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException(e.getCause() == null ? e.getMessage() : e.getCause().getMessage(), e.getCause());
        }
    }

    /** {number of beats, percent of the time something is changing} from grey frames sampled at {@code fps}. */
    static double[] beats(byte[] gray, int frameBytes, int fps) {
        int frames = gray.length / frameBytes;
        if (frames < 3) return new double[]{0, 0};
        double[] change = new double[frames - 1];
        for (int i = 1; i < frames; i++) {
            int changed = 0, a = (i - 1) * frameBytes, b = i * frameBytes;
            for (int k = 0; k < frameBytes; k++) if (Math.abs((gray[a + k] & 255) - (gray[b + k] & 255)) > 20) changed++;
            change[i - 1] = changed / (double) frameBytes;
        }
        int beats = 0, active = 0;
        double last = -9;
        for (int i = 0; i < change.length; i++) {
            if (change[i] > 0.005) active++;
            double[] window = java.util.Arrays.copyOfRange(change, Math.max(0, i - 6), i);
            java.util.Arrays.sort(window);
            double base = window.length == 0 ? 0 : window[window.length / 2];
            double t = (i + 1) / (double) fps;
            if (t > 0.4 && change[i] > 0.005 && change[i] > 2.0 * Math.max(base, 0.0005) && t - last >= 0.8) { beats++; last = t; }
        }
        return new double[]{beats, active * 100.0 / change.length};
    }

    /** brightness, contrast, colourfulness (chroma; all 0-100) and warmth (-1 cool .. +1 warm) over all sampled pixels. */
    static double[] colourStats(byte[] raw, int frameBytes) {
        int n = raw.length / 3;
        if (n == 0) return new double[]{0, 0, 0, 0};
        double sumL = 0, sumL2 = 0, sumC = 0, sumR = 0, sumB = 0;
        for (int i = 0; i + 2 < raw.length; i += 3) {
            int r = raw[i] & 255, g = raw[i + 1] & 255, b = raw[i + 2] & 255;
            double l = 0.299 * r + 0.587 * g + 0.114 * b;
            sumL += l;
            sumL2 += l * l;
            sumR += r;
            sumB += b;
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            sumC += (max - min);          // chroma: how far the colour is from grey, in absolute terms (a near-black teal is NOT colourful)
        }
        double mean = sumL / n, std = Math.sqrt(Math.max(0, sumL2 / n - mean * mean));
        double warmth = (sumR - sumB) / (sumR + sumB + 1.0);
        return new double[]{mean / 255 * 100, Math.min(100, std / 70.0 * 100), sumC / n / 255 * 100, warmth};
    }

    /** The five most common colours (4 bits per channel buckets, near duplicates merged). */
    static List<Colour> palette(byte[] raw, int frameBytes) {
        Map<Integer, long[]> bins = new HashMap<>();   // key -> count, sumR, sumG, sumB
        int total = raw.length / 3;
        for (int i = 0; i + 2 < raw.length; i += 3) {
            int r = raw[i] & 255, g = raw[i + 1] & 255, b = raw[i + 2] & 255;
            int key = (r >> 4) << 8 | (g >> 4) << 4 | (b >> 4);
            long[] a = bins.computeIfAbsent(key, k -> new long[4]);
            a[0]++; a[1] += r; a[2] += g; a[3] += b;
        }
        List<long[]> sorted = new ArrayList<>(bins.values());
        sorted.sort((x, y) -> Long.compare(y[0], x[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] a : sorted) {
            double[] c = {a[1] / (double) a[0], a[2] / (double) a[0], a[3] / (double) a[0]};
            boolean absorbed = false;
            for (long[] m : merged) {
                double[] mc = {m[1] / (double) m[0], m[2] / (double) m[0], m[3] / (double) m[0]};
                if (Math.hypot(Math.hypot(c[0] - mc[0], c[1] - mc[1]), c[2] - mc[2]) < 44) { m[0] += a[0]; m[1] += a[1]; m[2] += a[2]; m[3] += a[3]; absorbed = true; break; }
            }
            if (!absorbed) merged.add(a.clone());
        }
        merged.sort((x, y) -> Long.compare(y[0], x[0]));
        List<Colour> out = new ArrayList<>();
        for (int i = 0; i < Math.min(5, merged.size()); i++) {
            long[] a = merged.get(i);
            out.add(new Colour(String.format(Locale.ROOT, "#%02X%02X%02X", a[1] / a[0], a[2] / a[0], a[3] / a[0]), (int) Math.round(a[0] * 100.0 / Math.max(1, total))));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------ suggestions

    public static Suggestions suggest(Profile p) {
        List<String> notes = new ArrayList<>();
        boolean constantMotion = p.activityPercent() > 90;          // live footage: something always moves, so "new things appearing" says nothing
        double pace = constantMotion ? p.cutsPerMinute() : Math.max(p.cutsPerMinute(), p.beatsPerMinute());
        if (constantMotion) notes.add(String.format(Locale.ROOT, "Moving all the time (live footage), so the pace is taken from the hard cuts: %d in %.0f s.", p.cuts(), Math.min(p.durationSec(), CAP_SECONDS)));
        else notes.add(String.format(Locale.ROOT, "Something new appears about every %.1f s (%.0f per minute); %d hard cuts.", pace > 0 ? 60.0 / pace : p.durationSec(), pace, p.cuts()));
        String intensity = pace < 8 ? "SUBTLE" : pace < 16 ? "BALANCED" : pace < 30 ? "DYNAMIC" : "AGGRESSIVE";
        String motion = pace < 8 ? "LOW" : pace < 18 ? "MID" : "HIGH";

        String look = null;                       // dark footage first, then a clear tint, then colourfulness, then brightness
        if (p.saturation() < 5) look = "bw";
        else if (p.brightness() < 30) look = p.contrast() > 55 ? "drama" : "moody";
        else if (p.warmth() > 0.14) look = p.saturation() > 30 && p.brightness() > 45 ? "golden_hour" : "warm";
        else if (p.warmth() < -0.14) look = "cool";
        else if (p.saturation() > 42) look = "vivid";
        else if (p.brightness() > 65 && p.saturation() < 28) look = "soft_pastel";
        else if (p.brightness() > 62) look = "fresh";
        else if (p.brightness() < 40) look = "moody";
        notes.add(String.format(Locale.ROOT, "Colour: brightness %.0f, contrast %.0f, colourfulness %.0f, %s.", p.brightness(), p.contrast(), p.saturation(),
                p.warmth() > 0.08 ? "warm" : p.warmth() < -0.08 ? "cool" : "neutral"));

        String loudness = null;
        if (p.loudnessLufs() != null) {
            double best = 1e9;
            String nearest = null;
            for (Loudness.Target t : Loudness.TARGETS) {
                double d = Math.abs(p.loudnessLufs() - t.lufs());
                if (d < best) { best = d; nearest = t.id(); }
            }
            if (best <= 6) {
                loudness = nearest;
                notes.add(String.format(Locale.ROOT, "Loudness %.1f LUFS, closest standard: %s.", p.loudnessLufs(), loudness));
            } else notes.add(String.format(Locale.ROOT, "Loudness %.1f LUFS is far from every standard (-14 / -16 / -23), so no target is suggested.", p.loudnessLufs()));
            if (p.silencePercent() > 15) notes.add(String.format(Locale.ROOT, "%.0f %% of it is silence - a quiet, spacious pace.", p.silencePercent()));
        } else notes.add("It has no audio track.");

        int[] sides = {480, 720, 1080, 1440, 2160};
        int shortSide = Math.min(p.width(), p.height()), side = sides[0];
        for (int s : sides) if (Math.abs(s - shortSide) < Math.abs(side - shortSide)) side = s;
        int[] rates = {24, 25, 30, 50, 60};
        int fps = rates[0];
        for (int r : rates) if (Math.abs(r - p.fps()) < Math.abs(fps - p.fps())) fps = r;

        String style = lessonStyle(p);
        notes.add("Closest lesson look: " + style + ".");
        return new Suggestions(look, intensity, p.aspect(), loudness, side, fps, style, motion, notes);
    }

    /** One of the lesson styles, from the brightness and the colours that carry the most saturation. */
    static String lessonStyle(Profile p) {
        double hue = dominantHue(p);
        boolean dark = p.brightness() < 42;
        if (dark) {
            if (p.saturation() < 6) return "reel";
            if (hue >= 212 && hue <= 250 && p.brightness() < 30) return "blueprint";
            if (hue >= 160 && hue < 212) return "reference";
            if (hue >= 270 || hue < 20 || p.saturation() > 40) return "neon";
            return "reference";
        }
        if (p.brightness() > 60) return p.saturation() > 30 && p.warmth() > 0.05 ? "sketchnote" : "storyboard";
        return "reference";
    }

    private static double dominantHue(Profile p) {
        double bestScore = -1, hue = 180;
        for (Colour c : p.palette()) {
            int r = Integer.parseInt(c.hex().substring(1, 3), 16), g = Integer.parseInt(c.hex().substring(3, 5), 16), b = Integer.parseInt(c.hex().substring(5, 7), 16);
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            double chroma = max - min;
            double score = c.percent() * chroma / 255.0;       // common AND colourful
            if (chroma > 12 && score > bestScore) {
                bestScore = score;
                double hh;
                if (max == r) hh = ((g - b) / chroma) % 6; else if (max == g) hh = (b - r) / chroma + 2; else hh = (r - g) / chroma + 4;
                hue = (hh * 60 + 360) % 360;
            }
        }
        return hue;
    }

    // ------------------------------------------------------------------------------------------------------------ helpers

    static String nearestAspect(int w, int h) {
        double r = w / (double) h;
        String[] names = {"VERTICAL_9_16", "LANDSCAPE_16_9", "SQUARE_1_1", "PORTRAIT_4_5"};
        double[] ratios = {9 / 16.0, 16 / 9.0, 1.0, 0.8};
        int best = 0;
        for (int i = 1; i < names.length; i++) if (Math.abs(Math.log(r / ratios[i])) < Math.abs(Math.log(r / ratios[best]))) best = i;
        return names[best];
    }

    private static Map<String, String> probe(String ffprobe, Path video) throws IOException, InterruptedException {
        Map<String, String> out = new HashMap<>();
        String v = run(List.of(ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,r_frame_rate:format=duration", "-of", "default=nw=1", video.toString()), 2);
        for (String line : v.split("\\R")) {
            int i = line.indexOf('=');
            if (i > 0) out.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        String a = run(List.of(ffprobe, "-v", "error", "-select_streams", "a", "-show_entries", "stream=codec_type", "-of", "csv=p=0", video.toString()), 2);
        if (a.contains("audio")) out.put("audio", "1");
        return out;
    }

    private static double parse(String s, double dflt) {
        try { return s == null ? dflt : Double.parseDouble(s); } catch (NumberFormatException e) { return dflt; }
    }

    private static double fraction(String s) {
        if (s == null) return 0;
        String[] p = s.split("/");
        try { return p.length == 2 ? Double.parseDouble(p[0]) / Math.max(1, Double.parseDouble(p[1])) : Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    private static String run(List<String> cmd, int minutes) throws IOException, InterruptedException {
        return new String(runBytes(cmd, minutes, true), StandardCharsets.UTF_8);
    }

    private static byte[] runBytes(List<String> cmd, int minutes) throws IOException, InterruptedException {
        return runBytes(cmd, minutes, false);
    }

    private static byte[] runBytes(List<String> cmd, int minutes, boolean mergeErr) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (mergeErr) pb.redirectErrorStream(true); else pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) { in.transferTo(bos); }
        if (!p.waitFor(minutes, TimeUnit.MINUTES)) { p.destroyForcibly(); throw new IOException("analysis timed out"); }
        return bos.toByteArray();
    }
}
