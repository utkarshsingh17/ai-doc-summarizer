package com.utkarsh.ai_doc_qna.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    public static final String INGESTION_EXECUTOR = "ingestionExecutor";

    /**
     * Ingestion gets its own bounded pool rather than the shared task executor. Embedding is a
     * slow, rate-limited, network-bound job; letting it queue without limit would hide a backlog,
     * and letting it run unbounded would hammer the embeddings API.
     */
    @Bean(INGESTION_EXECUTOR)
    public Executor ingestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ingest-");
        // Overflow runs on the caller (the event-publishing thread) instead of being dropped.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
