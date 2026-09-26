package com.aistorystudio.service;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.domain.Scene;
import com.aistorystudio.domain.StoryBible;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a draft before any expensive production begins (spec section 32).
 * If the LLM-driven scorer fails for any reason, falls back to a conservative
 * heuristic score so the approval screen never blocks on a quality-engine outage.
 */
@Service
public class QualityEngineService {

    private static final Logger log = LoggerFactory.getLogger(QualityEngineService.class);

    private final ProviderGateway providerGateway;
    private final int minScore;
    private final ObjectMapper mapper = new ObjectMapper();

    public QualityEngineService(ProviderGateway providerGateway,
                                 @Value("${studio.quality.minScore:70}") int minScore) {
        this.providerGateway = providerGateway;
        this.minScore = minScore;
    }

    public record QualityResult(int score, List<String> feedback, boolean belowThreshold) {}

    public QualityResult evaluateDraft(StoryBible bible, List<Scene> scenes) {
        return evaluateDraft(bible, scenes, null);
    }

    public QualityResult evaluateDraft(StoryBible bible, List<Scene> scenes, String modelOverride) {
        try {
            String system = """
                You are a strict children's-content quality reviewer. Score the story 0-100 on:
                coherence, originality, age appropriateness, emotional engagement, humor, pacing,
                hook strength, ending, and visual variety across scenes. Respond with ONLY JSON:
                {"score": number, "feedback": [string, string, string]}
                """;
            String user = "Title: " + bible.getTitle() + "\nLogline: " + bible.getLogline()
                    + "\nNarration: " + bible.getFullNarration() + "\nScene count: " + scenes.size();

            String raw = providerGateway.llm().generateStructured(system, user, modelOverride);
            JsonNode node = mapper.readTree(cleanJson(raw));
            int score = node.path("score").asInt(75);
            List<String> feedback = new ArrayList<>();
            node.path("feedback").forEach(f -> feedback.add(f.asText()));
            return new QualityResult(score, feedback, score < minScore);
        } catch (Exception e) {
            log.warn("Quality engine scoring failed, using heuristic fallback: {}", e.getMessage());
            int heuristic = heuristicScore(bible, scenes);
            return new QualityResult(heuristic, List.of("Automated quality review unavailable; heuristic score shown."), heuristic < minScore);
        }
    }

    private int heuristicScore(StoryBible bible, List<Scene> scenes) {
        int score = 60;
        if (bible.getFullNarration() != null && bible.getFullNarration().length() > 200) score += 10;
        if (scenes.size() >= 5) score += 10;
        if (bible.getTitle() != null && !bible.getTitle().isBlank()) score += 5;
        return Math.min(95, score);
    }

    private String cleanJson(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n", "").replaceAll("```$", "");
        }
        return cleaned;
    }
}
