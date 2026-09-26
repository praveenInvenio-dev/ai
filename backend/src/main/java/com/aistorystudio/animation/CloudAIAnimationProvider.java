package com.aistorystudio.animation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A generic cloud image-to-video provider slot (spec section 20: "do not
 * hard-code one vendor"). Genuinely available once both
 * {@code studio.animation.cloud.api-url} and {@code ...api-key} are set to a
 * real endpoint - unlike {@link LocalAIAnimationProvider}, that part is a
 * real, meaningful check. What is NOT implemented yet is the actual HTTP
 * client (submit/poll/download) behind it, so even when this reports
 * available, {@link AnimationDecisionService} callers must still treat a
 * call into this provider as "not yet wired up" until that client exists.
 * The config surface and the honest availability check exist now so adding
 * that client later doesn't require touching the decision logic.
 */
@Component
public class CloudAIAnimationProvider implements AnimationProvider {

    private final String apiUrl;
    private final String apiKey;
    private final boolean offlineMode;

    public CloudAIAnimationProvider(@Value("${studio.animation.cloud.api-url:}") String apiUrl,
                                     @Value("${studio.animation.cloud.api-key:}") String apiKey,
                                     @Value("${studio.offlineMode:false}") boolean offlineMode) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.offlineMode = offlineMode;
    }

    @Override
    public String id() {
        return "cloud-ai";
    }

    @Override
    public String displayName() {
        return "Cloud AI (image-to-video)";
    }

    @Override
    public boolean isAvailable() {
        // OFFLINE_MODE (spec section 34) wins even over a fully-configured
        // endpoint - a cloud call is a cloud call regardless of how ready it
        // is to make one.
        if (offlineMode) {
            return false;
        }
        return apiUrl != null && !apiUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String unavailableReason() {
        if (offlineMode) {
            return "OFFLINE_MODE=true disallows external API calls - cloud animation is a hosted service.";
        }
        if (isAvailable()) {
            return null;
        }
        return "No cloud animation provider configured "
                + "(set studio.animation.cloud.api-url and ...api-key).";
    }
}
