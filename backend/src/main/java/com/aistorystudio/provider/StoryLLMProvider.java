package com.aistorystudio.provider;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Abstraction over any local/remote LLM used for story generation,
 * scene planning, prompt generation, continuity reasoning and quality checks.
 *
 * Every generation call accepts an optional per-call model override so the UI
 * can let the user pick from whatever models are actually installed, instead
 * of being locked to the single OLLAMA_MODEL default in configuration.
 */
public interface StoryLLMProvider {

    /** Plain free-text generation using the configured default model. */
    default String generate(String systemPrompt, String userPrompt) {
        return generate(systemPrompt, userPrompt, null);
    }

    /** Plain free-text generation. modelOverride may be null to use the configured default. */
    String generate(String systemPrompt, String userPrompt, String modelOverride);

    /**
     * Generation constrained to return valid JSON matching the caller's expected shape,
     * using the configured default model.
     */
    default String generateStructured(String systemPrompt, String userPrompt) {
        return generateStructured(systemPrompt, userPrompt, null);
    }

    /**
     * Generation constrained to return valid JSON matching the caller's expected shape.
     * modelOverride may be null to use the configured default. The caller is responsible
     * for parsing/validating the returned JSON string.
     */
    String generateStructured(String systemPrompt, String userPrompt, String modelOverride);

    /** Token-streamed generation, used for live UI feedback. */
    Flux<String> stream(String systemPrompt, String userPrompt, String modelOverride);

    /** Names of models currently installed/pulled on the backing LLM server. */
    List<String> listModels();

    boolean healthCheck();

    String providerName();

    /** The model used when no per-call override is supplied. */
    String defaultModel();
}
