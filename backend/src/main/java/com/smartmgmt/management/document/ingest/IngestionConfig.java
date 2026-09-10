package com.smartmgmt.management.document.ingest;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

@Configuration
public class IngestionConfig {

    /**
     * A bounded pool with CallerRuns: when the queue is full the upload request
     * thread does the work itself. That makes a burst slow rather than lossy,
     * which is the right trade for a queue that is not durable.
     */
    @Bean
    public Executor ingestionExecutor(IngestionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerThreads());
        executor.setMaxPoolSize(properties.getWorkerThreads());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("ingest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * One embedding client for the whole app, picked by which key is configured.
     * Gemini wins if both are set -- it is the preferred provider going forward.
     * Without either key the offline implementation is used, so the pipeline still
     * runs end to end (and tests never reach the network).
     */
    @Bean
    public EmbeddingClient embeddingClient(IngestionProperties properties, RestClient.Builder restClientBuilder) {
        if (properties.getGemini().getApiKey() != null && !properties.getGemini().getApiKey().isBlank()) {
            return new GeminiEmbeddingClient(restClientBuilder, properties);
        }
        if (properties.getOpenai().getApiKey() != null && !properties.getOpenai().getApiKey().isBlank()) {
            return new OpenAiEmbeddingClient(restClientBuilder, properties);
        }
        return new HashEmbeddingClient(properties.getDimensions());
    }
}
