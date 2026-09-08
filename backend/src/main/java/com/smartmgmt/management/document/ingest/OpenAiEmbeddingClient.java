package com.smartmgmt.management.document.ingest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OpenAI embeddings over plain {@link RestClient}.
 *
 * <p>Requests are batched, and a 429/5xx is retried with exponential backoff --
 * but only a bounded number of times: an outage must surface as a FAILED
 * document, not as a loop that burns quota.
 */
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);

    private final RestClient client;
    private final IngestionProperties properties;

    public OpenAiEmbeddingClient(RestClient.Builder builder, IngestionProperties properties) {
        this.properties = properties;
        this.client = builder
                .baseUrl(properties.getOpenai().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getOpenai().getApiKey())
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
        int maxAttempts = Math.max(1, properties.getOpenai().getMaxAttempts());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                EmbeddingsResponse response = client.post()
                        .uri("/embeddings")
                        .body(new EmbeddingsRequest(properties.getOpenai().getModel(), batch))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (request, res) -> {
                            throw new RetryableEmbeddingException(
                                    "Embeddings API returned " + res.getStatusCode(), res.getStatusCode());
                        })
                        .body(EmbeddingsResponse.class);
                if (response == null || response.data() == null || response.data().size() != batch.size()) {
                    throw new IngestionException("Embeddings API returned %d vectors for %d inputs"
                            .formatted(response == null || response.data() == null ? 0 : response.data().size(),
                                    batch.size()));
                }
                return response.data().stream()
                        .sorted((a, b) -> Integer.compare(a.index(), b.index()))
                        .map(EmbeddingsResponse.Item::embedding)
                        .toList();
            } catch (RetryableEmbeddingException e) {
                if (!e.retryable() || attempt == maxAttempts) {
                    throw new IngestionException(e.getMessage(), e);
                }
                last = e;
                backoff(attempt);
            } catch (RestClientException e) {
                if (attempt == maxAttempts) {
                    throw new IngestionException("Embeddings request failed: " + e.getMessage(), e);
                }
                last = e;
                backoff(attempt);
            }
        }
        throw new IngestionException("Embeddings request failed after %d attempts".formatted(maxAttempts), last);
    }

    private void backoff(int attempt) {
        Duration wait = Duration.ofMillis((long) (500 * Math.pow(2, attempt - 1)));
        log.warn("Embeddings attempt {} failed, retrying in {} ms", attempt, wait.toMillis());
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestionException("Interrupted while backing off from an embeddings retry", e);
        }
    }

    @Override
    public String modelId() {
        return properties.getOpenai().getModel();
    }

    @Override
    public int dimensions() {
        return properties.getDimensions();
    }

    record EmbeddingsRequest(String model, List<String> input) {
    }

    record EmbeddingsResponse(List<Item> data) {
        record Item(int index, float[] embedding) {
        }
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
