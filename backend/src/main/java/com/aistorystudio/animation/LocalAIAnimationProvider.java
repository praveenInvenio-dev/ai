package com.aistorystudio.animation;

import com.aistorystudio.provider.VideoGenerationProvider;
import org.springframework.stereotype.Component;

/**
 * Local image-to-video via ComfyUI's native Wan support (see
 * com.aistorystudio.provider.ComfyUIVideoProvider and
 * comfyui-workflows/wan-ti2v-5b-image-to-video.json / wan22-i2v-a14b.json for the actual implementation
 * and its requirements). Not viable on the 2GB-VRAM reference hardware this
 * project's 2.5D-first design targets - this is explicitly for deployment on
 * separate, higher-VRAM hardware, per the person's own instruction. This
 * class itself does no ComfyUI work; it only asks the real video provider
 * whether it's genuinely configured and available, and passes that answer
 * through - the same honesty discipline as before, just now backed by a
 * real implementation instead of a permanent "not yet built".
 */
@Component
public class LocalAIAnimationProvider implements AnimationProvider {

    private final VideoGenerationProvider videoGenerationProvider;

    public LocalAIAnimationProvider(VideoGenerationProvider videoGenerationProvider) {
        this.videoGenerationProvider = videoGenerationProvider;
    }

    @Override
    public String id() {
        return "local-ai";
    }

    @Override
    public String displayName() {
        return "Local AI (image-to-video)";
    }

    @Override
    public boolean isAvailable() {
        return videoGenerationProvider.isAvailable();
    }

    @Override
    public String unavailableReason() {
        return videoGenerationProvider.unavailableReason();
    }
}
