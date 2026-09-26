package com.aistorystudio.controller;

import com.aistorystudio.config.ProviderGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Lets the UI populate a model dropdown from whatever is actually installed
 * on the connected Ollama server, instead of being stuck with one configured
 * default model.
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ProviderGateway providerGateway;

    public ModelController(ProviderGateway providerGateway) {
        this.providerGateway = providerGateway;
    }

    @GetMapping("/ollama")
    public Map<String, Object> listOllamaModels() {
        boolean healthy = providerGateway.llm().healthCheck();
        List<String> models = healthy ? providerGateway.llm().listModels() : List.of();
        return Map.of(
                "healthy", healthy,
                "defaultModel", providerGateway.llm().defaultModel(),
                "models", models
        );
    }
}
