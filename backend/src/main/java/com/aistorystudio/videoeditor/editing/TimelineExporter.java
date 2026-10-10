package com.aistorystudio.videoeditor.editing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exports the timeline to professional editors: CMX3600 EDL (Premiere, DaVinci Resolve, Avid, ...) and
 * FCPXML 1.9 (Final Cut Pro, DaVinci Resolve, Premiere via import). Pure text generation, no I/O.
 *
 * What travels: the cut order, source in/out of every shot, clip names, frame-exact timing. Speed changes travel in the
 * EDL (M2 lines); the FCPXML carries the correct source range but not the retime (a warning says so). Colour looks,
 * effects, captions and titles are NOT part of an interchange file - they are baked into the MP4 render only.
 * Media is referenced by file name: relink to your original files if the NLE asks.
 */
public final class TimelineExporter {

    private TimelineExporter() { }

    /** One shot. start/end are source seconds; speed 1 = normal. */
    public record Row(String clipName, String clipKey, double clipDuration, double start, double end, double speed,
                      boolean hasAudio, boolean muted, double fps, int width, int height) {
        double outDuration() { return Math.max(0.001, (end - start) / (speed > 0 ? speed : 1.0)); }
    }

    public record Result(String text, List<String> warnings) { }

    // ------------------------------------------------------------------ frame rates

    /** Standard rates as exact rationals num/den. */
    public record Rate(int num, int den) {
        double fps() { return num / (double) den; }
        int nominal() { return (int) Math.round(fps()); }
    }

    public static Rate nearestRate(double fps) {
        double[][] std = {{24000, 1001}, {24, 1}, {25, 1}, {30000, 1001}, {30, 1}, {50, 1}, {60000, 1001}, {60, 1}};
        double best = Double.MAX_VALUE;
        Rate pick = new Rate(30, 1);
        for (double[] s : std) {
            double d = Math.abs(fps - s[0] / s[1]);
            if (d < best) { best = d; pick = new Rate((int) s[0], (int) s[1]); }
        }
        return fps <= 0 ? new Rate(30, 1) : pick;
    }

    static long frames(double seconds, Rate r) {
        return Math.max(0, Math.round(seconds * r.fps()));
    }

    static String tc(long frames, Rate r) {
        int fps = r.nominal();
        long f = frames % fps, s = (frames / fps) % 60, m = (frames / (fps * 60L)) % 60, h = frames / (fps * 3600L);
        return String.format(Locale.ROOT, "%02d:%02d:%02d:%02d", h, m, s, f);
    }

    // ------------------------------------------------------------------ EDL

    public static Result edl(String title, List<Row> rows) {
        List<String> warnings = new ArrayList<>();
        Rate rate = rows.isEmpty() ? new Rate(30, 1) : nearestRate(rows.get(0).fps());
        StringBuilder sb = new StringBuilder();
        sb.append("TITLE: ").append(sanitize(title)).append('\n').append("FCM: NON-DROP FRAME\n\n");
        long rec = 0;
        int n = 1;
        for (Row r : rows) {
            long in = frames(r.start(), rate), out = frames(r.start() + (r.end() - r.start()), rate);
            long recLen = frames(r.outDuration(), rate);
            if (out <= in) out = in + 1;
            if (recLen <= 0) recLen = 1;
            String reel = "AX";
            String line = String.format(Locale.ROOT, "%03d  %-8s %-5s C        %s %s %s %s",
                    n++, reel, "V", tc(in, rate), tc(out, rate), tc(rec, rate), tc(rec + recLen, rate));
            sb.append(line).append('\n');
            if (Math.abs(r.speed() - 1.0) > 0.01) {
                sb.append(String.format(Locale.ROOT, "M2   %-8s %05.1f                %s\n", reel, r.speed() * rate.fps(), tc(in, rate)));
            }
            sb.append("* FROM CLIP NAME: ").append(sanitize(r.clipName())).append('\n');
            if (r.hasAudio() && !r.muted()) {
                sb.append(String.format(Locale.ROOT, "%03d  %-8s %-5s C        %s %s %s %s\n",
                        n++, reel, "A", tc(in, rate), tc(out, rate), tc(rec, rate), tc(rec + recLen, rate)));
                sb.append("* FROM CLIP NAME: ").append(sanitize(r.clipName())).append('\n');
            }
            sb.append('\n');
            rec += recLen;
        }
        long mixed = rows.stream().map(Row::fps).distinct().count();
        if (mixed > 1) warnings.add("The clips have different frame rates; the EDL uses " + rate.fps() + " fps for all of them.");
        return new Result(sb.toString(), warnings);
    }

    // ------------------------------------------------------------------ FCPXML

    public static Result fcpxml(String title, List<Row> rows, int canvasWidth, int canvasHeight) {
        List<String> warnings = new ArrayList<>();
        Rate seq = rows.isEmpty() ? new Rate(30, 1) : nearestRate(rows.get(0).fps());
        Map<String, String> assetIds = new LinkedHashMap<>();
        Map<String, Row> assetRows = new LinkedHashMap<>();
        for (Row r : rows) {
            if (!assetIds.containsKey(r.clipKey())) { assetIds.put(r.clipKey(), "a" + (assetIds.size() + 1)); assetRows.put(r.clipKey(), r); }
        }
        boolean retimed = rows.stream().anyMatch(r -> Math.abs(r.speed() - 1.0) > 0.01);
        if (retimed) warnings.add("Speed changes are not part of the FCPXML: shots keep the correct source range at normal speed. Re-apply the speed in your editor (the EDL export does carry it).");
        if (rows.stream().map(Row::fps).distinct().count() > 1) warnings.add("The clips have different frame rates; the sequence uses " + seq.fps() + " fps.");

        long total = 0;
        for (Row r : rows) total += Math.max(1, frames(r.end() - r.start(), seq));

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE fcpxml>\n<fcpxml version=\"1.9\">\n  <resources>\n");
        sb.append("    <format id=\"r1\" name=\"Canvas\" frameDuration=\"").append(rate(seq.den(), seq.num()))
                .append("\" width=\"").append(canvasWidth).append("\" height=\"").append(canvasHeight).append("\"/>\n");
        int fmt = 2;
        Map<String, String> assetFormat = new LinkedHashMap<>();
        for (var e : assetRows.entrySet()) {
            Row r = e.getValue();
            Rate cr = nearestRate(r.fps());
            String fid = "r" + fmt++;
            assetFormat.put(e.getKey(), fid);
            sb.append("    <format id=\"").append(fid).append("\" frameDuration=\"").append(rate(cr.den(), cr.num()))
                    .append("\" width=\"").append(Math.max(2, r.width())).append("\" height=\"").append(Math.max(2, r.height())).append("\"/>\n");
        }
        for (var e : assetRows.entrySet()) {
            Row r = e.getValue();
            Rate cr = nearestRate(r.fps());
            sb.append("    <asset id=\"").append(assetIds.get(e.getKey())).append("\" name=\"").append(xml(r.clipName()))
                    .append("\" start=\"0s\" duration=\"").append(time(frames(Math.max(r.clipDuration(), r.end()), cr), cr))
                    .append("\" hasVideo=\"1\" format=\"").append(assetFormat.get(e.getKey())).append("\"")
                    .append(r.hasAudio() ? " hasAudio=\"1\" audioSources=\"1\" audioChannels=\"2\" audioRate=\"48000\"" : "").append(">\n")
                    .append("      <media-rep kind=\"original-media\" src=\"file:///").append(xml(urlName(r.clipName()))).append("\"/>\n")
                    .append("    </asset>\n");
        }
        sb.append("  </resources>\n  <library>\n    <event name=\"").append(xml(title)).append("\">\n      <project name=\"").append(xml(title)).append("\">\n");
        sb.append("        <sequence format=\"r1\" duration=\"").append(time(total, seq)).append("\" tcStart=\"0s\" tcFormat=\"NDF\" audioLayout=\"stereo\" audioRate=\"48k\">\n          <spine>\n");
        long offset = 0;
        for (Row r : rows) {
            long len = Math.max(1, frames(r.end() - r.start(), seq));
            long startFrames = frames(r.start(), seq);
            sb.append("            <asset-clip ref=\"").append(assetIds.get(r.clipKey())).append("\" name=\"").append(xml(r.clipName()))
                    .append("\" offset=\"").append(time(offset, seq)).append("\" start=\"").append(time(startFrames, seq))
                    .append("\" duration=\"").append(time(len, seq)).append("\" format=\"").append(assetFormat.get(r.clipKey())).append("\" tcFormat=\"NDF\"");
            if (r.muted()) sb.append(">\n              <adjust-volume amount=\"-96dB\"/>\n            </asset-clip>\n");
            else sb.append("/>\n");
            offset += len;
        }
        sb.append("          </spine>\n        </sequence>\n      </project>\n    </event>\n  </library>\n</fcpxml>\n");
        return new Result(sb.toString(), warnings);
    }

    private static String rate(int a, int b) { return a + "/" + b + "s"; }

    /** frames at rate num/den -> rational seconds "frames*den/num s" ("0s" for zero). */
    static String time(long frames, Rate r) {
        return frames == 0 ? "0s" : (frames * r.den()) + "/" + r.num() + "s";
    }

    private static String xml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String urlName(String name) {
        return java.net.URLEncoder.encode(name == null ? "clip" : name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
    }
}
