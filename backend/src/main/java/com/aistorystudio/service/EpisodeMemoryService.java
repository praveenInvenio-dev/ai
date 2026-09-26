package com.aistorystudio.service;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.domain.Episode;
import com.aistorystudio.domain.EpisodeMemory;
import com.aistorystudio.domain.StoryBible;
import com.aistorystudio.repository.EpisodeMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * After a completed episode, distills a COMPACT memory (events, character development,
 * new locations/objects, unresolved threads, visual changes) instead of ever sending
 * full episode history back into the LLM for future episodes (spec section 4/59).
 */
@Service
public class EpisodeMemoryService {

    private static final Logger log = LoggerFactory.getLogger(EpisodeMemoryService.class);

    private final ProviderGateway providerGateway;
    private final EpisodeMemoryRepository episodeMemoryRepository;

    public EpisodeMemoryService(ProviderGateway providerGateway, EpisodeMemoryRepository episodeMemoryRepository) {
        this.providerGateway = providerGateway;
        this.episodeMemoryRepository = episodeMemoryRepository;
    }

    public void recordMemory(Episode episode, StoryBible bible) {
        if (episode.getUniverseId() == null) return; // standalone stories don't need continuity memory
        try {
            String system = """
                Summarize this completed episode into a COMPACT JSON memory for future episodes.
                Respond with ONLY JSON: {"characters": [string], "locations": [string],
                "objects": [string], "unresolved": [string], "visualChanges": [string]}
                Keep every array short (max 5 items) and each item under 15 words.
                """;
            String user = "Title: " + bible.getTitle() + "\nNarration: " + bible.getFullNarration();
            String raw = providerGateway.llm().generateStructured(system, user, episode.getOllamaModel());

            EpisodeMemory memory = new EpisodeMemory();
            memory.setUniverseId(episode.getUniverseId());
            memory.setEpisodeId(episode.getId());
            memory.setSummaryJson(cleanJson(raw));
            episodeMemoryRepository.save(memory);
        } catch (Exception e) {
            log.warn("Failed to record episode memory for episode {}: {}", episode.getId(), e.getMessage());
        }
    }

    private String cleanJson(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n", "").replaceAll("```$", "");
        }
        return cleaned;
    }
}
