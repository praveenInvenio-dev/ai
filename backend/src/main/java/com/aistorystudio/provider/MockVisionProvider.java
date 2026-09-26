package com.aistorystudio.provider;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Placeholder vision analyzer. Swap for a real local vision-language model
 * (e.g. an Ollama vision model such as llava) by implementing VisionProvider
 * and marking it @Primary.
 */
@Component
@ConditionalOnProperty(name = "studio.vision.provider", havingValue = "mock", matchIfMissing = true)
public class MockVisionProvider implements VisionProvider {

    @Override
    public String describeCharacterImage(byte[] imageBytes, String mimeType) {
        return "Character reference uploaded (" + imageBytes.length + " bytes, " + mimeType + "). "
                + "Please refine this auto-generated description with the character's species, "
                + "colors, proportions, clothing and distinguishing features.";
    }

    @Override
    public boolean healthCheck() {
        return true;
    }

    @Override
    public String providerName() {
        return "mock-vision-provider";
    }
}
