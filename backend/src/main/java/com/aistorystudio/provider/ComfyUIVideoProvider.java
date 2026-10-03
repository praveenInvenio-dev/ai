package com.aistorystudio.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Real local AI image-to-video generation via ComfyUI's native Wan support
 * (see comfyui-workflows/wan-image-to-video.json for the node graph and its
 * extensive requirements/version notes - read that file before assuming this
 * class works out of the box).
 *
 * Architecturally this mirrors {@link ComfyUIImageProvider} closely
 * (submit/poll/download/cancel over the same ComfyUI HTTP API) rather than
 * introducing a second, different integration pattern - the two providers
 * talk to the same ComfyUI instance, just different workflows and output
 * types.
 *
 * isAvailable() is a REAL check: it requires every one of the video-specific
 * model files to be explicitly configured (not just "enabled=true"), since a
 * Wan workflow with an unconfigured model filename would fail on first use
 * in a much more confusing way than failing this check up front.
 */
@Component
public class ComfyUIVideoProvider implements VideoGenerationProvider {

    private static final Logger log = LoggerFactory.getLogger(ComfyUIVideoProvider.class);

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private final boolean enabled;
    private final String defaultWorkflow;
    private final String textToVideoWorkflow;
    private final String diffusionModel;
    private final String clipModel;
    private final String vaeModel;
    private final String clipVisionModel;
    private final String h3DiffusionModel;
    private final String h3Ref2vaDiffusionModel;
    private final String h3TextEncoder;
    private final String h3VideoVae;
    private final String h3AudioVae;
    private final int h3Steps;
    private final int defaultWidth;
    private final int defaultHeight;
    private final int defaultFps;
    private final int defaultSteps;
    private final double defaultCfg;
    private final String defaultSampler;
    private final String defaultScheduler;
    private final double maxDurationSeconds;
    private final Duration timeout;
    private final Duration queueWait;
    private final long pollIntervalMs;

    // Shared with ComfyUIImageProvider via ComfyUiAccessCoordinator, not a
    // separate semaphore - a separate one guaranteed nothing about what
    // ComfyUI itself does when an image prompt arrives while a video prompt
    // is still executing: it interrupts the in-flight one rather than
    // queueing behind it. Confirmed live - a completed 20-step Wan job got
    // killed during its save step by a concurrent image request. See
    // ComfyUiAccessCoordinator's own comment for the full story.
    private final Semaphore slot;
    private final int h3MinVramMb;

    private final com.aistorystudio.system.ResourceMonitorService resourceMonitor;
    private final int minVramMb;

    public ComfyUIVideoProvider(
            WebClient.Builder webClientBuilder,
            @Value("${studio.comfyui.baseUrl:http://comfyui:8188}") String baseUrl,
            @Value("${studio.animation.local-ai.enabled:false}") boolean enabled,
            @Value("${studio.animation.local-ai.workflow:wan-image-to-video}") String defaultWorkflow,
            // Separate workflow name, not the same file with an omitted field:
            // the graph shape genuinely differs (no LoadImage node, no
            // start_image wiring), and this project's convention is one
            // template file per real graph shape rather than one file trying
            // to serve two shapes via optional fields.
            @Value("${studio.animation.local-ai.text-to-video-workflow:wan-ti2v-5b-text-to-video}") String textToVideoWorkflow,
            @Value("${studio.animation.local-ai.diffusion-model:}") String diffusionModel,
            @Value("${studio.animation.local-ai.minimax-h3-diffusion-model:minimax_h3_fl2va_pruned_int8_convrot.safetensors}") String h3DiffusionModel,
            @Value("${studio.animation.local-ai.minimax-h3-ref2va-diffusion-model:minimax_h3_ref2va_pruned_int8_convrot.safetensors}") String h3Ref2vaDiffusionModel,
            @Value("${studio.animation.local-ai.minimax-h3-text-encoder:qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors}") String h3TextEncoder,
            @Value("${studio.animation.local-ai.minimax-h3-video-vae:minimax_h3_video_vae_fp16.safetensors}") String h3VideoVae,
            @Value("${studio.animation.local-ai.minimax-h3-audio-vae:minimax_h3_audio_vae_fp32.safetensors}") String h3AudioVae,
            @Value("${studio.animation.local-ai.minimax-h3-steps:8}") int h3Steps,
            @Value("${studio.animation.local-ai.clip-model:}") String clipModel,
            @Value("${studio.animation.local-ai.vae-model:}") String vaeModel,
            @Value("${studio.animation.local-ai.clip-vision-model:}") String clipVisionModel,
            @Value("${studio.animation.local-ai.width:832}") int defaultWidth,
            @Value("${studio.animation.local-ai.height:480}") int defaultHeight,
            @Value("${studio.animation.local-ai.fps:16}") int defaultFps,
            @Value("${studio.animation.local-ai.steps:20}") int defaultSteps,
            @Value("${studio.animation.local-ai.cfg:6.0}") double defaultCfg,
            @Value("${studio.animation.local-ai.sampler:uni_pc}") String defaultSampler,
            @Value("${studio.animation.local-ai.scheduler:simple}") String defaultScheduler,
            @Value("${studio.animation.local-ai.max-duration-seconds:6.0}") double maxDurationSeconds,
            @Value("${studio.animation.local-ai.timeout-seconds:1800}") long timeoutSeconds,
            @Value("${studio.animation.local-ai.queue-wait-seconds:3600}") long queueWaitSeconds,
            @Value("${studio.animation.local-ai.poll-interval-ms:3000}") long pollIntervalMs,
            @Value("${studio.animation.local-ai.max-concurrent:1}") int maxConcurrent,
            @Value("${studio.animation.local-ai.min-vram-mb:14000}") int minVramMb,
            @Value("${studio.animation.local-ai.minimax-h3-min-vram-mb:24000}") int h3MinVramMb,
            com.aistorystudio.system.ResourceMonitorService resourceMonitor,
            ComfyUiAccessCoordinator comfyUiAccessCoordinator) {
        this.resourceMonitor = resourceMonitor;
        this.minVramMb = minVramMb;
        this.h3MinVramMb = h3MinVramMb;
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.enabled = enabled;
        this.defaultWorkflow = defaultWorkflow;
        this.textToVideoWorkflow = textToVideoWorkflow;
        this.diffusionModel = diffusionModel;
        this.h3DiffusionModel = h3DiffusionModel;
        this.h3Ref2vaDiffusionModel = h3Ref2vaDiffusionModel;
        this.h3TextEncoder = h3TextEncoder;
        this.h3VideoVae = h3VideoVae;
        this.h3AudioVae = h3AudioVae;
        this.h3Steps = h3Steps;
        this.clipModel = clipModel;
        this.vaeModel = vaeModel;
        this.clipVisionModel = clipVisionModel;
        this.defaultWidth = defaultWidth;
        this.defaultHeight = defaultHeight;
        this.defaultFps = defaultFps;
        this.defaultSteps = defaultSteps;
        this.defaultCfg = defaultCfg;
        this.defaultSampler = defaultSampler;
        this.defaultScheduler = defaultScheduler;
        this.maxDurationSeconds = maxDurationSeconds;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.queueWait = Duration.ofSeconds(queueWaitSeconds);
        this.pollIntervalMs = Math.max(500, pollIntervalMs);
        // maxConcurrent above is intentionally no longer used to size this -
        // see ComfyUiAccessCoordinator's comment for why a per-provider
        // concurrency count was never actually safe against ComfyUI's real
        // one-prompt-at-a-time behavior. The binding is left in place so an
        // existing LOCAL_AI_ANIMATION_MAX_CONCURRENT in someone's .env
        // doesn't become an unrecognized property; it's just inert now.
        this.slot = comfyUiAccessCoordinator.slot();
    }

    @Override
    public boolean isAvailable() {
        return unavailableReason() == null;
    }

    @Override
    public String unavailableReason() {
        if (!enabled) {
            return "Local AI video generation is disabled (studio.animation.local-ai.enabled=false).";
        }
        boolean wanConfigured = !blank(diffusionModel) && !blank(clipModel) && !blank(vaeModel);
        boolean h3Configured = !blank(h3DiffusionModel) && !blank(h3TextEncoder) && !blank(h3VideoVae) && !blank(h3AudioVae);
        boolean h3VoiceConfigured = !blank(h3Ref2vaDiffusionModel) && !blank(h3TextEncoder) && !blank(h3VideoVae) && !blank(h3AudioVae);
        if (h3Configured && !h3VoiceConfigured) { log.warn("MiniMax H3 base workflow is configured but H3 Ref2VA voice-reference model is not configured."); }
        if (!wanConfigured && !h3Configured) {
            return "No local video model configuration is complete (Wan 2.2 or MiniMax H3).";
        }
        // The legacy Wan image-to-video graph uses CLIP Vision for reference conditioning.
        boolean needsClipVision = defaultWorkflow != null && !defaultWorkflow.toLowerCase(java.util.Locale.ROOT).contains("ti2v-5b");
        if (wanConfigured && needsClipVision && blank(clipVisionModel)) {
            log.warn("Wan legacy workflow is configured without a CLIP vision model; H3 can still be used independently.");
        }
        // Real check, not assumed (spec section 25/27): a scene attempted on
        // hardware without enough VRAM would OOM mid-generation, wasting the
        // wait rather than failing fast. gpuAvailable=false (no nvidia-smi,
        // e.g. the 2GB reference box) is itself a reason to stay on 2.5D.
        var resources = resourceMonitor.current();
        if (!resources.gpuAvailable()) {
            return "No GPU detected (" + resources.gpuUnavailableReason() + ") - AI video needs real VRAM.";
        }
        if (resources.gpuVramTotalMb() != null && resources.gpuVramTotalMb() < minVramMb) {
            return "GPU has " + resources.gpuVramTotalMb() + "MB VRAM, below the configured "
                    + minVramMb + "MB minimum (studio.animation.local-ai.min-vram-mb) for AI video.";
        }
        return null;
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public VideoGenerationResult generateVideo(VideoGenerationRequest request) {
        String reason = unavailableReason();
        if (reason != null) {
            throw new IllegalStateException("Local AI video generation is not available: " + reason);
        }
        if (request.workflow() != null && request.workflow().toLowerCase(java.util.Locale.ROOT).startsWith("minimax-h3")) {
            var gpu = resourceMonitor.current();
            if (gpu.gpuVramTotalMb() != null && gpu.gpuVramTotalMb() < h3MinVramMb) {
                throw new IllegalStateException("MiniMax H3 local ComfyUI workflow requires at least " + h3MinVramMb
                        + "MB VRAM in this build; current GPU has " + gpu.gpuVramTotalMb()
                        + "MB. Use Wan 2.2 TI2V-5B on 16GB or move H3 to a larger GPU.");
            }
        }
        boolean acquired;
        try {
            acquired = slot.tryAcquire(queueWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a free video generation slot", e);
        }
        if (!acquired) {
            throw new IllegalStateException(
                    "Timed out waiting for a free ComfyUI video slot after " + queueWait.toSeconds() + "s");
        }
        try {
            return doGenerate(request);
        } finally {
            slot.release();
        }
    }

    private VideoGenerationResult doGenerate(VideoGenerationRequest request) {
        long seed = request.seed() != null ? request.seed() : ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        String workflowName = request.workflow() != null ? request.workflow() : defaultWorkflow;
        int width = request.width() > 0 ? request.width() : defaultWidth;
        int height = request.height() > 0 ? request.height() : defaultHeight;

        double duration = Math.max(0.5, Math.min(maxDurationSeconds, request.durationSeconds()));
        // Wan's frame count needs to land on 4n+1 for its causal VAE - round to
        // the nearest valid length rather than passing an arbitrary frame count
        // the workflow might reject.
        // Wan's frame count needs to land on 4n+1 for its causal VAE - round UP
        // to the nearest valid length, never down. Rounding down (the previous
        // behavior here) could make the generated clip up to 3 frames SHORTER
        // than the scene's requested duration, and nothing downstream pads a
        // short AI clip back out - FFmpegProcessor's -t flag only trims a clip
        // DOWN to the target length, it can't manufacture missing frames. That
        // undershoot is exactly what trips "Scene N rendered only Xs of
        // expected Ys. Refusing to create a partial episode." Rounding up here
        // guarantees the raw Wan clip is always >= the requested duration, so
        // the later FFmpeg trim (which expects that) actually has something to
        // trim from instead of coming up short.
        int rawFrames = (int) Math.round(duration * (workflowName.startsWith("minimax-h3") ? 24 : defaultFps));
        int length = workflowName.startsWith("minimax-h3")
                ? Math.max(5, rawFrames + ((17 - (rawFrames - 5) % 17) % 17))
                : Math.max(5, (((rawFrames - 1) + 3) / 4) * 4 + 1);

        // Text-to-video is a real mode, not an error case: no starting image
        // means Wan22ImageToVideoLatent's optional start_image input is
        // simply left unwired (see wan-ti2v-5b-text-to-video.json's own
        // comment - that's genuinely how this node does T2V, per ComfyUI's
        // own official template). request.workflow() still wins if the
        // caller explicitly named one; otherwise the presence/absence of an
        // image picks between the two Wan templates automatically.
        boolean hasStartingImage = request.startingImagePath() != null;
        if (request.workflow() == null) {
            workflowName = hasStartingImage ? defaultWorkflow : textToVideoWorkflow;
        }
        String startingImageFilename = hasStartingImage
                ? uploadStartingImage(Path.of(request.startingImagePath()))
                : null;

        // Unique per job, not a shared incrementing counter: VHS_VideoCombine's
        // own auto-numbering is scanned from disk per-run, and any leftover
        // file from an earlier session (or a fresh ComfyUI restart racing an
        // in-flight job) can collide on the same number. When that happens the
        // node skips the write silently ("File already exists. Exiting.")
        // without raising a hard error, so ComfyUI reports the prompt as
        // executed successfully with no video output - and pollForVideo()
        // then waits the FULL timeout for a file that was never going to
        // appear. A per-job-unique prefix makes that collision structurally
        // impossible instead of trying to out-guess the counter.
        String clientId = UUID.randomUUID().toString();
        String filenamePrefix = "ai-story-studio-wan-" + clientId;

        String workflowJson = workflowName.startsWith("minimax-h3")
                ? fillMiniMaxH3Template(workflowName, request.prompt(), seed, width, height, length,
                    request.steps() > 0 ? request.steps() : h3Steps, startingImageFilename, request.voiceReferenceAudioPath())
                : fillWanTemplate(workflowName, request.prompt(),
                    request.negativePrompt() == null ? "" : request.negativePrompt(),
                    seed, width, height, length, defaultFps,
                    request.steps() > 0 ? request.steps() : defaultSteps,
                    startingImageFilename, filenamePrefix);

        Map<String, Object> payload = new LinkedHashMap<>();
        try {
            payload.put("prompt", mapper.readTree(workflowJson));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid ComfyUI video workflow template '" + workflowName + "'", e);
        }
        payload.put("client_id", clientId);

        log.info("ComfyUI video submit: workflow={} model={} {}x{} length={} frames (~{}s) fps={} seed={}",
                workflowName, diffusionModel, width, height, length, duration, defaultFps, seed);

        JsonNode queueResponse;
        try {
            queueResponse = webClient.post()
                    .uri("/prompt")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(30));
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            throw new IllegalStateException(
                    "ComfyUI rejected the video workflow (" + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
        }

        if (queueResponse == null || queueResponse.get("prompt_id") == null) {
            throw new IllegalStateException("ComfyUI did not accept the video workflow: " + queueResponse);
        }
        JsonNode nodeErrors = queueResponse.path("node_errors");
        if (nodeErrors.isObject() && nodeErrors.size() > 0) {
            throw new IllegalStateException("ComfyUI reported node errors: " + nodeErrors);
        }
        String promptId = queueResponse.get("prompt_id").asText();

        long start = System.currentTimeMillis();
        byte[] videoBytes = pollForVideo(promptId);
        log.info("ComfyUI video ready in {}s ({} bytes, promptId={})",
                (System.currentTimeMillis() - start) / 1000, videoBytes.length, promptId);
        return new VideoGenerationResult(videoBytes, "mp4", seed, workflowName);
    }

    /** Builds the video-specific placeholder maps and delegates the actual
     *  parse/substitute mechanism to {@link WorkflowTemplateFiller} - the
     *  same shared, typed-substitution mechanism ComfyUIImageProvider uses,
     *  not a separate ad hoc implementation. */
    private String fillMiniMaxH3Template(String workflowName, String positive, long seed, int width, int height, int length, int steps, String startingImageFilename, String voiceReferenceAudioPath) {
        boolean useVoiceReference = voiceReferenceAudioPath != null && !voiceReferenceAudioPath.isBlank();
        String template = useVoiceReference
                ? (startingImageFilename != null ? "minimax-h3-reference-to-video" : "minimax-h3-voice-to-video")
                : (startingImageFilename != null ? "minimax-h3-image-to-video" : "minimax-h3-text-to-video");
        String uploadedVoiceFilename = useVoiceReference ? uploadMedia(Path.of(voiceReferenceAudioPath)) : null;
        Map<String, String> text = new LinkedHashMap<>();
        text.put("{{POSITIVE_PROMPT}}", positive == null ? "" : positive);
        text.put("{{STARTING_IMAGE}}", startingImageFilename == null ? "" : startingImageFilename);
        text.put("{{VOICE_REFERENCE_AUDIO}}", uploadedVoiceFilename == null ? "" : uploadedVoiceFilename);
        text.put("{{FILENAME_PREFIX}}", "ai-story-studio-minimax-h3-" + UUID.randomUUID());
        text.put("{{H3_DIFFUSION_MODEL}}", useVoiceReference ? h3Ref2vaDiffusionModel : h3DiffusionModel);
        text.put("{{H3_TEXT_ENCODER}}", h3TextEncoder);
        text.put("{{H3_VIDEO_VAE}}", h3VideoVae);
        text.put("{{H3_AUDIO_VAE}}", h3AudioVae);
        Map<String, Number> numeric = new LinkedHashMap<>();
        numeric.put("{{SEED}}", seed);
        numeric.put("{{WIDTH}}", width);
        numeric.put("{{HEIGHT}}", height);
        numeric.put("{{LENGTH}}", length);
        numeric.put("{{STEPS}}", steps);
        return WorkflowTemplateFiller.fill(mapper, template, text, numeric);
    }

    private String fillWanTemplate(String workflowName, String positive, String negative,
                                   long seed, int width, int height, int length, int fps, int steps,
                                   String startingImageFilename, String filenamePrefix) {
        Map<String, String> text = new LinkedHashMap<>();
        text.put("{{POSITIVE_PROMPT}}", positive == null ? "" : positive);
        text.put("{{NEGATIVE_PROMPT}}", negative == null ? "" : negative);
        text.put("{{CHECKPOINT}}", diffusionModel);
        text.put("{{CLIP_MODEL}}", clipModel);
        text.put("{{VAE_MODEL}}", vaeModel);
        text.put("{{CLIP_VISION_MODEL}}", clipVisionModel);
        text.put("{{STARTING_IMAGE}}", startingImageFilename == null ? "" : startingImageFilename);
        text.put("{{FILENAME_PREFIX}}", "ai-story-studio-minimax-h3-" + UUID.randomUUID());
        text.put("{{SAMPLER}}", defaultSampler);
        text.put("{{SCHEDULER}}", defaultScheduler);
        text.put("{{FILENAME_PREFIX}}", filenamePrefix);

        Map<String, Number> numeric = new LinkedHashMap<>();
        numeric.put("{{SEED}}", seed);
        numeric.put("{{WIDTH}}", width);
        numeric.put("{{HEIGHT}}", height);
        numeric.put("{{LENGTH}}", length);
        numeric.put("{{FPS}}", fps);
        numeric.put("{{STEPS}}", steps);
        numeric.put("{{CFG}}", defaultCfg);

        return WorkflowTemplateFiller.fill(mapper, workflowName, text, numeric);
    }

    /** Same /upload/image mechanism ComfyUIImageProvider uses for reference
     *  images - this is ComfyUI's own documented way to feed an external
     *  image into any workflow's LoadImage node. */
    private String uploadStartingImage(Path imagePath) {
        return uploadMedia(imagePath);
    }

    /** Uploads an image or audio reference through ComfyUI's generic media upload endpoint. */
    private String uploadMedia(Path mediaPath) {
        if (mediaPath == null || !Files.isRegularFile(mediaPath)) {
            throw new IllegalStateException("Reference media not found on disk: " + mediaPath);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(mediaPath);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read reference media: " + mediaPath, e);
        }
        String ext = mediaPath.getFileName().toString();
        int dot = ext.lastIndexOf('.');
        ext = dot >= 0 ? ext.substring(dot).toLowerCase(java.util.Locale.ROOT) : ".bin";
        String prefix = ext.equals(".wav") || ext.equals(".mp3") || ext.equals(".flac") || ext.equals(".m4a") ? "h3-voice-" : "wan-start-";
        String filename = prefix + UUID.randomUUID() + ext;

        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("image", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        body.part("overwrite", "true");

        JsonNode response = webClient.post()
                .uri("/upload/image")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(body.build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(30));

        if (response == null || response.get("name") == null || response.get("name").asText().isBlank()) {
            throw new IllegalStateException("ComfyUI did not return a usable filename for uploaded reference media: " + response);
        }
        String name = response.get("name").asText();
        String subfolder = response.path("subfolder").asText("");
        String relative = subfolder.isBlank() ? name : subfolder + "/" + name;
        if (relative.equals("input") || relative.endsWith("/input") || relative.contains("../")) {
            throw new IllegalStateException("ComfyUI returned an invalid reference-media path: " + relative);
        }
        return relative;
    }

    // ---- polling -------------------------------------------------------------

    private byte[] pollForVideo(String promptId) {
        Instant deadline = Instant.now().plus(timeout);
        long startedAt = System.currentTimeMillis();
        long lastLog = startedAt;
        Instant missingSince = null;

        while (Instant.now().isBefore(deadline)) {
            JsonNode history = safeGet("/history/" + promptId);

            if (history != null && history.has(promptId)) {
                JsonNode entry = history.get(promptId);
                String statusStr = entry.path("status").path("status_str").asText("");
                if ("error".equalsIgnoreCase(statusStr)) {
                    throw new IllegalStateException("ComfyUI failed to execute the video workflow: " + describeError(entry));
                }

                byte[] bytes = extractFirstVideo(entry.path("outputs"));
                if (bytes != null) {
                    return bytes;
                }
                boolean completed = entry.path("status").path("completed").asBoolean(false);
                if (completed) {
                    throw new IllegalStateException(
                            "ComfyUI finished but produced no video file (looked for .mp4/.webm output across all "
                                    + "node outputs) - check that VHS_VideoCombine (or your chosen video-save node) "
                                    + "is actually installed and wired correctly (promptId=" + promptId + ")");
                }
            } else if (!isQueued(promptId)) {
                if (missingSince == null) {
                    missingSince = Instant.now();
                } else if (Duration.between(missingSince, Instant.now()).getSeconds() > 20) {
                    throw new IllegalStateException(
                            "ComfyUI video prompt " + promptId + " is no longer queued and never reached history - "
                                    + "it was most likely cancelled or ComfyUI restarted mid-generation");
                }
            } else {
                missingSince = null;
            }

            long now = System.currentTimeMillis();
            if (now - lastLog > 30_000) {
                log.info("Still waiting on ComfyUI video prompt {} ({}s elapsed, timeout {}s)",
                        promptId, (now - startedAt) / 1000, timeout.toSeconds());
                lastLog = now;
            }

            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelPrompt(promptId);
                throw new IllegalStateException("Interrupted while waiting for ComfyUI video", e);
            }
        }

        cancelPrompt(promptId);
        throw new IllegalStateException("Timed out after " + timeout.toSeconds()
                + "s waiting for ComfyUI video generation (promptId=" + promptId + "). "
                + "Video generation is much slower than image generation - raise "
                + "studio.animation.local-ai.timeout-seconds, or reduce steps/length/resolution.");
    }

    /**
     * Searches every output node for anything that looks like a video file,
     * regardless of which JSON key it's nested under. Deliberately
     * format-tolerant rather than hard-coded to one key name: the output key
     * VHS_VideoCombine (and alternative video-save nodes) use has not been
     * consistent across versions, and this is exactly the kind of detail
     * that's more reliable to detect from the actual filename than to
     * hard-code and have silently break on an unfamiliar version.
     */
    private byte[] extractFirstVideo(JsonNode outputs) {
        for (JsonNode nodeOutput : outputs) {
            var fields = nodeOutput.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode value = field.getValue();
                if (!value.isArray()) {
                    continue;
                }
                for (JsonNode item : value) {
                    String filename = item.path("filename").asText("");
                    if (filename.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp4|webm|mov)$")) {
                        return downloadFile(filename, item.path("subfolder").asText(""), item.path("type").asText("output"));
                    }
                }
            }
        }
        return null;
    }

    private String describeError(JsonNode historyEntry) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode message : historyEntry.path("status").path("messages")) {
            if (message.isArray() && message.size() > 1) {
                String type = message.get(0).asText();
                if (type.contains("error")) {
                    JsonNode detail = message.get(1);
                    sb.append(detail.path("node_type").asText("")).append(": ")
                      .append(detail.path("exception_message").asText(detail.toString())).append(' ');
                }
            }
        }
        return sb.length() > 0 ? sb.toString().trim() : historyEntry.path("status").toString();
    }

    private boolean isQueued(String promptId) {
        JsonNode queue = safeGet("/queue");
        if (queue == null) {
            return true;
        }
        return containsPrompt(queue.path("queue_running"), promptId)
                || containsPrompt(queue.path("queue_pending"), promptId);
    }

    private boolean containsPrompt(JsonNode queueSection, String promptId) {
        for (JsonNode item : queueSection) {
            if (item.isArray() && item.size() > 1 && promptId.equals(item.get(1).asText())) {
                return true;
            }
        }
        return false;
    }

    private void cancelPrompt(String promptId) {
        try {
            var body = mapper.createObjectNode();
            var ids = body.putArray("delete");
            ids.add(promptId);
            webClient.post().uri("/queue").bodyValue(body).retrieve()
                    .toBodilessEntity().block(Duration.ofSeconds(10));
            webClient.post().uri("/interrupt").retrieve()
                    .toBodilessEntity().block(Duration.ofSeconds(10));
            log.warn("Cancelled abandoned ComfyUI video prompt {}", promptId);
        } catch (Exception e) {
            log.warn("Could not cancel ComfyUI video prompt {}: {}", promptId, e.getMessage());
        }
    }

    private JsonNode safeGet(String uri) {
        try {
            return webClient.get().uri(uri).retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(15));
        } catch (Exception e) {
            log.debug("ComfyUI GET {} failed: {}", uri, e.getMessage());
            return null;
        }
    }

    private byte[] downloadFile(String filename, String subfolder, String type) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/view")
                        .queryParam("filename", filename)
                        .queryParam("subfolder", subfolder)
                        .queryParam("type", type)
                        .build())
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(120));
    }
}
