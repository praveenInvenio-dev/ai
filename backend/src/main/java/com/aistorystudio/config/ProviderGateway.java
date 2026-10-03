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
    // Kept individually, in addition to the single selected ttsProvider above,
    // so VoiceProfile-scoped calls (synthesizeWithVoice) can pick per-call by
    // the profile's own stored provider, independent of the app-wide
    // TTS_PROVIDER default a given episode/preview would otherwise use.
    private final TextToSpeechProvider localTtsProvider;
    private final TextToSpeechProvider chatterboxTtsProvider;
    private final TextToSpeechProvider cosyVoiceTtsProvider;
    private final TextToSpeechProvider sarvamTtsProvider;
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
            ChatterboxTTSProvider chatterboxTtsProvider,
            CosyVoiceTTSProvider cosyVoiceTtsProvider,
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
        // ChatterBox runs locally (tts-chatterbox sidecar) same as Piper, so
        // OFFLINE_MODE has no reason to refuse it the way it refuses Sarvam.
        if ("chatterbox".equalsIgnoreCase(ttsProviderName)) {
            this.ttsProvider = chatterboxTtsProvider;
        } else if (("cosyvoice".equalsIgnoreCase(ttsProviderName) || "cosyvoice3".equalsIgnoreCase(ttsProviderName))) {
            this.ttsProvider = cosyVoiceTtsProvider;
        } else if ("sarvam".equalsIgnoreCase(ttsProviderName) && !offlineMode) {
            this.ttsProvider = sarvamTtsProvider;
        } else {
            this.ttsProvider = localTtsProvider;
        }
        log.info("TTS provider: {}", this.ttsProvider.providerName());
        this.localTtsProvider = localTtsProvider;
        this.chatterboxTtsProvider = chatterboxTtsProvider;
        this.cosyVoiceTtsProvider = cosyVoiceTtsProvider;
        this.sarvamTtsProvider = sarvamTtsProvider;
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

    /** VoiceProfile-scoped synthesis. A saved voice is a real user-selected asset:
     * provider failures must surface instead of silently replacing it with the
     * mock WAV. The normal app-wide TTS path below may still use the mock fallback
     * for demo/offline pipeline execution. */
    public TextToSpeechProvider.TtsResult synthesizeWithVoice(String provider, String voiceName, String text,
                                                                String language, double speed, double pitch) {
        TextToSpeechProvider selected = selectTtsProvider(provider);
        TextToSpeechProvider.TtsRequest request = new TextToSpeechProvider.TtsRequest(text, voiceName, language, speed, pitch);
        if (demoMode) {
            throw new IllegalStateException("Saved voice preview/narration requires DEMO_MODE=false and a real local TTS provider.");
        }
        try {
            TextToSpeechProvider.TtsResult result = selected.synthesize(request);
            if (result == null || result.audioBytes() == null || result.audioBytes().length < 1000) {
                throw new IllegalStateException("Voice provider returned empty audio.");
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Voice provider '" + selected.providerName() + "' failed: " + e.getMessage(), e);
        }
    }

    public TextToSpeechProvider.TtsResult synthesizeWithVoice(String provider, String voiceName, String text,
                                                                String language, double speed, double pitch,
                                                                String emotion, Double emotionIntensity, String delivery,
                                                                java.util.List<String> emphasis, Boolean breath,
                                                                String paralinguisticEvent, String actingDirection,
                                                                String referenceTranscript) {
        TextToSpeechProvider selected = selectTtsProvider(provider);
        TextToSpeechProvider.TtsRequest request = new TextToSpeechProvider.TtsRequest(
                text, voiceName, language, speed, pitch, emotion, emotionIntensity, delivery,
                emphasis == null ? java.util.List.of() : emphasis, breath, paralinguisticEvent, actingDirection, referenceTranscript);
        if (demoMode) {
            throw new IllegalStateException("Saved voice narration requires DEMO_MODE=false and a real local TTS provider.");
        }
        try {
            TextToSpeechProvider.TtsResult result = selected.synthesize(request);
            if (result == null || result.audioBytes() == null || result.audioBytes().length < 1000) {
                throw new IllegalStateException("Voice provider returned empty audio.");
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Voice provider '" + selected.providerName() + "' failed: " + e.getMessage(), e);
        }
    }

    public TextToSpeechProvider.TtsResult synthesizeWithVoice(String provider, String voiceName, String text,
                                                                String language, double speed, double pitch,
                                                                String emotion, Double emotionIntensity, String delivery,
                                                                java.util.List<String> emphasis, Boolean breath,
                                                                String paralinguisticEvent, String actingDirection) {
        TextToSpeechProvider selected = selectTtsProvider(provider);
        TextToSpeechProvider.TtsRequest request = new TextToSpeechProvider.TtsRequest(
                text, voiceName, language, speed, pitch, emotion, emotionIntensity, delivery,
                emphasis == null ? java.util.List.of() : emphasis, breath, paralinguisticEvent, actingDirection, null);
        if (demoMode) return mockTtsProvider.synthesize(request);
        try {
            return selected.synthesize(request);
        } catch (Exception e) {
            String warning = "Voice provider '" + selected.providerName() + "' failed (" + e.getMessage()
                    + "); this line used a silent placeholder instead of the assigned voice.";
            log.warn(warning);
            var mock = mockTtsProvider.synthesize(request);
            return new TextToSpeechProvider.TtsResult(mock.audioBytes(), mock.durationSeconds(), mock.format(), warning);
        }
    }

    private TextToSpeechProvider selectTtsProvider(String provider) {
        if ("chatterbox".equalsIgnoreCase(provider)) return chatterboxTtsProvider;
        if ("cosyvoice".equalsIgnoreCase(provider) || "cosyvoice3".equalsIgnoreCase(provider)) return cosyVoiceTtsProvider;
        if ("sarvam".equalsIgnoreCase(provider) && !offlineMode) return sarvamTtsProvider;
        return localTtsProvider;
    }

    /** Sample/preview overload - default prosody, no per-line speed/pitch to
     *  carry. Real narration synthesis (ProductionPipelineService) uses the
     *  6-arg version above so scene-level prosody still applies to an
     *  assigned VoiceProfile exactly as it would to the app-wide default. */
    /** Strict voice-preview path: never replace a failed cloned voice with the silent mock.
     * The caller needs the real provider error so a saved voice can never appear playable while
     * actually containing silence. Production narration keeps the existing graceful fallback. */
    public TextToSpeechProvider.TtsResult synthesizeWithVoiceStrict(String provider, String voiceName, String text,
                                                                     String language, String referenceTranscript) {
        TextToSpeechProvider selected = selectTtsProvider(provider);
        TextToSpeechProvider.TtsRequest request = new TextToSpeechProvider.TtsRequest(
                text, voiceName, language, 1.0, 1.0, "neutral", 0.5, "natural",
                java.util.List.of(), false, null, null, referenceTranscript);
        if (demoMode) {
            throw new IllegalStateException("Voice preview is disabled in DEMO_MODE; select a real TTS provider.");
        }
        try {
            TextToSpeechProvider.TtsResult result = selected.synthesize(request);
            if (result == null || result.audioBytes() == null || result.audioBytes().length < 1000) {
                throw new IllegalStateException("Voice provider returned empty audio.");
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Voice provider '" + selected.providerName() + "' failed: " + e.getMessage(), e);
        }
    }

    public TextToSpeechProvider.TtsResult synthesizeWithVoice(String provider, String voiceName, String text) {
        return synthesizeWithVoice(provider, voiceName, text, null, 1.0, 1.0);
    }

    public TextToSpeechProvider.TtsResult synthesize(TextToSpeechProvider.TtsRequest request) {
        if (demoMode) {
            return mockTtsProvider.synthesize(request);
        }
        try {
            return ttsProvider.synthesize(request);
        } catch (Exception e) {
            String warning = "TTS provider '" + ttsProvider.providerName() + "' failed (" + e.getMessage()
                    + "); this line used a silent placeholder instead.";
            log.warn(warning);
            var mock = mockTtsProvider.synthesize(request);
            return new TextToSpeechProvider.TtsResult(mock.audioBytes(), mock.durationSeconds(), mock.format(), warning);
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
