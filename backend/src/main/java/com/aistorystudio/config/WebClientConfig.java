package com.aistorystudio.config;

import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Raises WebClient's default 256KB in-memory response buffer limit, which is
 * far too small for this app's actual payloads: WAV narration audio,
 * generated PNG images, and occasionally large structured-JSON story
 * responses from the LLM all routinely exceed it. Applied globally via
 * WebClientCustomizer so every provider's injected WebClient.Builder picks
 * it up automatically, rather than needing to be repeated in each provider.
 */
@Configuration
public class WebClientConfig {

    private static final int MAX_IN_MEMORY_SIZE_BYTES = 50 * 1024 * 1024; // 50MB

    @Bean
    public WebClientCustomizer webClientCustomizer() {
        return builder -> builder.codecs(configurer ->
                configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE_BYTES));
    }
}
