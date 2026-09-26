package com.aistorystudio.animation;

import com.aistorystudio.domain.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Decides which {@link AnimationProvider} animates a given scene (spec
 * sections 3-4, 21-22).
 *
 * Priority, matching the spec's own table:
 * <pre>
 *   NORMAL    -&gt; 2.5D always (cheap, sufficient, never fails)
 *   IMPORTANT -&gt; local AI if available, else 2.5D
 *   HERO      -&gt; cloud AI if available, else local AI if available, else 2.5D
 * </pre>
 * A scene longer than {@code studio.animation.max-ai-scene-seconds} skips
 * the AI tiers regardless of importance or availability - a five-minute
 * "hero" scene is not what a hero-shot budget is for.
 *
 * Every decision carries a plain-language reason (spec section 55's
 * transparency requirement: "Using: 2.5D / Reason: ..."), and this is the
 * only class that should ever ask a provider {@link AnimationProvider#isAvailable()}.
 */
@Service
public class AnimationDecisionService {

    private static final Logger log = LoggerFactory.getLogger(AnimationDecisionService.class);

    public record AnimationDecision(String providerId, String providerDisplayName, String reason) {}

    private final TwoPointFiveDAnimationProvider twoPointFiveD;
    private final LocalAIAnimationProvider localAi;
    private final CloudAIAnimationProvider cloudAi;
    private final double maxAiSceneSeconds;

    public AnimationDecisionService(TwoPointFiveDAnimationProvider twoPointFiveD,
                                    LocalAIAnimationProvider localAi,
                                    CloudAIAnimationProvider cloudAi,
                                    @Value("${studio.animation.max-ai-scene-seconds:12}") double maxAiSceneSeconds) {
        this.twoPointFiveD = twoPointFiveD;
        this.localAi = localAi;
        this.cloudAi = cloudAi;
        this.maxAiSceneSeconds = maxAiSceneSeconds;
    }

    public AnimationDecision decide(Scene scene) {
        String importance = scene.getImportance() == null ? "NORMAL" : scene.getImportance();
        double duration = scene.getImageDurationSeconds() == null ? 0 : scene.getImageDurationSeconds();

        AnimationDecision decision = decideInternal(importance, duration);
        log.info("Scene {} ({}, {}s): animation = {} - {}",
                scene.getSceneNumber(), importance, fmt(duration), decision.providerId(), decision.reason());
        return decision;
    }

    private AnimationDecision decideInternal(String importance, double duration) {
        if (duration > maxAiSceneSeconds && !"NORMAL".equals(importance)) {
            return fallback("Scene is " + fmt(duration) + "s, over the "
                    + fmt(maxAiSceneSeconds) + "s AI-animation budget - using 2.5D regardless of importance.");
        }

        if ("HERO".equals(importance)) {
            if (cloudAi.isAvailable()) {
                return new AnimationDecision(cloudAi.id(), cloudAi.displayName(),
                        "Hero scene, and a cloud animation provider is configured.");
            }
            if (localAi.isAvailable()) {
                return new AnimationDecision(localAi.id(), localAi.displayName(),
                        "Hero scene, no cloud provider configured, but local AI animation is available.");
            }
            return fallback("Hero scene, but no local or cloud animation provider is configured - "
                    + "using the strongest 2.5D treatment available (see FFmpegProcessor's HERO handling).");
        }

        if ("IMPORTANT".equals(importance)) {
            if (localAi.isAvailable()) {
                return new AnimationDecision(localAi.id(), localAi.displayName(),
                        "Important scene, and local AI animation is available.");
            }
            return fallback("Important scene, but no local AI animation provider is configured.");
        }

        return fallback("Standard scene - 2.5D (camera motion, parallax, particles) is efficient and sufficient.");
    }

    private AnimationDecision fallback(String reason) {
        return new AnimationDecision(twoPointFiveD.id(), twoPointFiveD.displayName(), reason);
    }

    private String fmt(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.1f", seconds);
    }
}
