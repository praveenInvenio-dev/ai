package com.aistorystudio.controller;

import com.aistorystudio.config.ProviderGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final ProviderGateway providerGateway;

    public HealthController(ProviderGateway providerGateway) {
        this.providerGateway = providerGateway;
    }

    @GetMapping
    public Map<String, Object> overall() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("demoMode", providerGateway.isDemoMode());
        result.put("ollama", safeCheck(() -> providerGateway.llm().healthCheck()));
        result.put("vision", safeCheck(() -> providerGateway.vision().healthCheck()));
        result.put("comfyui", safeCheck(providerGateway::imageProviderHealthy));
        result.put("tts", safeCheck(providerGateway::ttsProviderHealthy));
        if (providerGateway.isDemoMode()) {
            result.put("note", "demoMode is true - images/audio use placeholders regardless of comfyui/tts status above");
        }
        return result;
    }

    @GetMapping("/ollama")
    public Map<String, Object> ollama() {
        return Map.of("healthy", safeCheck(() -> providerGateway.llm().healthCheck()));
    }

    @GetMapping("/comfyui")
    public Map<String, Object> comfyui() {
        boolean healthy = safeCheck(providerGateway::imageProviderHealthy);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("healthy", healthy);
        if (providerGateway.isDemoMode()) {
            result.put("note", "demoMode is true - this reachability check passing doesn't mean real images are being generated yet");
        }
        return result;
    }

    @GetMapping("/tts")
    public Map<String, Object> tts() {
        boolean healthy = safeCheck(providerGateway::ttsProviderHealthy);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("healthy", healthy);
        if (providerGateway.isDemoMode()) {
            result.put("note", "demoMode is true - this reachability check passing doesn't mean real audio is being generated yet");
        }
        return result;
    }

    private boolean safeCheck(java.util.function.BooleanSupplier check) {
        try {
            return check.getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }
}
