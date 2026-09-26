package com.aistorystudio.videogen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory job table for the standalone Video Generation page. Same
 * retention idea as {@code VideoEditorCleanupService} (spec's own precedent
 * for "don't let generated media accumulate on disk forever"), just scoped
 * to this feature's own jobs/files instead of the video editor's.
 */
@Component
@EnableScheduling
public class VideoGenJobStore {

    private static final Logger log = LoggerFactory.getLogger(VideoGenJobStore.class);

    private final Map<UUID, VideoGenJob> jobs = new ConcurrentHashMap<>();
    private final Duration retention;

    public VideoGenJobStore(@Value("${studio.video-generation.retention-hours:24}") long retentionHours) {
        this.retention = Duration.ofHours(retentionHours);
    }

    public VideoGenJob create() {
        VideoGenJob job = new VideoGenJob(UUID.randomUUID());
        jobs.put(job.getId(), job);
        return job;
    }

    public VideoGenJob get(UUID id) {
        return jobs.get(id);
    }

    /** Sweeps jobs (and their result files) older than the retention window.
     *  Same hourly cadence as the video editor's cleanup, for the same
     *  reason: frequent enough that disk usage never builds up noticeably,
     *  rare enough that it is not worth its own configurable interval. */
    @Scheduled(fixedRate = 60 * 60 * 1000L)
    public void cleanup() {
        Instant cutoff = Instant.now().minus(retention);
        jobs.entrySet().removeIf(entry -> {
            VideoGenJob job = entry.getValue();
            boolean expired = job.getCreatedAt().isBefore(cutoff);
            if (expired && job.getResultVideoPath() != null) {
                deleteQuietly(job.getResultVideoPath());
            }
            return expired;
        });
    }

    private void deleteQuietly(String path) {
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException e) {
            log.warn("Could not delete expired video-generation result at {}: {}", path, e.getMessage());
        }
    }
}
