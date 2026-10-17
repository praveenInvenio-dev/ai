package com.aistorystudio.videoeditor.editing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Finishing pass over a rendered cut (Cardboard parity, batch 3). One FFmpeg run that adds, in order:
 * <ul>
 *   <li>zoom moments - "zoom in on the cup at 0:12": eased scale-in / hold / scale-out windows;</li>
 *   <li>text overlays - titles, lower thirds, call-outs, each with its own time window, position, style and a soft fade;</li>
 *   <li>sound effects placed at a time (whoosh, sparkle, ...);</li>
 *   <li>the voiceover track, with the cut's own audio / music ducked underneath it;</li>
 *   <li>export settings - mp4 / webm / mov / mkv, 480p - 4K, 24 - 60 fps.</li>
 * </ul>
 * Everything comes from a closed JSON model (no free FFmpeg syntax from users or models): numbers are clamped,
 * texts go through a text file (no escaping games), colours / positions / styles are looked up from fixed tables.
 */
public final class TimelineFinisher {

    private TimelineFinisher() { }

    public record Overlay(String type, double start, double end, String text, String position, String style,
                          String color, double amount, String sfx, double gain,
                          String animation, double xPercent, double yPercent) { }

    public record Export(String format, Integer height, Integer fps) {
        public String ext() {
            String f = format == null ? "mp4" : format.toLowerCase(Locale.ROOT);
            return List.of("mp4", "webm", "mov", "mkv").contains(f) ? f : "mp4";
        }
    }

    private static final ObjectMapper M = new ObjectMapper();

    private static final Map<String, String> COLORS = Map.of(
            "white", "white", "yellow", "#FFD60A", "black", "black", "red", "#FF4D4D", "green", "#3DDC84",
            "blue", "#4DA3FF", "pink", "#FF5DA2", "orange", "#FF9F1C");

    /** Built-in sound effects: the four recorded ones, plus sixteen synthesised by tools/build_sfx.py (no third-party audio). */
    public static final List<String> SFX_IDS = List.of("door", "footstep", "sparkle", "whoosh",
            "pop", "click", "tick", "ding", "success", "error", "swipe", "riser", "impact", "typing", "shutter", "bubble", "coin", "notification", "boing", "tada");
    public static final List<Integer> EXPORT_HEIGHTS = List.of(480, 720, 1080, 1440, 2160);
    public static final List<Integer> EXPORT_FPS = List.of(24, 25, 30, 50, 60);
    public static final List<String> EXPORT_FORMATS = List.of("mp4", "webm", "mov", "mkv");
    public static final List<String> COLOR_IDS = List.copyOf(COLORS.keySet());
    private static final int MAX_OVERLAYS = 40;

    /**
     * What the project is allowed to store: only known types / colours / sound effects survive, text is capped and
     * trimmed, times are clamped. Returns canonical JSON (or null when nothing is left).
     */
    public static String sanitize(String json) {
        List<Overlay> list = parse(json);
        com.fasterxml.jackson.databind.node.ArrayNode out = M.createArrayNode();
        for (Overlay o : list) {
            if (out.size() >= MAX_OVERLAYS) break;
            var n = out.addObject();
            n.put("type", o.type());
            n.put("start", Math.round(Math.min(o.start(), 36000) * 100) / 100.0);
            if (o.type().equals("text") || o.type().equals("text_behind_person")) {
                String t = o.text().replaceAll("\\s+", " ").trim();
                if (t.isEmpty()) { out.remove(out.size() - 1); continue; }
                n.put("end", Math.round(Math.min(o.end(), 36000) * 100) / 100.0);
                n.put("text", t.length() > 140 ? t.substring(0, 140) : t);
                String pos = o.position().toLowerCase(Locale.ROOT);
                n.put("position", List.of("top", "center", "middle", "bottom", "custom").contains(pos) ? pos : "bottom");
                String st = o.style().toLowerCase(Locale.ROOT);
                n.put("style", st.contains("lower") ? "lower_third" : st.contains("small") ? "small" : st.contains("box") ? "box" : "title");
                n.put("color", COLORS.containsKey(o.color().toLowerCase(Locale.ROOT)) ? o.color().toLowerCase(Locale.ROOT) : "white");
                String animation = safeAnimation(o.animation());
                n.put("animation", animation);
                n.put("xPercent", Math.round(clamp(o.xPercent(), 0, 100) * 100) / 100.0);
                n.put("yPercent", Math.round(clamp(o.yPercent(), 0, 100) * 100) / 100.0);
            } else if (o.type().equals("zoom")) {
                n.put("end", Math.round(Math.min(o.end(), 36000) * 100) / 100.0);
                n.put("amount", Math.round(o.amount() * 100) / 100.0);
            } else {
                String id = o.sfx().toLowerCase(Locale.ROOT);
                if (!SFX_IDS.contains(id)) { out.remove(out.size() - 1); continue; }
                n.put("sfx", id);
                n.put("gain", Math.round(o.gain() * 100) / 100.0);
            }
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static final List<String> ANIMATIONS = List.of("fade", "none", "slide_up", "slide_left", "float", "pop", "pulse");
    private static String safeAnimation(String animation) {
        String value = animation == null ? "fade" : animation.toLowerCase(Locale.ROOT).replace('-', '_').trim();
        return ANIMATIONS.contains(value) ? value : "fade";
    }
    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    /**
     * "1080p" means the SHORT side is 1080 (so a vertical 9:16 video becomes 1080 x 1920, not 608 x 1080); the other side keeps the
     * aspect ratio and stays even.
     */
    static String shortSideScale(int shortSide) {
        int e = Math.max(240, Math.min(2160, shortSide)) / 2 * 2;
        return "scale=w='if(gt(iw,ih),-2," + e + ")':h='if(gt(iw,ih)," + e + ",-2)':flags=lanczos";
    }

    /** Re-encodes a finished cut to the chosen container / resolution / frame rate (the last step of a final render). */
    public static List<String> exportCommand(String ffmpeg, Path input, Path output, Export export) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-y", "-i", input.toString()));
        List<String> vf = new ArrayList<>();
        if (export.fps() != null) vf.add("fps=" + Math.max(12, Math.min(60, export.fps())));
        if (export.height() != null) vf.add(shortSideScale(export.height()));
        vf.add("format=yuv420p");
        a.addAll(List.of("-vf", String.join(",", vf)));
        String ext = export.ext();
        if (ext.equals("webm")) a.addAll(List.of("-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "31", "-row-mt", "1", "-c:a", "libopus", "-b:a", "160k"));
        else a.addAll(List.of("-c:v", "libx264", "-preset", "medium", "-crf", "18", "-c:a", "aac", "-b:a", "192k"));
        if (ext.equals("mp4") || ext.equals("mov")) a.addAll(List.of("-movflags", "+faststart"));
        a.add(output.toString());
        return a;
    }

    /** Parses the project's overlay JSON; bad entries are skipped, numbers clamped. */
    public static List<Overlay> parse(String json) {
        List<Overlay> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            for (JsonNode n : M.readTree(json)) {
                String type = n.path("type").asText("").toLowerCase(Locale.ROOT);
                if (!List.of("text", "text_behind_person", "zoom", "sfx").contains(type)) continue;
                double start = Math.max(0, n.path("start").asDouble(0));
                double end = n.has("end") ? n.path("end").asDouble(start + 3) : start + (type.equals("zoom") ? 2 : type.equals("sfx") ? 0 : 3);
                if (!type.equals("sfx") && end <= start + 0.2) end = start + 0.2;
                out.add(new Overlay(type, start, end, n.path("text").asText(""), n.path("position").asText("bottom"),
                        n.path("style").asText("title"), n.path("color").asText("white"),
                        Math.max(1.05, Math.min(2.0, n.path("amount").asDouble(1.3))), n.path("sfx").asText(""),
                        Math.max(0, Math.min(2, n.path("gain").asDouble(1.0))),
                        safeAnimation(n.path("animation").asText("fade")),
                        clamp(n.path("xPercent").asDouble(50), 0, 100), clamp(n.path("yPercent").asDouble(50), 0, 100)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static boolean needed(List<Overlay> overlays, Path voiceover, Export export) {
        return !overlays.isEmpty() || voiceover != null
                || (export != null && (export.height() != null || export.fps() != null || !"mp4".equals(export.ext())));
    }

    /**
     * Builds the FFmpeg command. {@code textDir} receives one small text file per text overlay.
     * {@code sfxFiles} maps sfx ids to files (unknown ids are skipped).
     */
    public static List<String> command(String ffmpeg, Path input, Path output, int width, int height, List<Overlay> overlays,
                                       Path voiceover, double voiceGain, Map<String, Path> sfxFiles, Export export,
                                       Path fontFile, Path textDir) throws IOException {
        return command(ffmpeg, input, output, width, height, overlays, voiceover, voiceGain, sfxFiles, export, fontFile, textDir, true, 0);
    }

    public static List<String> command(String ffmpeg, Path input, Path output, int width, int height, List<Overlay> overlays,
                                       Path voiceover, double voiceGain, Map<String, Path> sfxFiles, Export export,
                                       Path fontFile, Path textDir, boolean hasAudio, double durationSec) throws IOException {
        return command(ffmpeg, input, output, width, height, overlays, voiceover, voiceGain, sfxFiles, export, fontFile, textDir, hasAudio, durationSec, false);
    }

    /**
     * @param hasAudio        whether the input has an audio stream (else silence of {@code durationSec} is used as the base)
     * @param replaceOriginal a dub: the original sound is lowered to about 16 % for the whole cut instead of only being ducked under the voiceover
     */
    public static List<String> command(String ffmpeg, Path input, Path output, int width, int height, List<Overlay> overlays,
                                       Path voiceover, double voiceGain, Map<String, Path> sfxFiles, Export export,
                                       Path fontFile, Path textDir, boolean hasAudio, double durationSec, boolean replaceOriginal) throws IOException {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-y", "-i", input.toString()));
        StringBuilder f = new StringBuilder();
        String v = "0:v";
        int inputs = 1;

        // ---- zoom moments: z(t) = 1 + sum of eased windows; scale per frame, centre crop back to the canvas
        StringBuilder z = new StringBuilder("1");
        for (Overlay o : overlays) {
            if (!o.type().equals("zoom")) continue;
            double r = Math.min(0.5, (o.end() - o.start()) / 3);
            z.append("+").append(fmt(o.amount() - 1)).append("*if(between(t,").append(fmt(o.start())).append(',').append(fmt(o.end()))
                    .append("),min(1,(t-").append(fmt(o.start())).append(")/").append(fmt(r)).append(")*min(1,(")
                    .append(fmt(o.end())).append("-t)/").append(fmt(r)).append("),0)");
        }
        if (!z.toString().equals("1")) {
            f.append('[').append(v).append("]scale=w='2*trunc(").append(width).append("*(").append(z).append(")/2)':h=-2:eval=frame:flags=bicubic,crop=")
                    .append(width).append(':').append(height).append(",setsar=1[vz];");
            v = "vz";
        }

        // ---- text overlays: measured with the real font, wrapped and shrunk to fit, one drawtext per line (so every line is centred)
        int ti = 0;
        java.awt.Font awt = loadFont(fontFile);
        for (Overlay o : overlays) {
            if (!o.type().equals("text") || o.text().isBlank()) continue;
            boolean lower = o.style().contains("lower");
            int size = (int) Math.round(height * (lower ? 0.042 : o.style().contains("small") ? 0.035 : 0.065));
            double maxW = width * (lower ? 0.84 : 0.86);
            List<String> lines = fitLines(awt, o.text().trim(), size, maxW, lower ? 2 : 3);
            size = fittedSize;
            String color = COLORS.getOrDefault(o.color().toLowerCase(Locale.ROOT), "white");
            String in = fmt(o.start()), outT = fmt(o.end());
            String fadeAlpha = "if(lt(t," + fmt(o.start() + 0.3) + "),(t-" + in + ")/0.3,if(gt(t," + fmt(o.end() - 0.3) + "),(" + outT + "-t)/0.3,1))";
            double lh = size * 1.22;
            String pos = o.position().toLowerCase(Locale.ROOT);
            String yBase = switch (lower ? "lower" : pos) {
                case "top" -> "h*0.08";
                case "center", "middle" -> "(h-" + fmt(lh * lines.size()) + ")/2";
                case "custom" -> "h*" + fmt(o.yPercent() / 100.0);
                case "lower" -> "h*0.78";
                default -> "h*0.82-" + fmt(lh * lines.size());
            };
            boolean box = lower || o.style().contains("box");
            for (int li = 0; li < lines.size(); li++) {
                Path tf = textDir.resolve("overlay-" + (ti) + "-" + li + ".txt");
                Files.writeString(tf, lines.get(li), StandardCharsets.UTF_8);
                String x = lower ? "w*0.06" : pos.equals("custom") ? "w*" + fmt(o.xPercent() / 100.0) : "(w-text_w)/2";
                String y = yBase + "+" + fmt(lh * li);
                String alpha = fadeAlpha;
                // Motion is evaluated by FFmpeg per frame. Every animation is bounded to the overlay's time window.
                String elapsed = "(t-" + in + ")";
                String enter = "min(1,max(0," + elapsed + "/0.45))";
                switch (safeAnimation(o.animation())) {
                    case "none" -> alpha = "1";
                    case "slide_up" -> y = "(" + y + ")+h*0.12*(1-" + enter + ")";
                    case "slide_left" -> x = "(" + x + ")+w*0.18*(1-" + enter + ")";
                    case "float" -> y = "(" + y + ")+sin(" + elapsed + "*2.4)*h*0.018";
                    case "pop" -> { alpha = "(" + fadeAlpha + ")*min(1,max(0," + elapsed + "/0.18))"; y = "(" + y + ")+h*0.025*(1-" + enter + ")"; }
                    case "pulse" -> { /* gentle position pulse avoids drawtext font reinitialization on every frame */
                        x = "(" + x + ")+sin(" + elapsed + "*5)*w*0.006";
                    }
                    default -> { }
                }
                String label = "vt" + ti + "_" + li;
                f.append('[').append(v).append("]drawtext=fontfile='").append(path(fontFile)).append("':textfile='").append(path(tf))
                        .append("':fontsize=").append(size).append(":fontcolor=").append(color)
                        .append(":x=").append(x).append(":y=").append(y)
                        .append(box ? ":box=1:boxcolor=black@0.55:boxborderw=" + Math.max(8, size / 3) : ":borderw=" + Math.max(2, size / 16) + ":bordercolor=black@0.6")
                        .append(":alpha='").append(alpha).append("'")
                        .append(":enable='between(t,").append(in).append(',').append(outT).append(")'[").append(label).append("];");
                v = label;
            }
            ti++;
        }

        // ---- export: frame rate + resolution
        List<String> tail = new ArrayList<>();
        if (export != null && export.fps() != null) tail.add("fps=" + Math.max(12, Math.min(60, export.fps())));
        if (export != null && export.height() != null) tail.add(shortSideScale(export.height()));
        tail.add("format=yuv420p");
        f.append('[').append(v).append(']').append(String.join(",", tail)).append("[vout];");

        // ---- audio: cut audio (+music), ducked under the voiceover, plus sound effects
        String aBase = "0:a";
        List<String> mix = new ArrayList<>();
        if (voiceover != null) {
            a.addAll(List.of("-i", voiceover.toString()));
            int vi = inputs++;
            f.append('[').append(vi).append(":a]aresample=48000,aformat=channel_layouts=stereo,volume=").append(fmt(Math.max(0.1, Math.min(2, voiceGain))))
                    .append(",asplit=2[vo][vosc0];");
            // the ducking control is padded with silence: without it the filter (and the mix) would end with the voiceover
            // and the cut's own audio would disappear after it
            f.append("[vosc0]apad[vosc];");
            f.append(baseAudio(aBase, hasAudio, durationSec)).append("[base0];")
                    .append(replaceOriginal ? "[base0]volume=0.16[base];" : "[base0]anull[base];")
                    .append("[base][vosc]sidechaincompress=threshold=0.03:ratio=8:attack=20:release=350[ducked];");
            mix.add("ducked");
            mix.add("vo");
        } else {
            f.append(baseAudio(aBase, hasAudio, durationSec)).append("[base];");
            mix.add("base");
        }
        int si = 0;
        for (Overlay o : overlays) {
            if (!o.type().equals("sfx")) continue;
            Path file = sfxFiles.get(o.sfx().toLowerCase(Locale.ROOT));
            if (file == null || !Files.isRegularFile(file)) continue;
            a.addAll(List.of("-i", file.toString()));
            int idx = inputs++;
            long ms = Math.round(o.start() * 1000);
            String lab = "sfx" + (si++);
            f.append('[').append(idx).append(":a]aresample=48000,aformat=channel_layouts=stereo,volume=").append(fmt(o.gain()))
                    .append(",adelay=").append(ms).append('|').append(ms).append('[').append(lab).append("];");
            mix.add(lab);
        }
        if (mix.size() == 1) f.append('[').append(mix.get(0)).append("]anull[aout]");
        else {
            for (String m : mix) f.append('[').append(m).append(']');
            f.append("amix=inputs=").append(mix.size()).append(":duration=first:dropout_transition=0:normalize=0[aout]");
        }

        a.addAll(List.of("-filter_complex", f.toString(), "-map", "[vout]", "-map", "[aout]"));
        String ext = export == null ? "mp4" : export.ext();
        if (ext.equals("webm")) a.addAll(List.of("-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "31", "-row-mt", "1", "-c:a", "libopus", "-b:a", "160k"));
        else a.addAll(List.of("-c:v", "libx264", "-preset", "medium", "-crf", "18", "-c:a", "aac", "-b:a", "192k"));
        if (ext.equals("mp4") || ext.equals("mov")) a.addAll(List.of("-movflags", "+faststart"));
        a.add(output.toString());
        return a;
    }

    /** Runs the finishing pass; returns the finished file. */
    public static Path run(List<String> command, long timeoutMinutes) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] log = p.getInputStream().readAllBytes();
        if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) { p.destroyForcibly(); throw new IOException("The finishing pass timed out."); }
        if (p.exitValue() != 0) {
            String s = new String(log, StandardCharsets.UTF_8);
            throw new IOException("Finishing pass failed: " + s.substring(Math.max(0, s.length() - 600)));
        }
        return Path.of(command.get(command.size() - 1));
    }

    private static int fittedSize;   // result of the last fitLines call (single-threaded use inside command())

    private static java.awt.Font loadFont(Path fontFile) {
        try {
            return java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, fontFile.toFile());
        } catch (Exception e) {
            return new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 12);
        }
    }

    private static double textWidth(java.awt.Font f, String t, int size) {
        return f.deriveFont((float) size).getStringBounds(t, new java.awt.font.FontRenderContext(null, true, true)).getWidth();
    }

    /** Greedy word wrap at the given size; shrinks the font until it fits in maxLines lines each narrower than maxW. */
    static List<String> fitLines(java.awt.Font font, String text, int size, double maxW, int maxLines) {
        for (int sz = size; ; sz = (int) Math.floor(sz * 0.92)) {
            List<String> lines = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean tooWide = false;
            for (String w : text.split("\\s+")) {
                if (textWidth(font, w, sz) > maxW) tooWide = true;
                String cand = cur.length() == 0 ? w : cur + " " + w;
                if (cur.length() > 0 && textWidth(font, cand, sz) > maxW) { lines.add(cur.toString()); cur = new StringBuilder(w); }
                else { cur = new StringBuilder(cand); }
            }
            if (cur.length() > 0) lines.add(cur.toString());
            if ((!tooWide && lines.size() <= maxLines) || sz <= 18) { fittedSize = sz; return lines; }
        }
    }

    private static String baseAudio(String label, boolean hasAudio, double durationSec) {
        if (!hasAudio) return "aevalsrc=0:d=" + fmt(Math.max(0.5, durationSec)) + ":s=48000:c=stereo";
        return "[" + label + "]aresample=48000,aformat=channel_layouts=stereo";
    }

    private static String path(Path p) { return p.toAbsolutePath().toString().replace("\\", "/").replace("'", ""); }

    private static String fmt(double d) { return String.format(Locale.ROOT, "%.3f", d); }
}
