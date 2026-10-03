package com.aistorystudio.provider;

/**
 * Real local AI image-to-video generation (spec: "AI VIDEO" tier of the
 * three-level animation engine - see com.aistorystudio.animation). Unlike
 * {@link com.aistorystudio.animation.LocalAIAnimationProvider}, which only
 * answers "is this configured and available", this is the provider that
 * actually talks to ComfyUI and produces a video file.
 *
 * Deliberately image-to-video, not pure text-to-video: the starting image is
 * the scene's own already-generated image, which itself may already be
 * character-consistent (via IPAdapter reference-conditioning, see
 * ComfyUIImageProvider). Animating that exact frame is what keeps a
 * character looking like the same character in motion, rather than the
 * video model re-imagining them from text alone on every call.
 */
public interface VideoGenerationProvider {

    record VideoGenerationRequest(
            String startingImagePath,
            String prompt,
            String negativePrompt,
            double durationSeconds,
            int width,
            int height,
            String workflow,
            String voiceReferenceAudioPath,
            Long seed,
            /** 0 = use the provider's configured default (LOCAL_AI_ANIMATION_STEPS) -
             *  same "0 means default" convention ImageGenerationRequest already
             *  uses for width/height/steps, not a new pattern. */
            int steps
    ) {}

    record VideoGenerationResult(
            byte[] videoBytes,
            String fileExtension,
            long seedUsed,
            String workflowUsed
    ) {}

    VideoGenerationResult generateVideo(VideoGenerationRequest request);

    /** Real check (configuration + reachability), never hard-coded true -
     *  callers must be able to trust this before spending a HERO-scene
     *  budget on a call that was never going to work. */
    boolean isAvailable();

    /** Short, specific reason generation is unavailable - null if it is. */
    String unavailableReason();
}
