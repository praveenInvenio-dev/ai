package com.aistorystudio.storyreveal;

import com.aistorystudio.conceptexplainer.ConceptSlideRenderer;
import com.aistorystudio.domain.Asset;
import com.aistorystudio.domain.Episode;
import com.aistorystudio.domain.Scene;
import com.aistorystudio.domain.enums.AssetType;
import com.aistorystudio.domain.enums.EpisodeStatus;
import com.aistorystudio.provider.MediaProcessor;
import com.aistorystudio.provider.StorageProvider;
import com.aistorystudio.repository.AssetRepository;
import com.aistorystudio.repository.EpisodeRepository;
import com.aistorystudio.repository.SceneRepository;
import com.aistorystudio.service.StoryboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Classic storyboard video - NO video model. Built like the Concept Explainer:
 *
 * <pre>
 * scene image -> background removal (video-worker) -> foreground split into up to 3 objects
 * narration   -> spoken segment by segment, start time of every segment known
 * video       -> background, then each object "lifts" out of the picture (scale + float) when its
 *                segment starts, the spoken line appears as a subtitle card, slow push-in on the
 *                whole frame, scenes cross-fade (no black frames, no split-screen transitions)
 * </pre>
 */
@Service
public class StoryRevealService {

    private static final Logger log = LoggerFactory.getLogger(StoryRevealService.class);
    private static final double TAIL = 0.7, CROSSFADE = 0.5, LIFT = 0.06;

    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }

    public static final class Job {
        public final UUID id = UUID.randomUUID();
        public final UUID episodeId;
        public final Instant createdAt = Instant.now();
        public volatile Status status = Status.QUEUED;
        public volatile String stage = "Queued";
        public volatile String error;
        public volatile double seconds;
        public final List<String> warnings = java.util.Collections.synchronizedList(new ArrayList<>());

        Job(UUID episodeId) { this.episodeId = episodeId; }
    }

    public record JobView(UUID id, UUID episodeId, Status status, String stage, String error, double seconds, List<String> warnings) { }

    private record Layer(Path png, int x, int y, int w, int h) { }

    private final StoryboardService storyboard;
    private final SceneRepository scenes;
    private final EpisodeRepository episodes;
    private final AssetRepository assets;
    private final StorageProvider storage;
    private final MediaProcessor media;
    private final String workerBaseUrl;
    private final ConceptSlideRenderer captions = new ConceptSlideRenderer();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();

    public StoryRevealService(StoryboardService storyboard, SceneRepository scenes, EpisodeRepository episodes,
                              AssetRepository assets, StorageProvider storage, MediaProcessor media,
                              @Value("${studio.video-editor.worker-base-url:http://video-worker:5010}") String workerBaseUrl) {
        this.storyboard = storyboard;
        this.scenes = scenes;
        this.episodes = episodes;
        this.assets = assets;
        this.storage = storage;
        this.media = media;
        this.workerBaseUrl = workerBaseUrl;
    }

    public Job start(UUID episodeId) {
        episodes.findById(episodeId).orElseThrow(() -> new IllegalArgumentException("Story not found: " + episodeId));
        Job job = new Job(episodeId);
        jobs.put(job.id, job);
        jobs.values().removeIf(j -> j.createdAt.isBefore(Instant.now().minus(Duration.ofHours(24))));
        return job;
    }

    public JobView view(UUID id) {
        Job j = jobs.get(id);
        if (j == null) throw new IllegalArgumentException("Job not found (expired?): " + id);
        return new JobView(j.id, j.episodeId, j.status, j.stage, j.error, j.seconds, List.copyOf(j.warnings));
    }

    // ================================================================== render

    @Async("videoGenerationExecutor")
    public void renderAsync(UUID jobId, String voice, boolean showCaptions) {
        Job job = jobs.get(jobId);
        if (job == null) return;
        job.status = Status.RUNNING;
        try {
            Episode ep = episodes.findById(job.episodeId).orElseThrow();
            List<Scene> ordered = scenes.findByEpisodeIdOrderByOrderIndexAsc(ep.getId());
            if (ordered.isEmpty()) throw new IllegalStateException("Add at least one scene first.");
            Map<UUID, Asset> images = storyboard.imagesBySceneId(ep.getId());
            List<String> missing = ordered.stream().filter(s -> !images.containsKey(s.getId())).map(s -> "scene " + s.getSceneNumber()).toList();
            if (!missing.isEmpty()) throw new IllegalStateException("No image for " + String.join(", ", missing) + ".");

            BufferedImage first = ImageIO.read(Path.of(images.get(ordered.get(0).getId()).getFilePath()).toFile());
            boolean landscape = first != null && first.getWidth() > first.getHeight();
            int W = landscape ? 1920 : 1080, H = landscape ? 1080 : 1920;
            Path work = storage.resolve(ep.getProjectId() + "/" + ep.getId() + "/classic-video/" + job.id);
            Files.createDirectories(work);

            List<Path> clips = new ArrayList<>();
            List<Double> durations = new ArrayList<>();
            StringBuilder srt = new StringBuilder();
            double sceneStart = 0;
            int cue = 1;
            for (int i = 0; i < ordered.size(); i++) {
                Scene sc = ordered.get(i);
                String tag = "Scene " + (i + 1) + "/" + ordered.size();
                job.stage = tag + ": recording narration";
                StoryboardService.TimedNarration tn = storyboard.narrateTimed(ep.getId(), sc.getId(), voice);
                double speech = tn.seconds() > 0 ? tn.seconds() : media.probeDurationSeconds(tn.wav());
                double dur = Math.max(2.5, speech + TAIL);
                job.stage = tag + ": separating objects in the picture";
                Path img = Path.of(images.get(sc.getId()).getFilePath());
                List<Layer> layers = layers(img, work.resolve("s" + i), W, H, job);
                job.stage = tag + ": animating";
                Path clip = work.resolve(String.format(Locale.ROOT, "scene-%02d.mp4", i + 1));
                renderScene(img, layers, tn, speech, dur, showCaptions, W, H, work.resolve("s" + i), clip);
                clips.add(clip);
                durations.add(dur);
                for (int k = 0; k < tn.segmentStarts().size(); k++) {
                    double a = sceneStart + tn.segmentStarts().get(k);
                    double b = sceneStart + (k + 1 < tn.segmentStarts().size() ? tn.segmentStarts().get(k + 1) : speech) - 0.05;
                    srt.append(cue++).append('\n').append(srtTime(a)).append(" --> ").append(srtTime(b)).append('\n')
                            .append(tn.segmentTexts().get(k)).append("\n\n");
                }
                sceneStart += dur - (i < ordered.size() - 1 ? CROSSFADE : 0);
            }
            job.stage = "Joining scenes and mixing music";
            Path joined = work.resolve("joined.mp4");
            join(clips, durations, joined);
            Path out = storage.resolve(ep.getProjectId() + "/" + ep.getId() + "/video/classic-" + job.id + ".mp4");
            Files.createDirectories(out.getParent());
            mixMusic(joined, storyboard.musicFor(ep.getId()), out);
            job.seconds = media.probeDurationSeconds(out);
            publish(ep, out, srt.toString(), job);
            job.stage = "Ready";
            job.status = Status.SUCCEEDED;
        } catch (Exception e) {
            log.warn("Classic storyboard video {} failed: {}", jobId, e.getMessage(), e);
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            job.stage = "Failed";
            job.status = Status.FAILED;
        }
    }

    // ================================================================== layers

    /**
     * Cuts the foreground out (rembg in video-worker) and splits it into up to 3 separate objects,
     * largest first, ordered left to right, each pre-scaled to output coordinates.
     * No worker / no subject -> no layers (background + captions still animate).
     */
    private List<Layer> layers(Path image, Path dir, int W, int H, Job job) {
        List<Layer> out = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            Path cut = dir.resolve("cutout.png");
            var body = mapper.createObjectNode();
            body.put("source", image.toAbsolutePath().toString());
            body.put("target", cut.toAbsolutePath().toString());
            body.put("kind", "image");
            body.put("background", "transparent");
            body.put("quality", "general");
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(workerBaseUrl + "/api/remove-background"))
                    .timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var resp = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300 || !Files.isRegularFile(cut)) {
                warnOnce(job, "Object separation unavailable (video-worker): scenes animate without lifted objects.");
                return out;
            }
            BufferedImage rgba = ImageIO.read(cut.toFile());
            int iw = rgba.getWidth(), ih = rgba.getHeight();
            // components on a 1/4 grid
            int gw = Math.max(1, iw / 4), gh = Math.max(1, ih / 4);
            int[] label = new int[gw * gh];
            List<int[]> comps = new ArrayList<>(); // {id, area, minx, miny, maxx, maxy}
            int next = 0;
            int[] stack = new int[gw * gh];
            for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
                int idx = y * gw + x;
                if (label[idx] != 0 || alpha(rgba, x * 4, y * 4) < 128) continue;
                next++;
                int sp = 0, area = 0, minx = x, miny = y, maxx = x, maxy = y;
                stack[sp++] = idx;
                label[idx] = next;
                while (sp > 0) {
                    int cur = stack[--sp];
                    int cx = cur % gw, cy = cur / gw;
                    area++;
                    minx = Math.min(minx, cx); maxx = Math.max(maxx, cx); miny = Math.min(miny, cy); maxy = Math.max(maxy, cy);
                    int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] d : nb) {
                        int nx = cx + d[0], ny = cy + d[1];
                        if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue;
                        int ni = ny * gw + nx;
                        if (label[ni] == 0 && alpha(rgba, nx * 4, ny * 4) >= 128) { label[ni] = next; stack[sp++] = ni; }
                    }
                }
                comps.add(new int[]{next, area, minx, miny, maxx, maxy});
            }
            comps.sort((a, b) -> b[1] - a[1]);
            int minArea = (int) (gw * gh * 0.02);
            List<int[]> keep = new ArrayList<>();
            for (int[] c : comps) if (c[1] >= minArea && keep.size() < 3) keep.add(c);
            keep.sort(Comparator.comparingInt(c -> c[2]));
            // map image -> output frame (cover fit, centred crop)
            double sc = Math.max(W / (double) iw, H / (double) ih);
            double ox = (iw * sc - W) / 2, oy = (ih * sc - H) / 2;
            int n = 0;
            for (int[] c : keep) {
                int x0 = Math.max(0, c[2] * 4 - 6), y0 = Math.max(0, c[3] * 4 - 6);
                int x1 = Math.min(iw, (c[4] + 1) * 4 + 6), y1 = Math.min(ih, (c[5] + 1) * 4 + 6);
                BufferedImage part = new BufferedImage(x1 - x0, y1 - y0, BufferedImage.TYPE_INT_ARGB);
                for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                    int gi = Math.min(gh - 1, y / 4) * gw + Math.min(gw - 1, x / 4);
                    int lab = label[gi];
                    // keep this object's pixels (and soft edges next to it), drop the other objects
                    if (lab == c[0] || lab == 0) part.setRGB(x - x0, y - y0, rgba.getRGB(x, y));
                }
                int pw = Math.max(2, (int) Math.round(part.getWidth() * sc)), ph = Math.max(2, (int) Math.round(part.getHeight() * sc));
                BufferedImage scaled = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
                var g = scaled.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(part, 0, 0, pw, ph, null);
                g.dispose();
                Path png = dir.resolve("layer-" + (n++) + ".png");
                ImageIO.write(scaled, "png", png.toFile());
                out.add(new Layer(png, (int) Math.round(x0 * sc - ox), (int) Math.round(y0 * sc - oy), pw, ph));
            }
        } catch (Exception e) {
            warnOnce(job, "Object separation failed (" + e.getMessage() + "); scenes animate without lifted objects.");
        }
        return out;
    }

    private static int alpha(BufferedImage img, int x, int y) {
        return (img.getRGB(Math.min(img.getWidth() - 1, x), Math.min(img.getHeight() - 1, y)) >>> 24) & 255;
    }

    private void warnOnce(Job job, String w) {
        if (!job.warnings.contains(w)) job.warnings.add(w);
    }

    // ================================================================== one scene

    private void renderScene(Path image, List<Layer> layers, StoryboardService.TimedNarration tn, double speech, double dur,
                             boolean showCaptions, int W, int H, Path dir, Path out) throws Exception {
        List<Double> starts = tn.segmentStarts();
        int segs = starts.size();
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y"));
        StringBuilder f = new StringBuilder();
        args.addAll(List.of("-loop", "1", "-framerate", "30", "-t", fmt(dur), "-i", image.toString()));
        f.append("[0:v]scale=").append(W).append(':').append(H).append(":force_original_aspect_ratio=increase,crop=")
                .append(W).append(':').append(H).append(",setsar=1,format=rgba[b0]");
        int input = 1;
        String base = "b0";
        // objects lift one by one, each when its spoken segment starts
        for (int k = 0; k < layers.size(); k++) {
            Layer l = layers.get(k);
            double t = segs >= layers.size() + 1 ? starts.get(k + 1) : speech * (k + 1) / (layers.size() + 1.0);
            if (segs == 1 && layers.size() == 1) t = Math.min(0.8, speech * 0.3);
            t = Math.max(0.4, Math.min(t, Math.max(0.4, speech - 0.5)));
            args.addAll(List.of("-loop", "1", "-framerate", "30", "-t", fmt(dur), "-i", l.png().toString()));
            String grow = "min(1,max(0,(t-" + fmt(t) + ")/0.5))";
            f.append(";[").append(input).append(":v]format=rgba,scale=w='").append(l.w()).append("*(1+").append(LIFT).append('*')
                    .append(grow).append(")':h=-1:eval=frame,fade=t=in:st=").append(fmt(t)).append(":d=0.45:alpha=1[l").append(k).append(']');
            f.append(";[").append(base).append("][l").append(k).append("]overlay=x='").append(l.x() + l.w() / 2).append("-w/2':y='")
                    .append(l.y() + l.h() / 2).append("-h/2-6*sin((t-").append(fmt(t)).append(")*1.7)*gte(t,").append(fmt(t))
                    .append(")':eval=frame:format=auto[b").append(k + 1).append(']');
            base = "b" + (k + 1);
            input++;
        }
        // the spoken line appears as a subtitle card while it is spoken
        if (showCaptions) {
            for (int k = 0; k < segs; k++) {
                String text = tn.segmentTexts().get(k);
                if (text == null || text.isBlank()) continue;
                double a = starts.get(k), b = k + 1 < segs ? starts.get(k + 1) : speech + 0.2;
                if (b - a < 0.4) continue;
                Path png = dir.resolve("caption-" + k + ".png");
                ImageIO.write(captions.caption(text, tn.speakers().get(k), W - 160, W > H ? 44 : 50), "png", png.toFile());
                args.addAll(List.of("-loop", "1", "-framerate", "30", "-t", fmt(dur), "-i", png.toString()));
                f.append(";[").append(input).append(":v]format=rgba,fade=t=in:st=").append(fmt(a)).append(":d=0.25:alpha=1,fade=t=out:st=")
                        .append(fmt(Math.max(a + 0.3, b - 0.25))).append(":d=0.25:alpha=1[c").append(k).append(']');
                f.append(";[").append(base).append("][c").append(k).append("]overlay=x=(W-w)/2:y=H-h-").append(W > H ? 70 : 220)
                        .append(":enable='between(t,").append(fmt(a)).append(',').append(fmt(b)).append(")'[q").append(k).append(']');
                base = "q" + k;
                input++;
            }
        }
        // slow push-in on the whole frame, applied once (no zoom restarts)
        f.append(";[").append(base).append("]scale=w='2*trunc(").append(W).append("*(1+0.03*t/").append(fmt(dur))
                .append(")/2)':h=-2:eval=frame:flags=bicubic,crop=").append(W).append(':').append(H).append(",setsar=1,format=yuv420p[v]");
        args.addAll(List.of("-i", tn.wav().toString()));
        f.append(";[").append(input).append(":a]aresample=48000,aformat=channel_layouts=stereo,apad[a]");
        args.addAll(List.of("-filter_complex", f.toString(), "-map", "[v]", "-map", "[a]", "-t", fmt(dur),
                "-r", "30", "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2", out.toString()));
        ffmpeg(args);
    }

    // ================================================================== join + music

    /** Cross-dissolve between scenes (no black frames). */
    private void join(List<Path> clips, List<Double> durations, Path out) throws Exception {
        if (clips.size() == 1) {
            Files.copy(clips.get(0), out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y"));
        for (Path c : clips) args.addAll(List.of("-i", c.toString()));
        StringBuilder f = new StringBuilder();
        String v = "0:v", a = "0:a";
        double offset = 0;
        for (int i = 1; i < clips.size(); i++) {
            offset += durations.get(i - 1) - CROSSFADE;
            String vo = i == clips.size() - 1 ? "v" : "v" + i, ao = i == clips.size() - 1 ? "a" : "a" + i;
            f.append('[').append(v).append("][").append(i).append(":v]xfade=transition=fade:duration=").append(fmt(CROSSFADE))
                    .append(":offset=").append(fmt(offset)).append('[').append(vo).append("];");
            f.append('[').append(a).append("][").append(i).append(":a]acrossfade=d=").append(fmt(CROSSFADE)).append('[').append(ao).append("];");
            v = vo;
            a = ao;
        }
        f.setLength(f.length() - 1);
        args.addAll(List.of("-filter_complex", f.toString(), "-map", "[v]", "-map", "[a]",
                "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", out.toString()));
        ffmpeg(args);
    }

    private void mixMusic(Path video, Path music, Path out) throws Exception {
        double total = media.probeDurationSeconds(video);
        String fadeOut = fmt(Math.max(0, total - 1.0));
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-i", video.toString()));
        String filter;
        if (music != null && Files.isRegularFile(music)) {
            args.addAll(List.of("-stream_loop", "-1", "-i", music.toString()));
            filter = "[0:v]fade=t=in:st=0:d=0.6,fade=t=out:st=" + fadeOut + ":d=1.0[v];"
                    + "[1:a]aresample=48000,aformat=channel_layouts=stereo,volume=0.14[m];"
                    + "[0:a]asplit=2[voice][key];[m][key]sidechaincompress=threshold=0.03:ratio=8:attack=20:release=450[duck];"
                    + "[voice][duck]amix=inputs=2:duration=first:normalize=0,afade=t=out:st=" + fadeOut + ":d=1.0[a]";
        } else {
            filter = "[0:v]fade=t=in:st=0:d=0.6,fade=t=out:st=" + fadeOut + ":d=1.0[v];[0:a]afade=t=out:st=" + fadeOut + ":d=1.0[a]";
        }
        args.addAll(List.of("-filter_complex", filter, "-map", "[v]", "-map", "[a]", "-t", fmt(total),
                "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out.toString()));
        ffmpeg(args);
    }

    // ================================================================== publish

    private void publish(Episode ep, Path video, String srt, Job job) throws IOException {
        List<Asset> previous = assets.findByEpisodeIdAndAssetType(ep.getId(), AssetType.VIDEO);
        int version = previous.stream().mapToInt(Asset::getVersion).max().orElse(0) + 1;
        previous.forEach(x -> x.setActive(false));
        assets.saveAll(previous);
        Asset a = new Asset();
        a.setEpisodeId(ep.getId());
        a.setAssetType(AssetType.VIDEO);
        a.setFilePath(video.toString());
        a.setVersion(version);
        a.setActive(true);
        a.setProvider("classic-animated-storyboard");
        a.setDurationSeconds(job.seconds);
        assets.save(a);
        if (!srt.isBlank()) {
            Path sub = storage.store(ep.getProjectId() + "/" + ep.getId() + "/subtitles/classic-" + job.id + ".srt",
                    srt.getBytes(StandardCharsets.UTF_8));
            List<Asset> oldSubs = assets.findByEpisodeIdAndAssetType(ep.getId(), AssetType.SUBTITLE);
            oldSubs.forEach(x -> x.setActive(false));
            assets.saveAll(oldSubs);
            Asset s = new Asset();
            s.setEpisodeId(ep.getId());
            s.setAssetType(AssetType.SUBTITLE);
            s.setFilePath(sub.toString());
            s.setVersion(version);
            s.setActive(true);
            s.setProvider("classic-animated-storyboard");
            assets.save(s);
        }
        ep.setStatus(EpisodeStatus.PRODUCTION_COMPLETE);
        episodes.save(ep);
    }

    // ================================================================== helpers

    private static void ffmpeg(List<String> args) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("ffmpeg timed out");
        }
        if (p.exitValue() != 0) {
            String s = new String(out, StandardCharsets.UTF_8);
            StringBuilder cause = new StringBuilder();
            for (String line : s.split("\\R")) {
                String l = line.toLowerCase(Locale.ROOT);
                if ((l.contains("error") || l.contains("invalid") || l.contains("cannot") || l.contains("no such file") || l.contains("failed"))
                        && !l.contains("could not open encoder before eof") && !l.contains("terminating thread") && !l.contains("task finished")) {
                    if (cause.length() > 0) cause.append(" | ");
                    cause.append(line.trim());
                    if (cause.length() > 600) break;
                }
            }
            throw new IllegalStateException("ffmpeg failed: " + (cause.length() > 0 ? cause : s.substring(Math.max(0, s.length() - 400))));
        }
    }

    private static String srtTime(double sec) {
        long ms = Math.max(0, Math.round(sec * 1000));
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
