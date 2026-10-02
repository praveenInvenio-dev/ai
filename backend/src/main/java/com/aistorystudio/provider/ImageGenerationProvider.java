package com.aistorystudio.provider;

public interface ImageGenerationProvider {

    ImageGenerationResult generateImage(ImageGenerationRequest request);

    boolean healthCheck();

    String providerName();

    record ImageGenerationRequest(
            String prompt,
            String negativePrompt,
            int width,
            int height,
            int steps,
            double cfg,
            Long seed,
            String workflow,
            String model,
            String referenceImagePath,
            /** Null for a single-character (or no-reference) scene. Set only
             *  when exactly 2 characters in the scene each have their own
             *  locked reference - triggers the regional dual-IPAdapter
             *  workflow (character-consistent-story-ipadapter-sdxl-dual.json)
             *  instead of the single-reference one. First reference is the
             *  LEFT half of frame, second is RIGHT - see that workflow's own
             *  comment for the ordering heuristic. */
            String referenceImagePath2
    ) {}

    record ImageGenerationResult(
            byte[] imageBytes,
            String fileExtension,
            long seedUsed,
            String modelUsed,
            String workflowUsed
    ) {}
}
