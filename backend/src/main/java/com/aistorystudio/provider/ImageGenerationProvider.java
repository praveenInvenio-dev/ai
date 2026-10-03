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
            /** Second locked character reference (becomes <image2> in the Qwen
             *  prompt), or null. workflow/model above are ignored by the Qwen
             *  provider - there is one image engine - and kept only for the mock. */
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
