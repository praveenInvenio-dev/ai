package com.aistorystudio.provider;

public interface VisionProvider {

    /** Analyze an uploaded reference image and produce a canonical character description. */
    String describeCharacterImage(byte[] imageBytes, String mimeType);

    /** Optional semantic image QA. Providers that do not support vision return a pass. */
    default ImageValidationResult validateGeneratedImage(byte[] imageBytes, String expectedPrompt,
                                                          String negativePrompt, String visualStyle,
                                                          String storyContext) {
        return new ImageValidationResult(true, 1.0, "Vision QA is not enabled for this provider.");
    }

    boolean healthCheck();
    String providerName();

    record ImageValidationResult(boolean passed, double confidence, String feedback) {}
}
