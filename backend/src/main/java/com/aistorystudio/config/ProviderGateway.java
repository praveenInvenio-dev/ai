package com.aistorystudio.config;

import com.aistorystudio.provider.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single entry point the pipeline uses to reach the LLM/image/TTS providers.
 *
 * - In DEMO_MODE, mock providers are used directly (fast, no GPU/models required).
 * - Otherwise the real provider (Ollama/ComfyUI/local TTS) is tried first; if it is
 *   unreachable or fails, the call transparently falls back to the mock provider so
 *   the rest of the pipeline (timing, editing, video assembly, packaging) still works
 *   and the UI can surface a "provider degraded" warning instead of hard-failing.
 */
@Component
public class ProviderGateway {

    private static final Logger log = LoggerFactory.getLogger(ProviderGateway.class);

    private final boolean demoMode;
    private final boolean offlineMode;
    private final boolean imageFallbackEnabled;
    private final StoryLLMProvider llmProvider;
    private final ImageGenerationProvider imageProvider;
    private final ImageGenerationProvider mockImageProvider;
    private final TextToSpeechProvider ttsProvider;
    private final TextToSpeechProvider mockTtsProvider;
    private final VisionProvider visionProvider;
    private final VideoGenerationProvider videoGenerationProvider;

    public ProviderGateway(
            @Value("${studio.demoMode:false}") boolean demoMode,
            @Value("${studio.comfyui.fallbackToPlaceholder:false}") boolean imageFallbackEnabled,
            OllamaLLMProvider llmProvider,
            ComfyUIImageProvider imageProvider,
            MockImageGenerationProvider mockImageProvider,
            LocalTTSProvider localTtsProvider,
            SarvamTTSProvider sarvamTtsProvider,
            @Value("${studio.tts.provider:piper}") String ttsProviderName,
            @Value("${studio.offlineMode:false}") boolean offlineMode,
            MockTTSProvider mockTtsProvider,
            VisionProvider visionProvider,
            VideoGenerationProvider videoGenerationProvider) {
        this.demoMode = demoMode;
        this.imageFallbackEnabled = imageFallbackEnabled;
        this.llmProvider = llmProvider;
        this.imageProvider = imageProvider;
        this.mockImageProvider = mockImageProvider;
        this.offlineMode = offlineMode;
        // Selected by config rather than @Primary so switching engines is a .env
        // change and both beans stay available for the /api/tts endpoints.
        // OFFLINE_MODE (spec section 34) refuses Sarvam even if explicitly
        // configured - it's a real hosted cloud API, not a local process -
        // and falls back to the always-local Piper path instead of failing
        // startup, since a config mistake here shouldn't crash the app.
        if ("sarvam".equalsIgnoreCase(ttsProviderName) && offlineMode) {
            log.warn("studio.tts.provider=sarvam but OFFLINE_MODE=true - using local TTS instead. "
                    + "Sarvam is a hosted cloud API and OFFLINE_MODE explicitly disallows external calls.");
        }
        this.ttsProvider = ("sarvam".equalsIgnoreCase(ttsProviderName) && !offlineMode)
                ? sarvamTtsProvider
                : localTtsProvider;
        log.info("TTS provider: {}", this.ttsProvider.providerName());
        this.mockTtsProvider = mockTtsProvider;
        this.visionProvider = visionProvider;
        this.videoGenerationProvider = videoGenerationProvider;
    }

    public StoryLLMProvider llm() {
        return llmProvider;
    }

    public VisionProvider vision() {
        return visionProvider;
    }

    public ImageGenerationProvider.ImageGenerationResult generateImage(ImageGenerationProvider.ImageGenerationRequest request) {
        if (demoMode) {
            return mockImageProvider.generateImage(request);
        }
        try {
            return imageProvider.generateImage(request);
        } catch (Exception e) {
            if (!imageFallbackEnabled) {
                throw new IllegalStateException("ComfyUI image generation failed: " + e.getMessage(), e);
            }
            // ERROR, not WARN: a placeholder that silently replaces a real image is
            // the difference between "the video looks wrong" and "ComfyUI timed out",
            // and that distinction was previously buried at WARN level.
            log.error("ComfyUI image generation failed ({}). Falling back to a PLACEHOLDER image - "
                    + "the output video will not contain a real generated frame for this scene. "
                    + "Set COMFYUI_FALLBACK_PLACEHOLDER=false to fail the job instead.", e.getMessage(), e);
            return mockImageProvider.generateImage(request);
        }
    }

    /** No demo-mode fallback here on purpose: a failed AI video call should
     *  fall back to the 2.5D rendering path, which is a fundamentally
     *  different mechanism (not a same-shape mock provider like images
     *  have) - that decision belongs to the caller orchestrating the scene
     *  render, not to this gateway. isAvailable() should always be checked
     *  before calling this. */
    public VideoGenerationProvider.VideoGenerationResult generateVideo(VideoGenerationProvider.VideoGenerationRequest request) {
        return videoGenerationProvider.generateVideo(request);
    }

    public boolean isLocalAiVideoAvailable() {
        return videoGenerationProvider.isAvailable();
    }

    /** Short, specific reason local AI video is unavailable - null if it is
     *  available. Exposed alongside isLocalAiVideoAvailable() so callers (the
     *  standalone Video Generation page in particular) can show the operator
     *  *why* rather than just a disabled button. */
    public String localAiVideoUnavailableReason() {
        return videoGenerationProvider.unavailableReason();
    }

    public TextToSpeechProvider.TtsResult synthesize(TextToSpeechProvider.TtsRequest request) {
        if (demoMode) {
            return mockTtsProvider.synthesize(request);
        }
        try {
            return ttsProvider.synthesize(request);
        } catch (Exception e) {
            log.warn("TTS provider '{}' failed ({}). Falling back to silent placeholder audio.",
                    ttsProvider.providerName(), e.getMessage());
            return mockTtsProvider.synthesize(request);
        }
    }

    public boolean isDemoMode() {
        return demoMode;
    }

    /** Real ComfyUI reachability, independent of demo mode - used by /api/health/comfyui. */
    public boolean imageProviderHealthy() {
        return imageProvider.healthCheck();
    }

    /** Real TTS sidecar reachability, independent of demo mode - used by /api/health/tts. */
    public boolean ttsProviderHealthy() {
        return ttsProvider.healthCheck();
    }
}
