package com.aistorystudio.videoeditor.service;

import com.aistorystudio.videoeditor.domain.CaptionTrack;
import com.aistorystudio.videoeditor.repository.CaptionTrackRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * "Auto captions" for the AI video editor (Higgsfield / CapCut style):
 * render -> faster-whisper word timings (video-worker) -> short 2-4 word phrases -> styled ASS
 * subtitles where the spoken word lights up -> burned into the video with FFmpeg/libass.
 * Styles follow the editor's caption presets: CLEAN, BOLD_REEL, KIDS, CINEMATIC, MINIMAL.
 */
@Service
public class AutoCaptionService {

    private static final Logger log = LoggerFactory.getLogger(AutoCaptionService.class);
    private static final String[] FONTS = {"BarlowSemiCondensed-SemiBold.ttf", "Kalam-Bold.ttf", "Bangers-Regular.ttf",
            "NotoSansDevanagari.ttf", "NotoSansKannada.ttf", "NotoSansTamil.ttf", "NotoSansTelugu.ttf",
            "NotoSansMalayalam.ttf", "NotoSansBengali.ttf", "NotoSansGujarati.ttf"};

    private final CaptionTrackRepository captions;
    private final String workerBaseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public AutoCaptionService(CaptionTrackRepository captions,
                              @Value("${studio.video-editor.worker-base-url:http://video-worker:5010}") String workerBaseUrl) {
        this.captions = captions;
        this.workerBaseUrl = workerBaseUrl;
    }

    /** Burns captions into {@code video} in place. Returns a note for the user (null = nothing done). */
    public String apply(UUID projectId, Path video, boolean vertical) throws Exception {
        CaptionTrack track = captions.findByProjectId(projectId).orElse(null);
        String preset = track == null || track.getStylePreset() == null ? "BOLD_REEL" : track.getStylePreset();
        if (track != null && !track.isBurnIn()) return null;

        JsonNode tr = transcribe(video);
        List<double[]> times = new ArrayList<>();
        List<String> words = new ArrayList<>();
        for (JsonNode w : tr.path("words")) {
            String t = w.path("word").asText("").trim();
            if (t.isEmpty()) continue;
            words.add(t);
            times.add(new double[]{w.path("start").asDouble(), w.path("end").asDouble()});
        }
        if (words.isEmpty()) return "Auto captions: no speech found, nothing burned in.";

        Path dir = video.getParent().resolve("captions-" + UUID.randomUUID());
        Files.createDirectories(dir);
        Path fonts = dir.resolve("fonts");
        Files.createDirectories(fonts);
        for (String f : FONTS) {
            try (InputStream in = getClass().getResourceAsStream("/concept-fonts/" + f)) {
                if (in != null) Files.copy(in, fonts.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        String ass = buildAss(words, times, preset, vertical);
        Path assFile = dir.resolve("captions.ass");
        Files.writeString(assFile, ass, StandardCharsets.UTF_8);
        Path srtFile = dir.resolve("captions.srt");
        Files.writeString(srtFile, buildSrt(tr), StandardCharsets.UTF_8);

        Path out = dir.resolve("captioned.mp4");
        String filter = "ass=" + escape(assFile.toAbsolutePath().toString()) + ":fontsdir=" + escape(fonts.toAbsolutePath().toString());
        run(List.of("ffmpeg", "-nostdin", "-y", "-i", video.toString(), "-vf", filter,
                "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-c:a", "copy",
                "-movflags", "+faststart", out.toString()));
        Files.move(out, video, StandardCopyOption.REPLACE_EXISTING);

        if (track != null) {
            track.setTranscriptJson(tr.toString());
            captions.save(track);
        }
        log.info("Auto captions burned into {} ({} words, style {}, language {})", video.getFileName(), words.size(), preset,
                tr.path("language").asText("?"));
        return "Auto captions added (" + words.size() + " words, " + preset.toLowerCase(Locale.ROOT).replace('_', ' ') + ").";
    }

    /**
     * Animated title over the first ~3 s (CapCut "Text"): big centred text with a soft fade/pop in, hold,
     * fade out. Burned into the video in place; style follows the project's caption preset.
     */
    public void addTitle(UUID projectId, Path video, String title, boolean vertical) throws Exception {
        CaptionTrack track = captions.findByProjectId(projectId).orElse(null);
        String preset = track == null || track.getStylePreset() == null ? "BOLD_REEL" : track.getStylePreset();
        Path dir = video.getParent().resolve("title-" + UUID.randomUUID());
        Files.createDirectories(dir);
        Path fonts = dir.resolve("fonts");
        Files.createDirectories(fonts);
        for (String f : FONTS) {
            try (InputStream in = getClass().getResourceAsStream("/concept-fonts/" + f)) {
                if (in != null) Files.copy(in, fonts.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path assFile = dir.resolve("title.ass");
        Files.writeString(assFile, buildTitleAss(title, preset, vertical), StandardCharsets.UTF_8);
        Path out = dir.resolve("titled.mp4");
        String filter = "ass=" + escape(assFile.toAbsolutePath().toString()) + ":fontsdir=" + escape(fonts.toAbsolutePath().toString());
        run(List.of("ffmpeg", "-nostdin", "-y", "-i", video.toString(), "-vf", filter,
                "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-c:a", "copy",
                "-movflags", "+faststart", out.toString()));
        Files.move(out, video, StandardCopyOption.REPLACE_EXISTING);
    }

    static String buildTitleAss(String title, String preset, boolean vertical) {
        int resX = vertical ? 1080 : 1920, resY = vertical ? 1920 : 1080;
        String font; int size, outline; String primary, edge;
        switch (preset) {
            case "KIDS" -> { font = "Kalam"; size = vertical ? 120 : 96; primary = "&H0050E0FF"; edge = "&H00602010"; outline = 7; }
            case "CLEAN", "MINIMAL", "CINEMATIC" -> { font = "Barlow Semi Condensed SemiBold"; size = vertical ? 96 : 80; primary = "&H00FFFFFF"; edge = "&H00000000"; outline = 3; }
            default -> { font = "Bangers"; size = vertical ? 130 : 104; primary = "&H0000E1FF"; edge = "&H00000000"; outline = 8; }
        }
        // wrap long titles at word boundaries (about 18 characters per line on vertical, 30 on wide)
        int maxChars = vertical ? 18 : 30;
        StringBuilder wrapped = new StringBuilder(); int col = 0;
        for (String w : title.split(" ")) {
            if (col > 0 && col + 1 + w.length() > maxChars) { wrapped.append("\\N"); col = 0; }
            if (col > 0) { wrapped.append(' '); col++; }
            wrapped.append(w.replace("{", "(").replace("}", ")")); col += w.length();
        }
        StringBuilder a = new StringBuilder();
        a.append("[Script Info]\nScriptType: v4.00+\nPlayResX: ").append(resX).append("\nPlayResY: ").append(resY).append("\nWrapStyle: 2\n\n");
        a.append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n");
        a.append("Style: Title,").append(font).append(',').append(size).append(',').append(primary).append(",&H00FFFFFF,").append(edge)
                .append(",&H80000000,-1,0,0,0,100,100,2,0,1,").append(outline).append(",3,8,90,90,").append(vertical ? 250 : 110).append(",1\n\n");
        a.append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        // pop-in (scale 70 -> 100 with fade), hold, fade out
        a.append("Dialogue: 0,0:00:00.20,0:00:03.20,Title,,0,0,0,,{\\fad(300,450)\\fscx70\\fscy70\\t(0,350,\\fscx100\\fscy100)}")
                .append(wrapped).append('\n');
        return a.toString();
    }

    private JsonNode transcribe(Path video) throws Exception {
        var body = mapper.createObjectNode().put("source", video.toAbsolutePath().toString());
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(workerBaseUrl + "/api/transcribe"))
                .timeout(Duration.ofMinutes(30)).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var resp = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        JsonNode n = mapper.readTree(resp.body());
        if (resp.statusCode() >= 300) throw new IllegalStateException("Transcription failed: " + n.path("error").asText(resp.body()));
        return n;
    }

    /**
     * Short phrases (max 4 words / 1.6 s); the word being spoken is highlighted (karaoke fill),
     * the phrase pops in slightly. Positions: lower third for 16:9, centre-low for 9:16 reels.
     */
    static String buildAss(List<String> words, List<double[]> t, String preset, boolean vertical) {
        if ("STACKED".equals(preset)) return buildStacked(words, t, vertical);
        if ("LINE_REVEAL".equals(preset)) return buildLineReveal(words, t, vertical);
        int resX = vertical ? 1080 : 1920, resY = vertical ? 1920 : 1080;
        String font, primary, highlight, outline;
        int size, outlineW, marginV;
        boolean pop;
        switch (preset) {
            case "CLEAN" -> { font = "Barlow Semi Condensed SemiBold"; size = vertical ? 64 : 54; primary = "&H00FFFFFF"; highlight = "&H0000E5FF"; outline = "&H00000000"; outlineW = 3; marginV = vertical ? 360 : 70; pop = false; }
            case "KIDS" -> { font = "Kalam"; size = vertical ? 84 : 66; primary = "&H00FFFFFF"; highlight = "&H0050E0FF"; outline = "&H00602010"; outlineW = 6; marginV = vertical ? 420 : 80; pop = true; }
            case "CINEMATIC" -> { font = "Barlow Semi Condensed SemiBold"; size = vertical ? 54 : 44; primary = "&H00F0F0F0"; highlight = "&H00A0E0FF"; outline = "&H00000000"; outlineW = 2; marginV = vertical ? 300 : 60; pop = false; }
            case "MINIMAL" -> { font = "Barlow Semi Condensed SemiBold"; size = vertical ? 50 : 40; primary = "&H00FFFFFF"; highlight = "&H00FFFFFF"; outline = "&H00000000"; outlineW = 2; marginV = vertical ? 260 : 50; pop = false; }
            default -> { font = "Bangers"; size = vertical ? 96 : 72; primary = "&H00FFFFFF"; highlight = "&H0000E1FF"; outline = "&H00000000"; outlineW = 7; marginV = vertical ? 520 : 110; pop = true; } // BOLD_REEL
        }
        StringBuilder a = new StringBuilder();
        a.append("[Script Info]\nScriptType: v4.00+\nPlayResX: ").append(resX).append("\nPlayResY: ").append(resY).append("\nWrapStyle: 0\n\n");
        a.append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n");
        // karaoke: SecondaryColour = not yet spoken, PrimaryColour = spoken (\kf fills from secondary to primary)
        a.append("Style: Cap,").append(font).append(',').append(size).append(',').append(highlight).append(',').append(primary).append(',')
                .append(outline).append(",&H80000000,-1,0,0,0,100,100,0,0,1,").append(outlineW).append(",2,2,80,80,").append(marginV).append(",1\n\n");
        a.append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        int i = 0;
        while (i < words.size()) {
            int j = i;
            double start = t.get(i)[0];
            while (j < words.size() && j - i < 4 && t.get(j)[1] - start <= 1.6) {
                j++;
                if (words.get(j - 1).matches(".*[.!?,।]$")) break;
            }
            if (j == i) j = i + 1;
            double end = Math.max(t.get(j - 1)[1], start + 0.4);
            if (j < words.size()) end = Math.min(end + 0.25, t.get(j)[0]);
            StringBuilder text = new StringBuilder();
            if (pop) text.append("{\\fscx85\\fscy85\\t(0,120,\\fscx100\\fscy100)}");
            double cursor = start;
            for (int k = i; k < j; k++) {
                int cs = (int) Math.max(1, Math.round((t.get(k)[1] - cursor) * 100));
                text.append("{\\kf").append(cs).append('}').append(clean(words.get(k))).append(k < j - 1 ? " " : "");
                cursor = t.get(k)[1];
            }
            a.append("Dialogue: 0,").append(assTime(start)).append(',').append(assTime(end)).append(",Cap,,0,0,0,,").append(text).append('\n');
            i = j;
        }
        return a.toString();
    }

    // ASS header shared by the two layout-driven styles
    private static String assHeader(int resX, int resY, String font, int size, String primary, String outlineColour, int outline, int borderStyle) {
        return "[Script Info]\nScriptType: v4.00+\nPlayResX: " + resX + "\nPlayResY: " + resY + "\nWrapStyle: 2\n\n"
                + "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"
                + "Style: Cap," + font + "," + size + "," + primary + ",&H00FFFFFF," + outlineColour + ",&H80000000,-1,0,0,0,100,100,0,0," + borderStyle + "," + outline + ",2,5,60,60,0,1\n\n"
                + "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n";
    }

    /** Stacked captions: up to 3 words, one per line, each word pops in on its own beat and the stack clears together. */
    static String buildStacked(List<String> words, List<double[]> t, boolean vertical) {
        int resX = vertical ? 1080 : 1920, resY = vertical ? 1920 : 1080;
        int size = vertical ? 118 : 88, lineH = (int) (size * 1.06);
        String highlight = "&H0000E1FF", white = "&H00FFFFFF";
        StringBuilder a = new StringBuilder(assHeader(resX, resY, "Bangers", size, white, "&H00000000", vertical ? 8 : 6, 1));
        double baseY = vertical ? resY * 0.60 : resY * 0.70;
        int i = 0;
        while (i < words.size()) {
            int j = i;
            double start = t.get(i)[0];
            while (j < words.size() && j - i < 3 && t.get(j)[1] - start <= 1.8) {
                j++;
                if (words.get(j - 1).matches(".*[.!?,।]$")) break;
            }
            if (j == i) j = i + 1;
            int n = j - i;
            double end = Math.max(t.get(j - 1)[1], start + 0.4);
            if (j < words.size()) end = Math.min(end + 0.3, t.get(j)[0]);
            double top = baseY - (n - 1) * lineH / 2.0;
            for (int k = 0; k < n; k++) {
                double ws = t.get(i + k)[0];
                int y = (int) Math.round(top + k * lineH), x = resX / 2;
                String color;
                if (k < n - 1) {                                     // highlight until the next word starts, then settle to white
                    int ms = (int) Math.max(60, Math.round((t.get(i + k + 1)[0] - ws) * 1000));
                    color = "\\1c" + highlight + "\\t(" + ms + "," + (ms + 80) + ",\\1c" + white + ")";
                } else {
                    color = "\\1c" + highlight;
                }
                a.append("Dialogue: 0,").append(assTime(ws)).append(',').append(assTime(Math.max(end, ws + 0.3))).append(",Cap,,0,0,0,,")
                        .append("{\\an5\\pos(").append(x).append(',').append(y).append(")\\fscx60\\fscy60\\t(0,110,\\fscx100\\fscy100)")
                        .append(color).append('}').append(clean(words.get(i + k).toUpperCase(java.util.Locale.ROOT))).append('\n');
            }
            i = j;
        }
        return a.toString();
    }

    /** Line reveal: a whole line (up to 6 words) slides up and fades in on a soft dark box, then the next line replaces it. */
    static String buildLineReveal(List<String> words, List<double[]> t, boolean vertical) {
        int resX = vertical ? 1080 : 1920, resY = vertical ? 1920 : 1080;
        int size = vertical ? 64 : 50;
        StringBuilder a = new StringBuilder(assHeader(resX, resY, "Barlow Semi Condensed SemiBold", size, "&H00FFFFFF", "&H99000000", 16, 3));
        int x = resX / 2, y = vertical ? (int) (resY * 0.72) : (int) (resY * 0.86);
        int i = 0;
        while (i < words.size()) {
            int j = i;
            double start = t.get(i)[0];
            while (j < words.size() && j - i < 6 && t.get(j)[1] - start <= 2.6) {
                j++;
                if (words.get(j - 1).matches(".*[.!?।]$")) break;
            }
            if (j == i) j = i + 1;
            double end = Math.max(t.get(j - 1)[1] + 0.2, start + 0.5);
            if (j < words.size()) end = Math.min(end, t.get(j)[0]);
            StringBuilder text = new StringBuilder();
            for (int k = i; k < j; k++) text.append(k > i ? " " : "").append(clean(words.get(k)));
            a.append("Dialogue: 0,").append(assTime(start)).append(',').append(assTime(end)).append(",Cap,,0,0,0,,")
                    .append("{\\an5\\fad(160,110)\\move(").append(x).append(',').append(y + 34).append(',').append(x).append(',').append(y).append(",0,200)}")
                    .append(text).append('\n');
            i = j;
        }
        return a.toString();
    }

    private static String buildSrt(JsonNode tr) {
        StringBuilder s = new StringBuilder();
        int n = 1;
        for (JsonNode seg : tr.path("segments")) {
            s.append(n++).append('\n').append(srtTime(seg.path("start").asDouble())).append(" --> ")
                    .append(srtTime(seg.path("end").asDouble())).append('\n').append(seg.path("text").asText("")).append("\n\n");
        }
        return s.toString();
    }

    private static String clean(String w) {
        return w.replace("{", "(").replace("}", ")").replace("\\", "/");
    }

    private static String assTime(double sec) {
        long cs = Math.max(0, Math.round(sec * 100));
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", cs / 360000, (cs / 6000) % 60, (cs / 100) % 60, cs % 100);
    }

    private static String srtTime(double sec) {
        long ms = Math.max(0, Math.round(sec * 1000));
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000);
    }

    /** FFmpeg filter-argument escaping for paths. */
    private static String escape(String p) {
        return p.replace("\\", "/").replace(":", "\\:").replace("'", "\\'");
    }

    private static void run(List<String> args) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.MINUTES)) { p.destroyForcibly(); throw new IllegalStateException("ffmpeg timed out"); }
        if (p.exitValue() != 0) {
            String s = new String(out, StandardCharsets.UTF_8);
            throw new IllegalStateException("Caption burn-in failed: " + s.substring(Math.max(0, s.length() - 500)));
        }
    }
}
