package com.smartmgmt.management.document.ingest;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/** Tuning for the extract -> chunk -> embed pipeline, bound from {@code app.ingest.*}. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.ingest")
public class IngestionProperties {

    /** Turn the whole pipeline off (uploads then just sit at UPLOADED). */
    private boolean enabled = true;

    /** Target chunk size in tokens (cl100k_base). */
    private int chunkTokens = 600;

    /** Overlap between consecutive chunks, in tokens (~15% of chunkTokens). */
    private int overlapTokens = 90;

    /** Hard ceiling per document; a bigger file fails loudly instead of quietly costing money. */
    private int maxChunksPerDocument = 2000;

    /** How many chunks go in one embeddings request. */
    private int embeddingBatchSize = 64;

    /** Must match the vector(N) column in V6; checked at startup against the active client. */
    private int dimensions = 1536;

    /** Characters Tika is allowed to extract from one file. */
    private int maxExtractedCharacters = 10_000_000;

    private int workerThreads = 2;

    private int queueCapacity = 100;

    /** A PROCESSING row untouched for this long is treated as abandoned by a dead worker. */
    private Duration staleProcessingAfter = Duration.ofMinutes(10);

    private final OpenAi openai = new OpenAi();

    private final Gemini gemini = new Gemini();

    @Getter
    @Setter
    public static class OpenAi {

        /** When blank, the offline HashEmbeddingClient is used instead. */
        private String apiKey = "";

        private String model = "text-embedding-3-small";

        private String baseUrl = "https://api.openai.com/v1";

        private Duration timeout = Duration.ofSeconds(60);

        private int maxAttempts = 3;
    }

    /**
     * Google Gemini embeddings. Preferred over {@link OpenAi} when both keys are set --
     * see {@code IngestionConfig.embeddingClient}. {@code dimensions} (1536) is sent as
     * Gemini's {@code outputDimensionality}, truncating the model's native 3072-dim
     * output via MRL so the existing {@code vector(1536)} column needs no migration.
     */
    @Getter
    @Setter
    public static class Gemini {

        /** When blank (and openai.apiKey is also blank), the offline HashEmbeddingClient is used. */
        private String apiKey = "";

        private String model = "gemini-embedding-001";

        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

        private Duration timeout = Duration.ofSeconds(60);

        private int maxAttempts = 3;
    }
}
