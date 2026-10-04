package com.aistorystudio.videogen;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Background executor for the standalone Video Generation page's jobs.
 *
 * Deliberately separate from videoEditorExecutor (minutes of CPU-bound FFmpeg
 * work) and from the story pipeline's own @Async methods - same reasoning as
 * VideoEditorExecutorConfig: a stuck pool for one feature should not starve
 * another.
 *
 * Actual GPU serialisation already happens one layer down, inside
 * ComfyUIVideoProvider's own semaphore (studio.animation.local-ai.max-concurrent).
 * This pool only needs enough threads that a few requests can queue behind
 * that semaphore without blocking the HTTP request thread that submitted
 * them; it is not a second place that concurrency is enforced.
 */
@Configuration
public class VideoGenerationExecutorConfig {

    /** Scene sequences run for hours (N clips back to back). Own small pool so they never use up the
     *  threads of the single-clip page; the GPU is still serialised inside the providers. */
    @Bean("videoSequenceExecutor")
    public Executor videoSequenceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("video-sequence-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean("videoGenerationExecutor")
    public Executor videoGenerationExecutor(
            @Value("${studio.animation.local-ai.max-concurrent:1}") int maxConcurrent) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, maxConcurrent + 1));
        executor.setMaxPoolSize(Math.max(3, maxConcurrent + 2));
        executor.setQueueCapacity(24);
        executor.setThreadNamePrefix("video-generation-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }
}
