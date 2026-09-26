package com.aistorystudio.animation;

/**
 * One way to turn a scene image into an animated shot.
 *
 * Three implementations exist: {@link TwoPointFiveDAnimationProvider} (always
 * available - FFmpeg camera motion, parallax and particles, all already
 * real and rendering today), {@link LocalAIAnimationProvider} and
 * {@link CloudAIAnimationProvider} (both honestly report themselves
 * unavailable until a real model/endpoint is actually configured - see
 * their own isAvailable() javadoc for exactly what "configured" means).
 *
 * {@link AnimationDecisionService} is the only thing that should choose
 * between these; nothing else should call {@link #isAvailable()} directly.
 */
public interface AnimationProvider {

    /** Stable identifier used in logs, the decision record, and (eventually)
     *  a project's per-scene animation-mode override. */
    String id();

    /** Human-readable label for logs and any future UI. */
    String displayName();

    /**
     * Whether this provider can actually be used right now. Must be a real
     * check against configuration/hardware, never a hard-coded true - a
     * provider that isn't really there must say so rather than being tried
     * and failing later. See {@link #unavailableReason()} for why not.
     */
    boolean isAvailable();

    /** Null when {@link #isAvailable()} is true. A short, specific reason
     *  otherwise (spec section 55: "Using: 2.5D / Reason: ..." transparency),
     *  e.g. "No local image-to-video model configured" rather than a bare
     *  "unavailable". */
    String unavailableReason();
}
