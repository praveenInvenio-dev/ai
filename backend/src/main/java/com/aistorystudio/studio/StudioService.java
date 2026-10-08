package com.aistorystudio.studio;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.domain.Asset;
import com.aistorystudio.domain.enums.AssetType;
import com.aistorystudio.h3.H3SceneRenderer;
import com.aistorystudio.h3.IndicSpeech;
import com.aistorystudio.provider.ComfyUIVideoProvider;
import com.aistorystudio.provider.MediaProcessor;
import com.aistorystudio.provider.TextToSpeechProvider;
import com.aistorystudio.repository.AssetRepository;
import com.aistorystudio.sequence.VideoSequenceService;
import com.aistorystudio.service.StoryTranslationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.aistorystudio.provider.StorageProvider;
import com.aistorystudio.sequence.ClipMerger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Motion &amp; Effects Studio (Higgsfield-style tools, all local):
 * <ul>
 *   <li><b>Motion control</b> - Wan 2.2 Animate "move" mode: the character in an image performs
 *       the movement of a driving video (dance, action, gesture). DWPose skeleton -> WanAnimateToVideo.</li>
 *   <li><b>Upscale</b> - AI (RealESRGAN per frame in ComfyUI) or FAST (FFmpeg lanczos + sharpen),
 *       optional smooth 24 fps. Turns 480x832 H3/Wan clips into 1080x1920 deliverables.</li>
 *   <li><b>Reframe</b> - 9:16 / 16:9 / 1:1 / 4:5 with blurred fill, centre crop or bars.</li>
 * </ul>
 * Camera presets live in {@code studio/camera-presets.json} and are used by Video Generation.
 */
@Service
public class StudioService {

    private static final Logger log = LoggerFactory.getLogger(StudioService.class);
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "mov", "webm", "mkv", "m4v");
    private static final String WAN_NEGATIVE = "blurry, low quality, deformed body, extra limbs, distorted face, flicker, static, subtitles, text, watermark";

    public record CameraPreset(String id, String name, String category, String prompt) { }

    public record Status(boolean comfyAvailable, String comfyReason, boolean motionControlConfigured,
                         double motionMaxSeconds, double aiUpscaleMaxSeconds) { }

    public record JobView(UUID id, StudioJob.Tool tool, StudioJob.Status status, String stage, String errorMessage,
                          Double resultSeconds, String summary, String resultUrl, String report, String resultExtension) { }

    private final ComfyUIVideoProvider comfy;
    private final StorageProvider storage;
    private final Map<UUID, StudioJob> jobs = new ConcurrentHashMap<>();
    private final List<CameraPreset> presets;
    private final Duration retention;
    private final String mcModel, mcLora, mcTextEncoder, mcVae, mcClipVision;
    private final int mcWidth, mcHeight, mcSteps, mcFps;
    private final double mcCfg, mcMaxSeconds, upscaleMaxSeconds;
    private final String upscaleModel;
    private final AssetRepository assetRepository;
    private final ProviderGateway gateway;
    private final MediaProcessor media;
    private final H3SceneRenderer h3;
    private final StoryTranslationService translator;
    private final VideoSequenceService sequences;
    private final String workerBaseUrl;
    private final double mcMaxTotalSeconds;
    private final ObjectMapper mapper = new ObjectMapper();

    public StudioService(ComfyUIVideoProvider comfy, StorageProvider storage,
                         @Value("${studio.motion-studio.retention-hours:48}") long retentionHours,
                         @Value("${studio.motion-studio.motion.model:Wan2_2-Animate-14B_fp8_e4m3fn_scaled_KJ.safetensors}") String mcModel,
                         @Value("${studio.motion-studio.motion.lora:lightx2v_I2V_14B_480p_cfg_step_distill_rank64_bf16.safetensors}") String mcLora,
                         @Value("${studio.motion-studio.motion.text-encoder:umt5_xxl_fp8_e4m3fn_scaled.safetensors}") String mcTextEncoder,
                         @Value("${studio.motion-studio.motion.vae:wan_2.1_vae.safetensors}") String mcVae,
                         @Value("${studio.motion-studio.motion.clip-vision:clip_vision_h.safetensors}") String mcClipVision,
                         @Value("${studio.motion-studio.motion.width:480}") int mcWidth,
                         @Value("${studio.motion-studio.motion.height:832}") int mcHeight,
                         @Value("${studio.motion-studio.motion.steps:6}") int mcSteps,
                         @Value("${studio.motion-studio.motion.cfg:1.0}") double mcCfg,
                         @Value("${studio.motion-studio.motion.fps:16}") int mcFps,
                         @Value("${studio.motion-studio.motion.max-seconds:4.8}") double mcMaxSeconds,
                         @Value("${studio.motion-studio.upscale.model:${studio.comfyui.qwen.upscale-model:RealESRGAN_x2.pth}}") String upscaleModel,
                         @Value("${studio.motion-studio.upscale.ai-max-seconds:30}") double upscaleMaxSeconds,
                         AssetRepository assetRepository, ProviderGateway gateway, MediaProcessor media,
                         H3SceneRenderer h3, StoryTranslationService translator, VideoSequenceService sequences,
                         @Value("${studio.video-editor.worker-base-url:http://video-worker:5010}") String workerBaseUrl,
                         @Value("${studio.motion-studio.motion.max-total-seconds:20}") double mcMaxTotalSeconds) {
        this.assetRepository = assetRepository;
        this.gateway = gateway;
        this.media = media;
        this.h3 = h3;
        this.translator = translator;
        this.sequences = sequences;
        this.workerBaseUrl = workerBaseUrl;
        this.mcMaxTotalSeconds = mcMaxTotalSeconds;
        this.comfy = comfy;
        this.storage = storage;
        this.retention = Duration.ofHours(retentionHours);
        this.mcModel = mcModel; this.mcLora = mcLora; this.mcTextEncoder = mcTextEncoder;
        this.mcVae = mcVae; this.mcClipVision = mcClipVision;
        this.mcWidth = mcWidth; this.mcHeight = mcHeight; this.mcSteps = mcSteps; this.mcCfg = mcCfg;
        this.mcFps = mcFps; this.mcMaxSeconds = mcMaxSeconds;
        this.upscaleModel = upscaleModel; this.upscaleMaxSeconds = upscaleMaxSeconds;
        this.presets = loadPresets();
    }

    // ================================================================== info

    public List<CameraPreset> cameraPresets() { return presets; }

    public Status status() {
        String reason = comfy.unavailableReason();
        return new Status(reason == null, reason, !mcModel.isBlank() && !mcClipVision.isBlank(), mcMaxTotalSeconds, upscaleMaxSeconds);
    }

    public JobView view(UUID id) {
        StudioJob j = require(id);
        String url = j.getResultPath() == null ? null : "/api/studio/jobs/" + id + "/video";
        return new JobView(j.getId(), j.getTool(), j.getStatus(), j.getStage(), j.getErrorMessage(),
                j.getResultSeconds(), j.getSummary(), url, j.getReport(), j.getResultExtension());
    }

    public List<JobView> list() {
        return jobs.values().stream().sorted(Comparator.comparing(StudioJob::getCreatedAt).reversed())
                .map(j -> view(j.getId())).toList();
    }

    public String resultExtension(UUID id) {
        return require(id).getResultExtension();
    }

    public Path result(UUID id) {
        StudioJob j = require(id);
        if (j.getStatus() != StudioJob.Status.SUCCEEDED || j.getResultPath() == null) {
            throw new IllegalStateException("The studio result is not ready.");
        }
        return Path.of(j.getResultPath());
    }

    // ================================================================== create

    /** Stores inputs and returns the job; the controller then calls the matching *Async method. */
    public StudioJob create(StudioJob.Tool tool, MultipartFile image, MultipartFile video, UUID sourceJobId) {
        return create(tool, image, video, sourceJobId, null, null);
    }

    /**
     * Source video priority: upload, earlier studio result, a story's final video (episodeId),
     * a Story Video Production result (sequenceId).
     */
    public StudioJob create(StudioJob.Tool tool, MultipartFile image, MultipartFile video, UUID sourceJobId,
                            UUID episodeId, UUID sequenceId) {
        StudioJob job = new StudioJob(tool);
        jobs.put(job.getId(), job);
        try {
            Path dir = workDir(job);
            Files.createDirectories(dir);
            if (image != null && !image.isEmpty()) {
                Files.write(dir.resolve("input-image." + ext(image, IMAGE_EXT, "image")), image.getBytes());
            }
            if (video != null && !video.isEmpty()) {
                Files.write(dir.resolve("input-video." + ext(video, VIDEO_EXT, "video")), video.getBytes());
            } else if (sourceJobId != null) {
                // chain: upscale / reframe the result of an earlier studio job
                Files.copy(result(sourceJobId), dir.resolve("input-video." + require(sourceJobId).getResultExtension()));
            } else if (episodeId != null) {
                Asset a = assetRepository.findByEpisodeIdAndAssetType(episodeId, AssetType.VIDEO).stream()
                        .filter(Asset::isActive).max(Comparator.comparingInt(Asset::getVersion))
                        .orElseThrow(() -> new IllegalArgumentException("This story has no finished video yet. Produce it first."));
                Files.copy(Path.of(a.getFilePath()), dir.resolve("input-video.mp4"));
            } else if (sequenceId != null) {
                Path merged = sequences.mergedPath(sequenceId);
                if (merged == null || !Files.isRegularFile(merged)) throw new IllegalArgumentException("That production has no merged video yet.");
                Files.copy(merged, dir.resolve("input-video.mp4"));
            }
        } catch (IOException e) {
            jobs.remove(job.getId());
            throw new IllegalStateException("Could not store the uploaded files: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            jobs.remove(job.getId());
            throw e;
        }
        if (tool == StudioJob.Tool.MOTION_CONTROL && input(job, "input-image") == null) {
            jobs.remove(job.getId());
            throw new IllegalArgumentException("Motion control needs a character image.");
        }
        if (tool == StudioJob.Tool.BACKGROUND && (input(job, "input-image") != null || input(job, "input-video") != null)) {
            return job;
        }
        if (input(job, "input-video") == null) {
            jobs.remove(job.getId());
            throw new IllegalArgumentException(tool == StudioJob.Tool.MOTION_CONTROL
                    ? "Motion control needs a driving video (the movement to copy)." : "Upload a video or pick an earlier studio result.");
        }
        return job;
    }

    // ================================================================== motion control

    @Async("videoGenerationExecutor")
    public void motionControlAsync(UUID id, String prompt, String orientation, Double seconds) {
        StudioJob job = jobs.get(id);
        if (job == null) return;
        run(job, () -> {
            boolean horizontal = "horizontal".equalsIgnoreCase(orientation);
            int w = horizontal ? Math.max(mcWidth, mcHeight) : Math.min(mcWidth, mcHeight);
            int h = horizontal ? Math.min(mcWidth, mcHeight) : Math.max(mcWidth, mcHeight);
            double total = Math.max(1.0, Math.min(mcMaxTotalSeconds, seconds == null ? mcMaxSeconds : seconds));
            job.setStage("Preparing driving video");
            Path dir = workDir(job);
            Path driving = dir.resolve("driving.mp4");
            ffmpeg(List.of("ffmpeg", "-nostdin", "-y", "-i", input(job, "input-video").toString(), "-t", fmt(total),
                    "-vf", "fps=" + mcFps + ",scale=" + w + ":" + h + ":force_original_aspect_ratio=increase,crop=" + w + ":" + h + ",setsar=1",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "16", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-ar", "48000", "-ac", "2", driving.toString()));
            double drivingSeconds = ClipMerger.probeDuration(driving);
            // Long clips: chained ~4.8 s windows; each continues from the previous window's frames.
            int windows = (int) Math.ceil(drivingSeconds / mcMaxSeconds - 0.1);
            windows = Math.max(1, windows);
            List<Path> parts = new ArrayList<>();
            Path previous = null;
            for (int k = 0; k < windows; k++) {
                double start = k * mcMaxSeconds;
                double len = Math.min(mcMaxSeconds, drivingSeconds - start);
                if (len < 0.5) break;
                Path chunk = dir.resolve(String.format(Locale.ROOT, "driving-%02d.mp4", k));
                ffmpeg(List.of("ffmpeg", "-nostdin", "-y", "-ss", fmt(start), "-i", driving.toString(), "-t", fmt(len),
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "16", "-pix_fmt", "yuv420p", "-an", chunk.toString()));
                int frames = (int) Math.floor(ClipMerger.probeDuration(chunk) * mcFps);
                int length = Math.max(5, ((frames - 1) / 4) * 4 + 1); // WanAnimate wants 4k+1 frames
                Map<String, String> text = motionText(prompt);
                Map<String, Number> numeric = new LinkedHashMap<>();
                numeric.put("{{WIDTH}}", w);
                numeric.put("{{HEIGHT}}", h);
                numeric.put("{{LENGTH}}", length);
                numeric.put("{{STEPS}}", mcSteps);
                numeric.put("{{CFG}}", mcCfg);
                numeric.put("{{FPS}}", mcFps);
                numeric.put("{{SEED}}", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
                Map<String, Path> uploads = new LinkedHashMap<>();
                uploads.put("{{CHARACTER_IMAGE}}", input(job, "input-image"));
                uploads.put("{{DRIVING_VIDEO}}", chunk);
                if (previous != null) uploads.put("{{PREVIOUS_CLIP}}", previous);
                job.setStage("Wan 2.2 Animate window " + (k + 1) + "/" + windows + " (" + length + " frames)");
                byte[] video = comfy.runStudioWorkflow(previous == null ? "studio-motion-control" : "studio-motion-control-continue",
                        text, numeric, uploads);
                Path part = dir.resolve(String.format(Locale.ROOT, "part-%02d.mp4", k));
                Files.write(part, video);
                parts.add(part);
                previous = part;
            }
            job.setStage("Joining windows + original audio");
            Path joined = dir.resolve("motion.mp4");
            Path list = dir.resolve("parts.txt");
            StringBuilder sb = new StringBuilder();
            for (Path p : parts) sb.append("file '").append(p.toAbsolutePath()).append("'\n");
            Files.writeString(list, sb.toString());
            ffmpeg(List.of("ffmpeg", "-nostdin", "-y", "-f", "concat", "-safe", "0", "-i", list.toString(), "-i", driving.toString(),
                    "-map", "0:v:0", "-map", "1:a?", "-c:v", "libx264", "-preset", "veryfast", "-crf", "17", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", joined.toString()));
            finish(job, Files.readAllBytes(joined), "motion control " + w + "x" + h + ", " + parts.size() + " window(s), "
                    + fmt1(drivingSeconds) + " s @ " + mcFps + " fps");
        });
    }

    private Map<String, String> motionText(String prompt) {
        Map<String, String> text = new LinkedHashMap<>();
        text.put("{{MC_MODEL}}", mcModel);
        text.put("{{MC_LORA}}", mcLora);
        text.put("{{MC_TEXT_ENCODER}}", mcTextEncoder);
        text.put("{{MC_VAE}}", mcVae);
        text.put("{{MC_CLIP_VISION}}", mcClipVision);
        text.put("{{POSITIVE_PROMPT}}", prompt == null || prompt.isBlank()
                ? "The character from the reference image performs exactly the same movements as the person in the driving video, natural body motion, consistent identity and outfit."
                : prompt.trim());
        text.put("{{NEGATIVE_PROMPT}}", WAN_NEGATIVE);
        return text;
    }

    // ================================================================== background removal

    /** background: transparent | color | blur. quality: fast | general | best. Runs in video-worker (rembg). */
    @Async("videoGenerationExecutor")
    public void backgroundAsync(UUID id, String background, String color, String quality) {
        StudioJob job = jobs.get(id);
        if (job == null) return;
        run(job, () -> {
            Path image = input(job, "input-image");
            Path video = input(job, "input-video");
            boolean isImage = video == null;
            String bg = background == null ? "transparent" : background.toLowerCase(Locale.ROOT);
            String ext = isImage ? "png" : ("transparent".equals(bg) ? "webm" : "mp4");
            Path out = workDir(job).resolve("cutout." + ext);
            job.setStage(isImage ? "Removing background" : "Removing background frame by frame (CPU, can take minutes)");
            var body = mapper.createObjectNode();
            body.put("source", (isImage ? image : video).toAbsolutePath().toString());
            body.put("target", out.toAbsolutePath().toString());
            body.put("kind", isImage ? "image" : "video");
            body.put("background", bg);
            body.put("color", color == null ? "#00ff00" : color);
            body.put("quality", quality == null ? "general" : quality);
            body.put("maxSeconds", 15);
            body.put("fps", 24);
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(workerBaseUrl + "/api/remove-background"))
                    .timeout(Duration.ofMinutes(60)).header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var resp = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                String err = resp.body();
                try { err = mapper.readTree(resp.body()).path("error").asText(err); } catch (Exception ignored) { }
                throw new IllegalStateException("Background removal failed: " + err);
            }
            job.setResultExtension(ext);
            finish(job, Files.readAllBytes(out), "background removed (" + bg + ", " + (quality == null ? "general" : quality) + ")"
                    + (isImage ? " - image" : " - video, max 15 s"));
        });
    }

    // ================================================================== dub / re-voice

    /**
     * Re-voice a video in another language.
     * REVOICE: keep the picture, new IndicF5 voice track (original audio ducked or muted). Lips are not re-synced.
     * REANIMATE: first frame + translated lines through the shared H3 scene renderer -> new clip whose
     * speech (and lips for character lines) match the new language.
     */
    @Async("videoGenerationExecutor")
    public void dubAsync(UUID id, String script, String sourceLanguage, String targetLanguage, String mode,
                         String speechEngine, double originalVolume, String voice) {
        StudioJob job = jobs.get(id);
        if (job == null) return;
        run(job, () -> {
            if (script == null || script.isBlank()) throw new IllegalArgumentException("Enter the lines to speak (one per line, optional 'Name: line').");
            List<H3SceneRenderer.Line> lines = new ArrayList<>(H3SceneRenderer.linesFromText(null, script));
            String target = targetLanguage == null || targetLanguage.isBlank() ? sourceLanguage : targetLanguage;
            if (target != null && sourceLanguage != null && !target.equalsIgnoreCase(sourceLanguage)) {
                job.setStage("Translating to " + target);
                List<String> t = translator.translateLines(lines.stream().map(H3SceneRenderer.Line::text).toList(), sourceLanguage, target, null);
                List<H3SceneRenderer.Line> out = new ArrayList<>();
                for (int i = 0; i < lines.size(); i++) {
                    H3SceneRenderer.Line l = lines.get(i);
                    out.add(new H3SceneRenderer.Line(l.speaker(), t.get(i), l.delivery(), l.voiceOver(), l.pauseSeconds(), l.voice()));
                }
                lines = out;
            }
            StringBuilder report = new StringBuilder();
            for (H3SceneRenderer.Line l : lines) report.append(l.voiceOver() ? "" : l.speaker() + ": ").append(l.text()).append('\n');
            job.setReport(report.toString().trim());
            Path src = input(job, "input-video");
            Path dir = workDir(job);
            if ("REANIMATE".equalsIgnoreCase(mode)) {
                job.setStage("Re-animating with H3 (" + (speechEngine == null ? "H3" : speechEngine) + " speech)");
                Path first = ClipMerger.extractLastFrame(firstFrameClip(src, dir), dir.resolve("first.png"));
                var spec = new H3SceneRenderer.SceneSpec("Continue the scene naturally from this frame; the characters act out the lines.",
                        null, null, null, target, lines, null, null, ClipMerger.probeDuration(src), "studio-dub-" + id,
                        H3SceneRenderer.SpeechEngine.parse(speechEngine));
                var r = h3.render(spec, new H3SceneRenderer.Options(first, 0, 0, dir.resolve("h3"), () -> false));
                finish(job, Files.readAllBytes(r.video()), "re-animated in " + target + " (" + r.shots() + " shot(s))");
                return;
            }
            // REVOICE
            IndicSpeech.Language lang = IndicSpeech.resolve(target, report.toString());
            List<String> pool = IndicSpeech.indicVoices(lang);
            if (pool.isEmpty() && (voice == null || voice.isBlank())) {
                throw new IllegalStateException("No IndicF5 voice for " + lang.name() + ". Use Re-animate (H3 speech) instead.");
            }
            Map<String, String> voices = new LinkedHashMap<>();
            int ci = 0;
            List<Path> wavs = new ArrayList<>();
            int i = 0;
            for (H3SceneRenderer.Line l : lines) {
                String v = voices.get(l.speaker());
                if (v == null) {
                    v = voice != null && !voice.isBlank() && voices.isEmpty() ? voice
                            : (l.voiceOver() ? pool.get(0) : pool.get((1 + ci++) % pool.size()));
                    voices.put(l.speaker(), v);
                }
                job.setStage("Voicing line " + (i + 1) + "/" + lines.size() + " (" + v + ")");
                IndicSpeech.Prepared prepared = IndicSpeech.prepare(l.text(), lang);
                var tts = gateway.synthesizeIndicVoice(new TextToSpeechProvider.TtsRequest(prepared.text(), v, lang.name(), 1.0, 1.0));
                byte[] wav = tts.audioBytes();
                try { wav = media.humanizeVoice(wav); } catch (RuntimeException ignored) { }
                Path w = dir.resolve(String.format(Locale.ROOT, "line-%02d.wav", i++));
                Files.write(w, wav);
                wavs.add(w);
            }
            List<String> args = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y"));
            for (Path w : wavs) args.addAll(List.of("-i", w.toString()));
            StringBuilder g = new StringBuilder();
            for (int k = 0; k < wavs.size(); k++) {
                g.append('[').append(k).append(":a]aresample=48000,aformat=channel_layouts=stereo,apad=pad_dur=")
                        .append(k == wavs.size() - 1 ? "0" : "0.3").append("[s").append(k).append("];");
            }
            for (int k = 0; k < wavs.size(); k++) g.append("[s").append(k).append(']');
            g.append("concat=n=").append(wavs.size()).append(":v=0:a=1[out]");
            Path voiceTrack = dir.resolve("voice.wav");
            args.addAll(List.of("-filter_complex", g.toString(), "-map", "[out]", voiceTrack.toString()));
            ffmpeg(args);
            double videoSec = ClipMerger.probeDuration(src);
            double voiceSec = ClipMerger.probeDuration(voiceTrack);
            // A little faster speech is fine (<= 1.2x); beyond that the picture holds its last frame.
            double tempo = voiceSec > videoSec ? Math.min(1.2, voiceSec / Math.max(0.5, videoSec - 0.2)) : 1.0;
            double outSec = Math.max(videoSec, voiceSec / tempo + 0.3);
            job.setStage("Mixing new voice over the video");
            Path out = dir.resolve("dubbed.mp4");
            String vol = fmt(Math.max(0, Math.min(1, originalVolume)));
            String filter = "[0:v]tpad=stop_mode=clone:stop_duration=" + fmt(Math.max(0, outSec - videoSec) + 0.5) + "[v];"
                    + "[1:a]atempo=" + fmt(tempo) + ",apad[voice];"
                    + "[0:a]aresample=48000,aformat=channel_layouts=stereo,volume=" + vol + ",apad[orig];"
                    + "[voice]asplit=2[vm][vk];[orig][vk]sidechaincompress=threshold=0.03:ratio=10:attack=15:release=350[duck];"
                    + "[vm][duck]amix=inputs=2:duration=longest:normalize=0,loudnorm=I=-16:TP=-1.5:LRA=11[a]";
            boolean hasAudio = hasAudio(src);
            if (!hasAudio) filter = "[0:v]tpad=stop_mode=clone:stop_duration=" + fmt(Math.max(0, outSec - videoSec) + 0.5) + "[v];"
                    + "[1:a]atempo=" + fmt(tempo) + ",apad,loudnorm=I=-16:TP=-1.5:LRA=11[a]";
            ffmpeg(List.of("ffmpeg", "-nostdin", "-y", "-i", src.toString(), "-i", voiceTrack.toString(),
                    "-filter_complex", filter, "-map", "[v]", "-map", "[a]", "-t", fmt(outSec),
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-movflags", "+faststart", out.toString()));
            finish(job, Files.readAllBytes(out), "re-voiced in " + lang.tag() + " with " + voices.values()
                    + (tempo > 1.0 ? String.format(Locale.ROOT, ", speech %.2fx", tempo) : "")
                    + " - lips not re-synced (use Re-animate for that)");
        });
    }

    private Path firstFrameClip(Path src, Path dir) throws IOException, InterruptedException {
        Path c = dir.resolve("first-frame.mp4");
        ffmpeg(List.of("ffmpeg", "-nostdin", "-y", "-i", src.toString(), "-frames:v", "1", "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", c.toString()));
        return c;
    }

    // ================================================================== analyze

    /** Hook / pacing / audio / format checks + an LLM coach report with concrete fixes. */
    @Async("videoGenerationExecutor")
    public void analyzeAsync(UUID id, String platform) {
        StudioJob job = jobs.get(id);
        if (job == null) return;
        run(job, () -> {
            Path src = input(job, "input-video");
            job.setStage("Measuring");
            double dur = ClipMerger.probeDuration(src);
            int[] dims = probeSize(src);
            String cuts = capture(List.of("ffmpeg", "-nostdin", "-i", src.toString(), "-vf", "select='gt(scene,0.3)',showinfo", "-an", "-f", "null", "-"));
            List<Double> cutTimes = new ArrayList<>();
            var cm = java.util.regex.Pattern.compile("pts_time:([0-9.]+)").matcher(cuts);
            while (cm.find()) cutTimes.add(Double.parseDouble(cm.group(1)));
            boolean hasAudio = hasAudio(src);
            Double lufs = null;
            boolean silentStart = false;
            if (hasAudio) {
                String loud = capture(List.of("ffmpeg", "-nostdin", "-i", src.toString(), "-af", "ebur128", "-f", "null", "-"));
                var lm = java.util.regex.Pattern.compile("I:\\s*(-?[0-9.]+) LUFS").matcher(loud);
                while (lm.find()) lufs = Double.parseDouble(lm.group(1));
                String sil = capture(List.of("ffmpeg", "-nostdin", "-t", "2", "-i", src.toString(), "-af", "silencedetect=n=-40dB:d=1.0", "-f", "null", "-"));
                silentStart = sil.contains("silence_start: 0");
            }
            double firstCut = cutTimes.isEmpty() ? dur : cutTimes.get(0);
            double cutsPerMin = dur > 0 ? cutTimes.size() / (dur / 60.0) : 0;
            boolean vertical = dims[1] > dims[0];
            // heuristic score (0-100), the LLM adds advice on top
            int score = 50;
            if (dur >= 7 && dur <= 60) score += 10;
            if (vertical) score += 10;
            if (firstCut <= 3.0) score += 10;
            if (cutsPerMin >= 8) score += 10;
            if (lufs != null && lufs > -20 && lufs < -11) score += 5;
            if (silentStart || !hasAudio) score -= 15;
            if (Math.min(dims[0], dims[1]) < 720) score -= 5;
            score = Math.max(0, Math.min(100, score));
            String metrics = String.format(Locale.ROOT,
                    "duration %.1fs, %dx%d (%s), %d cuts (%.1f per min, first change at %.1fs), audio %s%s%s",
                    dur, dims[0], dims[1], vertical ? "vertical" : "horizontal", cutTimes.size(), cutsPerMin, firstCut,
                    hasAudio ? "yes" : "NO", lufs == null ? "" : String.format(Locale.ROOT, ", loudness %.1f LUFS", lufs),
                    silentStart ? ", silent first second" : "");
            job.setStage("Writing advice");
            String advice;
            try {
                String sys = "You are a short-form video coach for Indian creators (Instagram Reels, YouTube Shorts, kids story channels). "
                        + "Given measured facts about a video, give a frank, practical review. Return ONLY JSON: "
                        + "{\"hook\": string, \"pacing\": string, \"audio\": string, \"format\": string, \"fixes\": [string, string, string]}. "
                        + "Each value one or two sentences; fixes are concrete edits doable in this app (upscale, reframe, camera preset, re-voice, shorter scenes).";
                String user = "Platform: " + (platform == null || platform.isBlank() ? "Instagram Reels / YouTube Shorts" : platform)
                        + ". Measured: " + metrics + ". Heuristic score: " + score + "/100.";
                String raw = gateway.llm().generateStructured(sys, user);
                int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
                JsonNode n = mapper.readTree(a >= 0 && b > a ? raw.substring(a, b + 1) : raw);
                StringBuilder r = new StringBuilder();
                r.append("Hook: ").append(n.path("hook").asText("")).append('\n');
                r.append("Pacing: ").append(n.path("pacing").asText("")).append('\n');
                r.append("Audio: ").append(n.path("audio").asText("")).append('\n');
                r.append("Format: ").append(n.path("format").asText("")).append('\n');
                r.append("Fixes:\n");
                for (JsonNode f : n.path("fixes")) r.append(" - ").append(f.asText()).append('\n');
                advice = r.toString().trim();
            } catch (Exception e) {
                advice = "AI advice unavailable (" + e.getMessage() + "). Rules of thumb: show the main character or the payoff in the first 2 s, "
                        + "change the shot every 3-6 s, keep 7-60 s, vertical 1080x1920, loudness around -14 to -16 LUFS, no silent start.";
            }
            job.setReport("Score " + score + "/100\n" + metrics + "\n\n" + advice);
            job.setSummary("score " + score + "/100");
            job.setStage("Done");
            job.setStatus(StudioJob.Status.SUCCEEDED);
        });
    }

    private static boolean hasAudio(Path file) throws IOException, InterruptedException {
        return capture(List.of("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_type",
                "-of", "csv=p=0", file.toString())).trim().startsWith("audio");
    }

    private static String capture(List<String> args) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(30, TimeUnit.MINUTES);
        return out;
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // ================================================================== upscale

    /** Short-side pixels -> label. 480p H3/Wan clips go to any of these. */
    public static int normalizeResolution(Integer r) {
        if (r == null) return 1080;
        if (r <= 720) return 720;
        if (r <= 1080) return 1080;
        if (r <= 1440) return 1440;
        return 2160;
    }

    private static String resolutionLabel(int r) {
        return switch (r) { case 720 -> "720p HD"; case 1440 -> "1440p 2K"; case 2160 -> "2160p 4K"; default -> "1080p Full HD"; };
    }

    /**
     * Upscale to 720p / 1080p / 1440p (2K) / 2160p (4K), keeping orientation (short side = target).
     * AI: RealESRGAN x2 per frame in ComfyUI, then FFmpeg lanczos to the exact size.
     * FAST: FFmpeg lanczos + sharpen only. smooth24: motion-interpolate to 24 fps first.
     */
    @Async("videoGenerationExecutor")
    public void upscaleAsync(UUID id, String mode, boolean smooth24, Integer resolution) {
        StudioJob job = jobs.get(id);
        if (job == null) return;
        run(job, () -> {
            int target = normalizeResolution(resolution);
            Path src = input(job, "input-video");
            if (smooth24) {
                job.setStage("Smoothing motion to 24 fps");
                Path smooth = workDir(job).resolve("smooth.mp4");
                ffmpeg(List.of("ffmpeg", "-nostdin", "-y", "-i", src.toString(),
                        "-vf", "minterpolate=fps=24:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1",
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "16", "-pix_fmt", "yuv420p", "-c:a", "copy", smooth.toString()));
                src = smooth;
            }
            int[] dims = probeSize(src);
            boolean portrait = dims[1] >= dims[0];
            // short side = target, long side follows the source aspect (rounded to even)
            double aspect = (double) Math.max(dims[0], dims[1]) / Math.min(dims[0], dims[1]);
            // H3 renders 480x832 (1.733); snap near-16:9 sources to the standard 16:9 frame
            // (720x1280, 1080x1920, 1440x2560, 2160x3840) with a tiny centre crop.
            if (Math.abs(aspect - 16.0 / 9.0) / (16.0 / 9.0) < 0.05) aspect = 16.0 / 9.0;
            int longSide = (int) Math.round(target * aspect / 2.0) * 2;
            int w = portrait ? target : longSide, h = portrait ? longSide : target;
            boolean ai = !"FAST".equalsIgnoreCase(mode);
            double seconds = ClipMerger.probeDuration(src);
            if (ai && seconds > upscaleMaxSeconds) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "AI upscale is limited to %.0f s per clip (memory). Use FAST mode or trim the clip.", upscaleMaxSeconds));
            }
            Path base = src;
            String how = "fast";
            if (ai && Math.min(dims[0], dims[1]) < target) {
                job.setStage("AI detail pass (RealESRGAN x2) on " + dims[0] + "x" + dims[1]);
                Map<String, String> text = new LinkedHashMap<>();
                text.put("{{UPSCALE_MODEL}}", upscaleModel);
                byte[] video = comfy.runStudioWorkflow("studio-upscale-video", text, Map.of(), Map.of("{{SOURCE_VIDEO}}", src));
                base = workDir(job).resolve("ai-x2.mp4");
                Files.write(base, video);
                how = "AI";
            }
            job.setStage("Scaling to " + resolutionLabel(target) + " (" + w + "x" + h + ")");
            Path out = workDir(job).resolve("upscaled.mp4");
            // AI frames are already sharp: lighter sharpening; 4K gets more bitrate headroom.
            String sharpen = "AI".equals(how) ? "unsharp=3:3:0.3:3:3:0.0" : "unsharp=5:5:0.7:5:5:0.0";
            String crf = target >= 2160 ? "18" : "17";
            ffmpeg(List.of("ffmpeg", "-nostdin", "-y", "-i", base.toString(), "-i", src.toString(),
                    "-map", "0:v:0", "-map", "1:a?",
                    "-vf", "scale=" + w + ":" + h + ":force_original_aspect_ratio=increase:flags=lanczos,crop=" + w + ":" + h + "," + sharpen + ",setsar=1",
                    "-c:v", "libx264", "-preset", target >= 2160 ? "slow" : "medium", "-crf", crf, "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out.toString()));
            finish(job, Files.readAllBytes(out), how + " upscale " + dims[0] + "x" + dims[1] + " -> " + w + "x" + h
                    + " (" + resolutionLabel(target) + ")" + (smooth24 ? ", 24 fps" : ""));
        });
    }

    // ================================================================== reframe

    @Async("videoGenerationExecutor")
    public void reframeAsync(UUID id, String aspect, String mode) {
        StudioJob job = jobs.get(id);
        if (job == null) return;
        run(job, () -> {
            int[] wh = switch (aspect == null ? "9:16" : aspect.trim()) {
                case "16:9" -> new int[]{1920, 1080};
                case "1:1" -> new int[]{1080, 1080};
                case "4:5" -> new int[]{1080, 1350};
                default -> new int[]{1080, 1920};
            };
            int w = wh[0], h = wh[1];
            String m = mode == null ? "BLUR_FILL" : mode.trim().toUpperCase(Locale.ROOT);
            String filter = switch (m) {
                case "CROP" -> "[0:v]scale=" + w + ":" + h + ":force_original_aspect_ratio=increase,crop=" + w + ":" + h + ",setsar=1[v]";
                case "BARS" -> "[0:v]scale=" + w + ":" + h + ":force_original_aspect_ratio=decrease,pad=" + w + ":" + h + ":(ow-iw)/2:(oh-ih)/2:black,setsar=1[v]";
                default -> "[0:v]split[a][b];[a]scale=" + w + ":" + h + ":force_original_aspect_ratio=increase,crop=" + w + ":" + h
                        + ",boxblur=24:2,eq=brightness=-0.06[bg];[b]scale=" + w + ":" + h + ":force_original_aspect_ratio=decrease[fg];"
                        + "[bg][fg]overlay=(W-w)/2:(H-h)/2,setsar=1[v]";
            };
            job.setStage("Reframing to " + aspect + " (" + m.toLowerCase(Locale.ROOT).replace('_', ' ') + ")");
            Path src = input(job, "input-video");
            Path out = workDir(job).resolve("reframed.mp4");
            List<String> args = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-i", src.toString(),
                    "-filter_complex", filter, "-map", "[v]", "-map", "0:a?",
                    "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out.toString()));
            ffmpeg(args);
            finish(job, Files.readAllBytes(out), "reframe " + aspect + " " + m.toLowerCase(Locale.ROOT));
        });
    }

    // ================================================================== helpers

    private interface Work { void run() throws Exception; }

    private void run(StudioJob job, Work work) {
        job.setStatus(StudioJob.Status.RUNNING);
        try {
            work.run();
        } catch (Exception e) {
            log.warn("Studio job {} ({}) failed: {}", job.getId(), job.getTool(), e.getMessage());
            job.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            job.setStage("Failed");
            job.setStatus(StudioJob.Status.FAILED);
        }
    }

    private void finish(StudioJob job, byte[] video, String summary) throws IOException {
        Path stored = storage.store("studio/results/" + job.getId() + "." + job.getResultExtension(), video);
        job.setResultPath(stored.toString());
        try { job.setResultSeconds(ClipMerger.probeDuration(stored)); } catch (RuntimeException ignored) { }
        job.setSummary(summary);
        job.setStage("Done");
        job.setStatus(StudioJob.Status.SUCCEEDED);
    }

    private Path workDir(StudioJob job) {
        return storage.resolve("studio/jobs/" + job.getId());
    }

    private Path input(StudioJob job, String base) {
        try (var files = Files.list(workDir(job))) {
            return files.filter(p -> p.getFileName().toString().startsWith(base + ".")).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private StudioJob require(UUID id) {
        StudioJob j = jobs.get(id);
        if (j == null) throw new IllegalArgumentException("Studio job not found (expired?): " + id);
        return j;
    }

    private static String ext(MultipartFile f, Set<String> allowed, String kind) {
        String name = f.getOriginalFilename() == null ? "" : f.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        String e = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!allowed.contains(e)) throw new IllegalArgumentException("Unsupported " + kind + " type '." + e + "'. Allowed: " + allowed);
        return e;
    }

    private List<CameraPreset> loadPresets() {
        try (InputStream in = new ClassPathResource("studio/camera-presets.json").getInputStream()) {
            return new ObjectMapper().readValue(in, new TypeReference<List<CameraPreset>>() { });
        } catch (IOException e) {
            log.warn("Camera presets not loaded: {}", e.getMessage());
            return List.of();
        }
    }

    private static int[] probeSize(Path video) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                "stream=width,height", "-of", "csv=p=0", video.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor(60, TimeUnit.SECONDS);
        String[] parts = out.split(",");
        return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
    }

    private static void ffmpeg(List<String> args) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("ffmpeg timed out");
        }
        if (p.exitValue() != 0) {
            String s = new String(out, StandardCharsets.UTF_8);
            throw new IllegalStateException("ffmpeg failed: " + (s.length() > 600 ? s.substring(s.length() - 600) : s));
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    @Scheduled(fixedRate = 3_600_000L)
    void cleanup() {
        Instant cutoff = Instant.now().minus(retention);
        jobs.values().removeIf(j -> {
            if (j.getCreatedAt().isAfter(cutoff) || j.getStatus() == StudioJob.Status.RUNNING) return false;
            try (var walk = Files.walk(workDir(j))) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
            } catch (IOException ignored) { }
            try { if (j.getResultPath() != null) Files.deleteIfExists(Path.of(j.getResultPath())); } catch (IOException ignored) { }
            return true;
        });
    }
}
