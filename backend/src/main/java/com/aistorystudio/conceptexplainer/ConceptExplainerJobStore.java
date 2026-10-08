package com.aistorystudio.conceptexplainer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConceptExplainerJobStore {
    private final Map<UUID, ConceptExplainerJob> jobs = new ConcurrentHashMap<>();
    private final Duration retention;
    private final Path storageRoot;

    public ConceptExplainerJobStore(@Value("${studio.concept-explainer.retention-hours:24}") long retentionHours,
                                    @Value("${studio.storage.root:/data/projects}") String storageRoot) {
        this.retention = Duration.ofHours(Math.max(1, retentionHours));
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public ConceptExplainerJob create(String topic, String instructions, String language, String duration,
                                      String difficulty, String animationMode, String model, String track, String subject, boolean examFocus, String voice) {
        ConceptExplainerJob job = new ConceptExplainerJob(UUID.randomUUID(), topic, instructions, language,
                duration, difficulty, animationMode, model, track, subject, examFocus, voice);
        jobs.put(job.getId(), job);
        return job;
    }

    public ConceptExplainerJob create(String topic, String instructions, String language, String duration,
                                      String difficulty, String animationMode, String model) {
        return create(topic, instructions, language, duration, difficulty, animationMode, model,
                "GENERAL", "General", false, "narrator-male");
    }

    public ConceptExplainerJob get(UUID id) { return jobs.get(id); }

    /** Newest first - powers the "My lessons" list so a lesson survives leaving the page. */
    public java.util.List<ConceptExplainerJob> list() {
        return jobs.values().stream().sorted(Comparator.comparing(ConceptExplainerJob::getCreatedAt).reversed()).toList();
    }

    @Scheduled(fixedRate = 3600000L)
    public void cleanup() {
        Instant cutoff = Instant.now().minus(retention);
        jobs.entrySet().removeIf(entry -> {
            ConceptExplainerJob job = entry.getValue();
            if (!job.getCreatedAt().isBefore(cutoff)) return false;
            deleteTree(storageRoot.resolve("concept-explainers").resolve(job.getId().toString()));
            return true;
        });
    }

    private void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
