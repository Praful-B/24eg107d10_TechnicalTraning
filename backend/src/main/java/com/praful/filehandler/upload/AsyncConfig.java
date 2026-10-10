package com.praful.filehandler.upload;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Chunking shells out to FFmpeg, so it runs off the request thread. The pool is small
 * on purpose: a low-cost VPS should not try to normalize ten recordings at once.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "chunkExecutor")
    public TaskExecutor chunkExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("chunk-");
        executor.initialize();
        return executor;
    }

    /**
     * Single-threaded pool for the synchronous transcription mode. Whisper is
     * memory hungry, so only one recording is decoded at a time.
     */
    @Bean(name = "transcriptionExecutor")
    public TaskExecutor transcriptionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("transcribe-");
        executor.initialize();
        return executor;
    }
}
