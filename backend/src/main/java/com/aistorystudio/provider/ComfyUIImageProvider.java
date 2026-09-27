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
 * Workflow JSON templates live in resources/comfyui-workflows and are filled in
 * with prompt/negative-prompt/seed/size/sampler before being POSTed to /prompt.
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
 *  - Sizes are snapped to multiples of 8 and clamped, because a wrong latent
 *    size is the single biggest cause of "why is this taking 40 minutes".
 */
@Component
public class ComfyUIImageProvider implements ImageGenerationProvider {

    private static final Logger log = LoggerFactory.getLogger(ComfyUIImageProvider.class);

    /** Anything above this on CPU is effectively a hang; SD1.5 also degrades badly past ~768. */
    private static final int MAX_DIMENSION = 2048;
    private static final int MIN_DIMENSION = 256;

    private final WebClient webClient;
    private final String defaultWorkflow;
    private final String defaultModel;
    private final String defaultSampler;
    private final String defaultScheduler;
    private final int defaultSteps;
    private final int defaultWidth;
    private final int defaultHeight;
    private final double defaultCfg;
    private final Duration timeout;
    private final Duration queueWait;
    private final long pollIntervalMs;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * ComfyUI runs one prompt at a time. Serialising here means a caller waits for
     * a free slot rather than piling a second prompt onto the queue behind the one
     * it is already going to time out on.
     */
    private final Semaphore slot = new Semaphore(1, true);

    public ComfyUIImageProvider(
            WebClient.Builder webClientBuilder,
            @Value("${studio.comfyui.baseUrl}") String baseUrl,
            @Value("${studio.comfyui.workflow}") String defaultWorkflow,
            @Value("${studio.comfyui.model}") String defaultModel,
            @Value("${studio.comfyui.steps:8}") int defaultSteps,
            @Value("${studio.comfyui.width:512}") int defaultWidth,
            @Value("${studio.comfyui.height:512}") int defaultHeight,
            @Value("${studio.comfyui.cfg:7.0}") double defaultCfg,
            @Value("${studio.comfyui.sampler:dpmpp_2m}") String defaultSampler,
            @Value("${studio.comfyui.scheduler:karras}") String defaultScheduler,
            @Value("${studio.comfyui.timeoutSeconds:900}") long timeoutSeconds,
            @Value("${studio.comfyui.queueWaitSeconds:3600}") long queueWaitSeconds,
            @Value("${studio.comfyui.pollIntervalMs:2000}") long pollIntervalMs) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.defaultWorkflow = defaultWorkflow;
        this.defaultModel = defaultModel;
        this.defaultSteps = defaultSteps;
        this.defaultWidth = defaultWidth;
        this.defaultHeight = defaultHeight;
        this.defaultCfg = defaultCfg;
        this.defaultSampler = defaultSampler;
        this.defaultScheduler = defaultScheduler;
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
            throw new IllegalStateException(
                    "Timed out waiting for a free ComfyUI slot after " + queueWait.toSeconds() + "s - "
                            + "generation is slower than the pipeline is submitting work "
                            + "(lower COMFYUI_STEPS / COMFYUI_WIDTH / COMFYUI_HEIGHT, or move to GPU)");
        }
        try {
            return doGenerate(request);
        } finally {
            slot.release();
        }
    }

    private ImageGenerationResult doGenerate(ImageGenerationRequest request) {
        long seed = request.seed() != null ? request.seed() : ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        String workflowName = request.workflow() != null ? request.workflow() : defaultWorkflow;
        String checkpointName = request.model() != null ? request.model() : defaultModel;
        // The bundled reference workflow is SD1.5-specific. Selecting it for an
        // SDXL checkpoint produces either a node/model mismatch or weak identity
        // conditioning. Automatically choose the matching SDXL IPAdapter template.
        if ("character-consistent-story-ipadapter".equals(workflowName)
                && checkpointName != null
                && checkpointName.toLowerCase(java.util.Locale.ROOT).contains("xl")) {
            workflowName = "character-consistent-story-ipadapter-sdxl";
        }

        int width = snap(request.width() > 0 ? request.width() : defaultWidth);
        int height = snap(request.height() > 0 ? request.height() : defaultHeight);
        int steps = request.steps() > 0 ? request.steps() : defaultSteps;
        double cfg = request.cfg() > 0 ? request.cfg() : defaultCfg;

        // SDXL-Lightning is distilled for the Euler/SGM-Uniform schedule. The
        // older dpmpp_2m/karras defaults are valid for ordinary SD1.5 but waste
        // the Lightning checkpoint's speed/quality advantage.
        String sampler = defaultSampler;
        String scheduler = defaultScheduler;
        String modelLower = checkpointName == null ? "" : checkpointName.toLowerCase(java.util.Locale.ROOT);
        if (modelLower.contains("lightning")) {
            if ("dpmpp_2m".equalsIgnoreCase(sampler)) sampler = "euler";
            if ("karras".equalsIgnoreCase(scheduler)) scheduler = "sgm_uniform";
        }

        // Character-consistency reference image (never required - see
        // WorkflowTemplateLoader's {{REFERENCE_IMAGE}} placeholder, which
        // only the IPAdapter-capable workflow templates actually use). A
        // failed upload degrades to no reference rather than failing the
        // whole scene: a character looking slightly less consistent in one
        // scene is a far smaller problem than the scene not existing.
        String referenceImageFilename = null;
        if (request.referenceImagePath() != null) {
            try {
                referenceImageFilename = uploadReferenceImage(Path.of(request.referenceImagePath()));
            } catch (Exception e) {
                log.warn("Could not upload character reference image to ComfyUI, generating without it: {}",
                        e.getMessage());
            }
        }
        // The workflow must follow the resource that actually made it into ComfyUI,
        // not merely the fact that the caller supplied a local path. If upload failed
        // (or there was no reference at all), never submit an IPAdapter workflow with
        // an empty LoadImage value - ComfyUI resolves that empty value to its input
        // directory and LoadImage then throws IsADirectoryError.
        if (referenceImageFilename == null && workflowName.toLowerCase(java.util.Locale.ROOT).contains("ipadapter")) {
            log.info("No usable character reference uploaded; switching from {} to plain SDXL workflow.", workflowName);
            workflowName = "character-consistent-story-sdxl";
        }

        // Unique per call, not a shared constant: every image workflow's
        // SaveImage node had a hardcoded filename_prefix ("ai-story-studio"
        // etc.), the exact same collision class that broke video generation
        // (see wan-ti2v-5b-image-to-video.json's history) - SaveImage's own
        // auto-increment counter is no more reliable across restarts/
        // concurrent runs than VHS_VideoCombine's was. A per-call unique
        // prefix makes the collision structurally impossible instead of
        // relying on ComfyUI to keep count correctly.
        String clientId = UUID.randomUUID().toString();
        String filenamePrefix = "ai-story-studio-" + clientId;

        String workflowJson = WorkflowTemplateLoader.loadAndFill(
                mapper, workflowName, request.prompt(),
                request.negativePrompt() == null ? "" : request.negativePrompt(),
                seed, width, height, steps, cfg, checkpointName, defaultSampler, defaultScheduler,
                referenceImageFilename, filenamePrefix);

        Map<String, Object> payload = new LinkedHashMap<>();
        try {
            payload.put("prompt", mapper.readTree(workflowJson));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid ComfyUI workflow template '" + workflowName + "'", e);
        }
        payload.put("client_id", clientId);

        log.info("ComfyUI submit: workflow={} ckpt={} {}x{} steps={} cfg={} sampler={}/{} seed={}",
                workflowName, checkpointName, width, height, steps, cfg, sampler, scheduler, seed);

        JsonNode queueResponse;
        try {
            queueResponse = submitPrompt(payload);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            // A missing IPAdapter custom node should not make every scene fail.
            // Retry the same SDXL generation without reference conditioning so the
            // episode can still render. Once ComfyUI_IPAdapter_plus is installed,
            // the normal workflow is used again automatically.
            if (isMissingIpAdapterNode(workflowName, body)) {
                String fallbackWorkflow = "character-consistent-story-sdxl";
                log.warn("ComfyUI IPAdapter node is unavailable; retrying scene with {} fallback. "
                        + "Install ComfyUI_IPAdapter_plus to restore reference conditioning.", fallbackWorkflow);
                String fallbackJson = WorkflowTemplateLoader.loadAndFill(
                        mapper, fallbackWorkflow, request.prompt(),
                        request.negativePrompt() == null ? "" : request.negativePrompt(),
                        seed, width, height, steps, cfg, checkpointName, defaultSampler, defaultScheduler,
                        null, filenamePrefix);
                Map<String, Object> fallbackPayload = new LinkedHashMap<>();
                try {
                    fallbackPayload.put("prompt", mapper.readTree(fallbackJson));
                } catch (Exception parse) {
                    throw new IllegalStateException("Invalid ComfyUI fallback workflow '" + fallbackWorkflow + "'", parse);
                }
                fallbackPayload.put("client_id", clientId);
                queueResponse = submitPrompt(fallbackPayload);
                workflowName = fallbackWorkflow;
            } else {
                throw new IllegalStateException(
                        "ComfyUI rejected the workflow (" + e.getStatusCode() + "): " + body, e);
            }
        }

        if (queueResponse == null || queueResponse.get("prompt_id") == null) {
            throw new IllegalStateException("ComfyUI did not accept the workflow: " + queueResponse);
        }
        JsonNode nodeErrors = queueResponse.path("node_errors");
        if (nodeErrors.isObject() && nodeErrors.size() > 0) {
            String nodeErrorText = nodeErrors.toString();
            if (isMissingIpAdapterNode(workflowName, nodeErrorText)) {
                String fallbackWorkflow = "character-consistent-story-sdxl";
                log.warn("ComfyUI returned IPAdapter node errors; retrying with {} fallback.", fallbackWorkflow);
                String fallbackJson = WorkflowTemplateLoader.loadAndFill(
                        mapper, fallbackWorkflow, request.prompt(),
                        request.negativePrompt() == null ? "" : request.negativePrompt(),
                        seed, width, height, steps, cfg, checkpointName, defaultSampler, defaultScheduler,
                        null, filenamePrefix);
                Map<String, Object> fallbackPayload = new LinkedHashMap<>();
                try { fallbackPayload.put("prompt", mapper.readTree(fallbackJson)); }
                catch (Exception parse) { throw new IllegalStateException("Invalid ComfyUI fallback workflow '" + fallbackWorkflow + "'", parse); }
                fallbackPayload.put("client_id", clientId);
                queueResponse = submitPrompt(fallbackPayload);
                workflowName = fallbackWorkflow;
                nodeErrors = queueResponse.path("node_errors");
            }
            if (nodeErrors.isObject() && nodeErrors.size() > 0) {
                throw new IllegalStateException("ComfyUI reported node errors: " + nodeErrors);
            }
        }
        String promptId = queueResponse.get("prompt_id").asText();

        long start = System.currentTimeMillis();
        byte[] imageBytes = pollForImage(promptId);
        log.info("ComfyUI image ready in {}s ({} bytes, promptId={})",
                (System.currentTimeMillis() - start) / 1000, imageBytes.length, promptId);
        return new ImageGenerationResult(imageBytes, "png", seed, checkpointName, workflowName);
    }

    private JsonNode submitPrompt(Map<String, Object> payload) {
        return webClient.post()
                .uri("/prompt")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(30));
    }

    private boolean isMissingIpAdapterNode(String workflowName, String body) {
        if (workflowName == null || !workflowName.toLowerCase(java.util.Locale.ROOT).contains("ipadapter")) {
            return false;
        }
        String text = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        return text.contains("missing_node_type")
                && (text.contains("ipadaptermodellloader") || text.contains("ipadapteradvanced") || text.contains("ipadapter"));
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

    private static int snap(int value) {
        int clamped = Math.max(MIN_DIMENSION, Math.min(MAX_DIMENSION, value));
        return Math.round(clamped / 8f) * 8; // latent size must be a multiple of 8
    }

    /**
     * Builds the image-specific placeholder maps and delegates the actual
     * parse/substitute mechanism to {@link WorkflowTemplateFiller}, which is
     * shared with the video provider rather than duplicated here.
     */
    static final class WorkflowTemplateLoader {

        static String loadAndFill(ObjectMapper mapper, String workflowName, String positive, String negative,
                                   long seed, int width, int height, int steps, double cfg,
                                   String checkpointName, String sampler, String scheduler,
                                   String referenceImageFilename, String filenamePrefix) {
            Map<String, String> text = new LinkedHashMap<>();
            text.put("{{POSITIVE_PROMPT}}", positive == null ? "" : positive);
            text.put("{{NEGATIVE_PROMPT}}", negative == null ? "" : negative);
            text.put("{{CHECKPOINT}}", checkpointName);
            text.put("{{SAMPLER}}", sampler);
            text.put("{{SCHEDULER}}", scheduler);
            text.put("{{FILENAME_PREFIX}}", filenamePrefix);
            // Only substituted if the workflow actually has this placeholder
            // (the IPAdapter-capable templates) - a plain txt2img template
            // simply has no {{REFERENCE_IMAGE}} token anywhere to replace.
            // Empty string rather than null: a workflow's LoadImage node
            // still needs *some* string value even if unused.
            text.put("{{REFERENCE_IMAGE}}", referenceImageFilename == null ? "" : referenceImageFilename);

            Map<String, Number> numeric = new LinkedHashMap<>();
            numeric.put("{{SEED}}", seed);
            numeric.put("{{WIDTH}}", width);
            numeric.put("{{HEIGHT}}", height);
            numeric.put("{{STEPS}}", steps);
            numeric.put("{{CFG}}", cfg);

            return WorkflowTemplateFiller.fill(mapper, workflowName, text, numeric);
        }
    }
}
