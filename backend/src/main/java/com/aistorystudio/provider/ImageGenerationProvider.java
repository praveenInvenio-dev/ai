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
            String referenceImagePath
    ) {}

    record ImageGenerationResult(
            byte[] imageBytes,
            String fileExtension,
            long seedUsed,
            String modelUsed,
            String workflowUsed
    ) {}
}
