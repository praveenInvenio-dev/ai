package com.aistorystudio.util;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class ClasspathResources {

    private ClasspathResources() {}

    public static String readWorkflow(String workflowName) {
        String safeName = workflowName.replaceAll("[^a-zA-Z0-9_-]", "");
        ClassPathResource resource = new ClassPathResource("comfyui-workflows/" + safeName + ".json");
        try {
            return Files.readString(resource.getFile().toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            try (var in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("ComfyUI workflow template not found: " + safeName, ex);
            }
        }
    }
}
