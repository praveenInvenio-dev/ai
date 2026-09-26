package com.aistorystudio.videoeditor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Background executor for analysis, planning and rendering.
 *
 * Deliberately separate from any pool the story pipeline uses. Editor tasks are
 * minutes of CPU-bound FFmpeg work, and sharing a pool with the story pipeline
 * would let one long render starve story generation - two features degrading
 * together for no reason.
 *
 * The queue is bounded and the rejection policy is CallerRuns. An unbounded
 * queue would accept a hundred render requests and quietly sit on them for
 * hours; CallerRuns instead pushes back on the request thread, so the API slows
 * down visibly rather than silently accumulating work nobody is watching.
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "studio.video-editor.enabled", havingValue = "true", matchIfMissing = true)
public class VideoEditorExecutorConfig {

    @Bean("videoEditorExecutor")
    public Executor videoEditorExecutor(VideoEditorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Renders are serialised by a semaphore inside VideoRenderService, so
        // extra threads here only let analysis and planning overlap a render's
        // queue wait - not the render itself.
        executor.setCorePoolSize(Math.max(2, properties.getMaxConcurrentRenders() + 1));
        executor.setMaxPoolSize(Math.max(3, properties.getMaxConcurrentRenders() + 2));
        executor.setQueueCapacity(24);
        executor.setThreadNamePrefix("video-editor-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

        // Let an in-flight render finish on shutdown rather than leaving a
        // half-written MP4 and a job row stuck at RENDERING forever.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }
}
