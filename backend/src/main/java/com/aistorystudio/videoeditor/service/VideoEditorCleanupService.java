package com.aistorystudio.videoeditor.service;

import com.aistorystudio.videoeditor.config.VideoEditorProperties;
import com.aistorystudio.videoeditor.repository.VideoEditorProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Sweeps stale preview renders.
 *
 * Previews are cached by timeline hash, which is what makes undo/redo cheap -
 * but it also means every arrangement a user tries leaves a file behind. A
 * session of adjusting cuts can produce dozens, and at 480p they are small
 * individually and significant together. Without this the volume grows until
 * something fails on a full disk, which surfaces as a mystifying render error.
 *
 * Only previews are swept. Final renders persist until their project is
 * deleted, because those are the artefact the user actually wanted.
 *
 * {@code @ConditionalOnProperty} keeps the scheduler out of the context
 * entirely when the editor is disabled, matching the controller - a disabled
 * module should have no moving parts, not merely no endpoints.
 */
@Service
@EnableScheduling
@ConditionalOnProperty(name = "studio.video-editor.enabled", havingValue = "true", matchIfMissing = true)
public class VideoEditorCleanupService {

    private static final Logger log = LoggerFactory.getLogger(VideoEditorCleanupService.class);

    private final VideoEditorProperties properties;
    private final VideoEditorProjectRepository projects;
    private final VideoEditorStorageService storage;

    public VideoEditorCleanupService(VideoEditorProperties properties,
                                     VideoEditorProjectRepository projects,
                                     VideoEditorStorageService storage) {
        this.properties = properties;
        this.projects = projects;
        this.storage = storage;
    }

    /**
     * Hourly, and once shortly after startup.
     *
     * initialDelay rather than immediately: a sweep competing with Flyway and
     * bean initialisation for I/O slows startup for no benefit, and anything
     * stale has already been stale for a while.
     */
    @Scheduled(initialDelay = 5, fixedRate = 60, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void sweepPreviews() {
        Duration keep = Duration.ofHours(Math.max(1, properties.getPreviewRetentionHours()));
        Instant cutoff = Instant.now().minus(keep);
        int deleted = 0;
        long freed = 0;

        List<UUID> projectIds = projects.findAll().stream()
                .map(p -> p.getId()).toList();

        for (UUID projectId : projectIds) {
            Path dir = storage.previewsDir(projectId);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    try {
                        if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                            long size = Files.size(file);
                            Files.deleteIfExists(file);
                            deleted++;
                            freed += size;
                        }
                    } catch (IOException e) {
                        // One unreadable file must not abort the whole sweep.
                        log.debug("Could not sweep {}: {}", file.getFileName(), e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.debug("Could not list previews for project {}: {}", projectId, e.getMessage());
            }
        }

        if (deleted > 0) {
            log.info("Swept {} stale preview(s), freed {} MB", deleted, freed / (1024 * 1024));
        }
    }
}
