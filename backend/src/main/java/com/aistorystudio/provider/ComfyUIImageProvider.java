package com.aistorystudio.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

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
 * Talks to a self-hosted ComfyUI instance through its HTTP API.
 *
 * Single image engine: Qwen Image 2.1 (qwen-image-2-1-16gb-t2i / -ref / -ref-dual).
 * Locked character references go in natively as images.image_N.
 *
 * Behaviour that matters when ComfyUI is CPU-only (which is slow - minutes per
 * image, not seconds):
 *
 *  - Only ONE prompt is in flight at a time (ComfyUI executes serially anyway).
 *    Submitting scene 2 while scene 1 is still sampling just builds a queue that
 *    every caller then times out on, one after another.
 *  - Polling fails fast on ComfyUI-side errors and on prompts that vanished from
 *    both the queue and the history (i.e. were cancelled), instead of spinning
 *    until the full timeout expires.
 *  - On timeout the prompt is actually interrupted and removed from the queue,
 *    so an abandoned job stops burning CPU that the next scene needs.
 *  - Sizes are snapped to multiples of 16 (Qwen latent) and clamped.
 */
@Component
public class ComfyUIImageProvider implements ImageGenerationProvider {

    private static final Logger log = LoggerFactory.getLogger(ComfyUIImageProvider.class);

    private static final int MAX_DIMENSION = 2048;
    private static final int MIN_DIMENSION = 256;

    // Single image engine: Qwen Image 2.1. Three graphs, picked per request by how
    // many character references actually uploaded: t2i / ref / ref-dual.
    private static final String WF_T2I = "qwen-image-2-1-16gb-t2i";
    private static final String WF_REF = "qwen-image-2-1-16gb-ref";
    private static final String WF_REF_DUAL = "qwen-image-2-1-16gb-ref-dual";

    private final WebClient webClient;
    private final String qwenDiffusionModel;
    private final String qwenTextEncoder;
    private final String qwenVae;
    private final String qwenUpscaleModel;
    private final int qwenResolution;
    private final int qwenOutputWidth;
    private final int qwenOutputHeight;
    private final int defaultSteps;
    private final int defaultWidth;
    private final int defaultHeight;
    private final Duration timeout;
    private final Duration queueWait;
    private final long pollIntervalMs;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * ComfyUI runs one prompt at a time - shared with ComfyUIVideoProvider via
     * ComfyUiAccessCoordinator so image and video prompts never interrupt each other.
     */
    private final Semaphore slot;

    public ComfyUIImageProvider(
            WebClient.Builder webClientBuilder,
            ComfyUiAccessCoordinator comfyUiAccessCoordinator,
            @Value("${studio.comfyui.baseUrl}") String baseUrl,
            @Value("${studio.comfyui.qwen.diffusion-model:qwen_image_2.1_int8_convrot.safetensors}") String qwenDiffusionModel,
            @Value("${studio.comfyui.qwen.text-encoder:qwen3vl_8b_int8_convrot.safetensors}") String qwenTextEncoder,
            @Value("${studio.comfyui.qwen.vae:qwen_image_2.1_vae_bf16.safetensors}") String qwenVae,
            @Value("${studio.comfyui.qwen.upscale-model:RealESRGAN_x2.pth}") String qwenUpscaleModel,
            @Value("${studio.comfyui.qwen.resolution:1024}") int qwenResolution,
            @Value("${studio.comfyui.qwen.output-width:1080}") int qwenOutputWidth,
            @Value("${studio.comfyui.qwen.output-height:1920}") int qwenOutputHeight,
            @Value("${studio.comfyui.qwen.steps:30}") int defaultSteps,
            @Value("${studio.comfyui.qwen.width:768}") int defaultWidth,
            @Value("${studio.comfyui.qwen.height:1344}") int defaultHeight,
            @Value("${studio.comfyui.timeoutSeconds:900}") long timeoutSeconds,
            @Value("${studio.comfyui.queueWaitSeconds:3600}") long queueWaitSeconds,
            @Value("${studio.comfyui.pollIntervalMs:2000}") long pollIntervalMs) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.slot = comfyUiAccessCoordinator.slot();
        this.qwenDiffusionModel = qwenDiffusionModel;
        this.qwenTextEncoder = qwenTextEncoder;
        this.qwenVae = qwenVae;
        this.qwenUpscaleModel = qwenUpscaleModel;
        this.qwenResolution = qwenResolution;
        this.qwenOutputWidth = qwenOutputWidth;
        this.qwenOutputHeight = qwenOutputHeight;
        this.defaultSteps = defaultSteps;
        this.defaultWidth = defaultWidth;
        this.defaultHeight = defaultHeight;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.queueWait = Duration.ofSeconds(queueWaitSeconds);
        this.pollIntervalMs = Math.max(250, pollIntervalMs);
    }

    @Override
    public ImageGenerationResult generateImage(ImageGenerationRequest request) {
        boolean acquired;
        try {
            acquired = slot.tryAcquire(queueWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a free ComfyUI slot", e);
        }
        if (!acquired) {
            throw new IllegalStateException("Timed out waiting for a free ComfyUI slot after "
                    + queueWait.toSeconds() + "s");
        }
        try {
            return doGenerate(request);
        } finally {
            slot.release();
        }
    }

    private ImageGenerationResult doGenerate(ImageGenerationRequest request) {
        long seed = request.seed() != null ? request.seed() : ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        int width = snap16(request.width() > 0 ? request.width() : defaultWidth);
        int height = snap16(request.height() > 0 ? request.height() : defaultHeight);
        int steps = request.steps() > 0 ? request.steps() : defaultSteps;

        // References: a failed upload degrades to fewer references, never to a
        // failed image. Both must upload for the dual graph.
        String ref1 = tryUpload(request.referenceImagePath());
        String ref2 = ref1 == null ? null : tryUpload(request.referenceImagePath2());
        int refCount = ref1 == null ? 0 : (ref2 == null ? 1 : 2);
        String workflowName = refCount == 2 ? WF_REF_DUAL : refCount == 1 ? WF_REF : WF_T2I;

        // Final size: configured 1080x1920 for the usual 9:16 frame, otherwise the
        // plain 2x upscale at the request's own aspect (e.g. square character refs).
        int outW = qwenOutputWidth;
        int outH = qwenOutputHeight;
        if (Math.abs((double) width / height - (double) qwenOutputWidth / qwenOutputHeight) > 0.03) {
            outW = width * 2;
            outH = height * 2;
        }

        String clientId = UUID.randomUUID().toString();
        Map<String, String> text = new LinkedHashMap<>();
        text.put("{{POSITIVE_PROMPT}}", qwenPromptWithReferences(request.prompt(), refCount));
        text.put("{{NEGATIVE_PROMPT}}", request.negativePrompt() == null ? "" : request.negativePrompt());
        text.put("{{REFERENCE_IMAGE}}", ref1 == null ? "" : ref1);
        text.put("{{REFERENCE_IMAGE_2}}", ref2 == null ? "" : ref2);
        text.put("{{QWEN_DIFFUSION_MODEL}}", qwenDiffusionModel);
        text.put("{{QWEN_TEXT_ENCODER}}", qwenTextEncoder);
        text.put("{{QWEN_VAE}}", qwenVae);
        text.put("{{UPSCALE_MODEL}}", qwenUpscaleModel);
        text.put("{{FILENAME_PREFIX}}", "ai-story-studio-" + clientId);
        Map<String, Number> numeric = new LinkedHashMap<>();
        numeric.put("{{SEED}}", seed);
        numeric.put("{{WIDTH}}", width);
        numeric.put("{{HEIGHT}}", height);
        numeric.put("{{STEPS}}", steps);
        numeric.put("{{QWEN_RESOLUTION}}", qwenResolution);
        numeric.put("{{OUTPUT_WIDTH}}", outW);
        numeric.put("{{OUTPUT_HEIGHT}}", outH);
        String workflowJson = WorkflowTemplateFiller.fill(mapper, workflowName, text, numeric);

        Map<String, Object> payload = new LinkedHashMap<>();
        try {
            payload.put("prompt", mapper.readTree(workflowJson));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid ComfyUI workflow template '" + workflowName + "'", e);
        }
        payload.put("client_id", clientId);

        log.info("ComfyUI submit: workflow={} model={} {}x{} -> {}x{} steps={} refs={} seed={}",
                workflowName, qwenDiffusionModel, width, height, outW, outH, steps, refCount, seed);

        JsonNode queue;
        try {
            queue = submitPrompt(payload);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            if (body != null && body.contains("missing_node_type")) {
                throw new IllegalStateException("ComfyUI is missing Qwen Image 2.1 nodes "
                        + "(QwenImage21Cache / TextEncodeQwenImage21). Update ComfyUI. Details: " + body, e);
            }
            throw new IllegalStateException("ComfyUI rejected the workflow (" + e.getStatusCode() + "): " + body, e);
        }
        if (queue == null || queue.get("prompt_id") == null) {
            throw new IllegalStateException("ComfyUI did not accept the workflow: " + queue);
        }
        JsonNode nodeErrors = queue.path("node_errors");
        if (nodeErrors.isObject() && nodeErrors.size() > 0) {
            throw new IllegalStateException("ComfyUI reported node errors: " + nodeErrors);
        }

        long start = System.currentTimeMillis();
        byte[] imageBytes = pollForImage(queue.get("prompt_id").asText());
        log.info("ComfyUI image ready in {}s ({} bytes)", (System.currentTimeMillis() - start) / 1000, imageBytes.length);
        return new ImageGenerationResult(imageBytes, "png", seed, qwenDiffusionModel, workflowName);
    }

    private String tryUpload(String path) {
        if (path == null) {
            return null;
        }
        try {
            return uploadReferenceImage(Path.of(path));
        } catch (Exception e) {
            log.warn("Could not upload character reference {} to ComfyUI, generating without it: {}", path, e.getMessage());
            return null;
        }
    }

    /** Qwen 2.1 latents are 1/16 of pixel size. */
    private static int snap16(int value) {
        int clamped = Math.max(MIN_DIMENSION, Math.min(MAX_DIMENSION, value));
        return Math.round(clamped / 16f) * 16;
    }


    private JsonNode submitPrompt(Map<String, Object> payload) {
        return webClient.post()
                .uri("/prompt")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(30));
    }

    /**
     * Qwen Image 2.1 addresses reference images as {@code <image1>}, {@code <image2>}.
     * The pipeline orders characters so the first described character owns
     * image1 and the second owns image2. The instruction also tells the model
     * NOT to copy the reference sheet's plain studio background or pose -
     * the cause of "no background detail" with the old IPAdapter path.
     */
    static String qwenPromptWithReferences(String prompt, int refCount) {
        String p = prompt == null ? "" : prompt.trim();
        if (refCount <= 0) {
            return p;
        }
        String mapping = refCount == 1
                ? "<image1> is the official character design reference for the first character described below."
                : "<image1> is the official character design reference for the first character described below, "
                  + "<image2> is the official character design reference for the second character.";
        return mapping
                + " Keep each referenced character exactly on-model: same face shape, eyes, hairstyle, skin tone, "
                + "outfit, colors, accessories and body proportions as the reference. "
                + "Draw a completely new scene: new pose, new camera angle, new expression as described, "
                + "and a fully rendered, richly detailed environment. Do not copy the reference image's plain "
                + "background, framing or pose, and do not draw the reference sheet itself.\n\n"
                + p;
    }

    /**
     * Uploads a reference image to ComfyUI via its /upload/image endpoint so
     * a workflow's LoadImage node can reference it by the server-side
     * filename ComfyUI returns. This is ComfyUI's own documented mechanism
     * for feeding an external image into a workflow (used by IPAdapter and
     * ControlNet workflows alike) - not something specific to this app.
     *
     * NOTE: this method's HTTP contract (multipart upload, response shape)
     * matches ComfyUI's documented /upload/image API, but has not been
     * exercised against a live ComfyUI instance in this environment. Treat
     * it as needing verification against your actual ComfyUI version before
     * relying on it, the same as any workflow template you haven't run yet.
     */
    private String uploadReferenceImage(Path imagePath) {
        if (!Files.exists(imagePath)) {
            throw new IllegalStateException("Reference image not found on disk: " + imagePath);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(imagePath);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read reference image: " + imagePath, e);
        }

        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("image", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "reference-" + UUID.randomUUID() + ".png";
            }
        }).contentType(MediaType.IMAGE_PNG);
        // "true" tells ComfyUI to overwrite rather than de-dupe/rename on a
        // filename collision - irrelevant here since the filename is always
        // a fresh UUID, but matches ComfyUI's documented expectation for
        // this field being present.
        body.part("overwrite", "true");

        JsonNode response = webClient.post()
                .uri("/upload/image")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(body.build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(30));

        if (response == null || response.get("name") == null || response.get("name").asText().isBlank()) {
            throw new IllegalStateException("ComfyUI did not return a usable filename for the uploaded reference image: " + response);
        }
        String name = response.get("name").asText();
        String subfolder = response.path("subfolder").asText("");
        String relative = subfolder.isBlank() ? name : subfolder + "/" + name;
        if (relative.equals("input") || relative.endsWith("/input") || relative.contains("../")) {
            throw new IllegalStateException("ComfyUI returned an invalid reference-image path: " + relative);
        }
        return relative;
    }

    // ---- polling -----------------------------------------------------------

    private byte[] pollForImage(String promptId) {
        Instant deadline = Instant.now().plus(timeout);
        long startedAt = System.currentTimeMillis();
        long lastLog = startedAt;
        // Grace period before we trust "not in queue and not in history" as a real
        // disappearance rather than a race right after submitting.
        Instant missingSince = null;

        while (Instant.now().isBefore(deadline)) {
            JsonNode history = safeGet("/history/" + promptId);

            if (history != null && history.has(promptId)) {
                JsonNode entry = history.get(promptId);
                String statusStr = entry.path("status").path("status_str").asText("");
                if ("error".equalsIgnoreCase(statusStr)) {
                    throw new IllegalStateException("ComfyUI failed to execute the workflow: " + describeError(entry));
                }

                byte[] bytes = extractFirstImage(entry.path("outputs"));
                if (bytes != null) {
                    return bytes;
                }
                boolean completed = entry.path("status").path("completed").asBoolean(false);
                if (completed) {
                    throw new IllegalStateException(
                            "ComfyUI finished but produced no image - does the workflow have a SaveImage node? "
                                    + "(promptId=" + promptId + ")");
                }
            } else if (!isQueued(promptId)) {
                if (missingSince == null) {
                    missingSince = Instant.now();
                } else if (Duration.between(missingSince, Instant.now()).getSeconds() > 15) {
                    throw new IllegalStateException(
                            "ComfyUI prompt " + promptId + " is no longer queued and never reached history - "
                                    + "it was most likely cancelled or ComfyUI restarted mid-generation");
                }
            } else {
                missingSince = null;
            }

            long now = System.currentTimeMillis();
            if (now - lastLog > 30_000) {
                log.info("Still waiting on ComfyUI prompt {} ({}s elapsed, timeout {}s)",
                        promptId, (now - startedAt) / 1000, timeout.toSeconds());
                lastLog = now;
            }

            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelPrompt(promptId);
                throw new IllegalStateException("Interrupted while waiting for ComfyUI image", e);
            }
        }

        // Do not just walk away: an abandoned prompt keeps sampling and starves
        // every following scene of CPU, which is exactly how one slow image turns
        // into a whole episode of timeouts.
        cancelPrompt(promptId);
        throw new IllegalStateException("Timed out after " + timeout.toSeconds()
                + "s waiting for ComfyUI image generation (promptId=" + promptId + "). "
                + "Reduce COMFYUI_STEPS / COMFYUI_WIDTH / COMFYUI_HEIGHT, or raise COMFYUI_TIMEOUT_SECONDS.");
    }

    private byte[] extractFirstImage(JsonNode outputs) {
        for (JsonNode nodeOutput : outputs) {
            JsonNode images = nodeOutput.path("images");
            if (images.isArray() && images.size() > 0) {
                JsonNode img = images.get(0);
                return downloadImage(
                        img.path("filename").asText(),
                        img.path("subfolder").asText(""),
                        img.path("type").asText("output"));
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
                      .append(detail.path("exception_message").asText(detail.toString())).append(" ");
                }
            }
        }
        return sb.length() > 0 ? sb.toString().trim() : historyEntry.path("status").toString();
    }

    /** True if the prompt is currently running or still pending in ComfyUI's queue. */
    private boolean isQueued(String promptId) {
        JsonNode queue = safeGet("/queue");
        if (queue == null) {
            return true; // can't tell - assume still alive rather than failing spuriously
        }
        return containsPrompt(queue.path("queue_running"), promptId)
                || containsPrompt(queue.path("queue_pending"), promptId);
    }

    private boolean containsPrompt(JsonNode queueSection, String promptId) {
        for (JsonNode item : queueSection) {
            // entries look like [number, "<prompt_id>", {graph}, {extra}, [outputs]]
            if (item.isArray() && item.size() > 1 && promptId.equals(item.get(1).asText())) {
                return true;
            }
        }
        return false;
    }

    private void cancelPrompt(String promptId) {
        try {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode ids = body.putArray("delete");
            ids.add(promptId);
            webClient.post().uri("/queue").bodyValue(body).retrieve()
                    .toBodilessEntity().block(Duration.ofSeconds(10));
            // /queue delete only removes *pending* items; interrupt stops the running one.
            webClient.post().uri("/interrupt").retrieve()
                    .toBodilessEntity().block(Duration.ofSeconds(10));
            log.warn("Cancelled abandoned ComfyUI prompt {}", promptId);
        } catch (Exception e) {
            log.warn("Could not cancel ComfyUI prompt {}: {}", promptId, e.getMessage());
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

    private byte[] downloadImage(String filename, String subfolder, String type) {
        byte[] bytes = webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/view")
                        .queryParam("filename", filename)
                        .queryParam("subfolder", subfolder)
                        .queryParam("type", type)
                        .build())
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(60));
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("Failed to download generated image " + filename + " from ComfyUI");
        }
        return bytes;
    }

    @Override
    public boolean healthCheck() {
        try {
            webClient.get().uri("/system_stats").retrieve().toBodilessEntity().block(Duration.ofSeconds(5));
            return true;
        } catch (Exception e) {
            log.warn("ComfyUI health check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String providerName() {
        return "comfyui";
    }

}
