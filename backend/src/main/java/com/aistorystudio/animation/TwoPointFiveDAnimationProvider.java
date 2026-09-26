package com.aistorystudio.animation;

import org.springframework.stereotype.Component;

/**
 * The always-available tier. Its actual rendering work - Ken Burns camera
 * motion, parallax, environment particles and matching ambience - already
 * lives in {@code FFmpegProcessor}, which is real, tested, and runs on this
 * one whether or not any AI provider is ever configured. This class exists
 * so {@link AnimationDecisionService} has a uniform way to name and reason
 * about "the fallback that never fails" alongside the other two tiers,
 * without duplicating or wrapping FFmpegProcessor's rendering logic.
 */
@Component
public class TwoPointFiveDAnimationProvider implements AnimationProvider {

    @Override
    public String id() {
        return "2.5d";
    }

    @Override
    public String displayName() {
        return "2.5D (camera motion + parallax)";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return null;
    }
}
