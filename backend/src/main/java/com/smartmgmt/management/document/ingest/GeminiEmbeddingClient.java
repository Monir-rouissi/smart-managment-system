package com.smartmgmt.management.document.ingest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Google Gemini embeddings ({@code gemini-embedding-001}) over plain {@link RestClient}.
 *
 * <p>Uses {@code batchEmbedContents} so a whole chunk batch is one HTTP call, same
 * shape as {@link OpenAiEmbeddingClient}. {@code outputDimensionality} is pinned to
 * {@code app.ingest.dimensions} (1536) via Matryoshka truncation so the existing
 * {@code vector(1536)} column in V6 does not need a migration -- the model's native
 * output is 3072, but Google explicitly recommends 3072/1536/768 as the
 * quality-preserving truncation points.
 *
 * <p>Auth is a header, not a bearer token: {@code x-goog-api-key}.
 */
public class GeminiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingClient.class);

    private final RestClient client;
    private final IngestionProperties properties;

    public GeminiEmbeddingClient(RestClient.Builder builder, IngestionProperties properties) {
        this.properties = properties;
        this.client = builder
                .baseUrl(properties.getGemini().getBaseUrl())
                .defaultHeader("x-goog-api-key", properties.getGemini().getApiKey())
                .build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        int batchSize = Math.max(1, properties.getEmbeddingBatchSize());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(from + batchSize, texts.size()));
            vectors.addAll(embedBatch(batch));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> batch) {
        int maxAttempts = Math.max(1, properties.getGemini().getMaxAttempts());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String model = properties.getGemini().getModel();
                List<EmbedRequest> requests = batch.stream()
                        .map(text -> new EmbedRequest(
                                "models/" + model,
                                new Content(List.of(new Part(text))),
                                new EmbedConfig(properties.getDimensions())))
                        .toList();

                BatchEmbedResponse response = client.post()
                        .uri("/models/{model}:batchEmbedContents", model)
                        .body(new BatchEmbedRequest(requests))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (request, res) -> {
                            throw new RetryableEmbeddingException(
                                    "Gemini embeddings API returned " + res.getStatusCode(), res.getStatusCode());
                        })
                        .body(BatchEmbedResponse.class);

                if (response == null || response.embeddings() == null || response.embeddings().size() != batch.size()) {
                    throw new IngestionException("Gemini embeddings API returned %d vectors for %d inputs"
                            .formatted(response == null || response.embeddings() == null ? 0
                                    : response.embeddings().size(), batch.size()));
                }
                return response.embeddings().stream()
                        .map(Embedding::values)
                        .toList();
            } catch (RetryableEmbeddingException e) {
                if (!e.retryable() || attempt == maxAttempts) {
                    throw new IngestionException(e.getMessage(), e);
                }
                last = e;
                backoff(attempt);
            } catch (RestClientException e) {
                if (attempt == maxAttempts) {
                    throw new IngestionException("Gemini embeddings request failed: " + e.getMessage(), e);
                }
                last = e;
                backoff(attempt);
            }
        }
        throw new IngestionException("Gemini embeddings request failed after %d attempts".formatted(maxAttempts), last);
    }

    private void backoff(int attempt) {
        Duration wait = Duration.ofMillis((long) (500 * Math.pow(2, attempt - 1)));
        log.warn("Gemini embeddings attempt {} failed, retrying in {} ms", attempt, wait.toMillis());
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestionException("Interrupted while backing off from a Gemini embeddings retry", e);
        }
    }

    @Override
    public String modelId() {
        return properties.getGemini().getModel();
    }

    @Override
    public int dimensions() {
        return properties.getDimensions();
    }

    record BatchEmbedRequest(List<EmbedRequest> requests) {
    }

    record EmbedRequest(String model, Content content, EmbedConfig embedContentConfig) {
    }

    record Content(List<Part> parts) {
    }

    record Part(String text) {
    }

    record EmbedConfig(int outputDimensionality) {
    }

    record BatchEmbedResponse(List<Embedding> embeddings) {
    }

    record Embedding(float[] values) {
    }

    /** 429 and 5xx are worth another go; 4xx (bad key, bad model) never is. */
    static class RetryableEmbeddingException extends RuntimeException {

        private final HttpStatusCode status;

        RetryableEmbeddingException(String message, HttpStatusCode status) {
            super(message);
            this.status = status;
        }

        boolean retryable() {
            return status.is5xxServerError() || status.value() == 429;
        }
    }
}
