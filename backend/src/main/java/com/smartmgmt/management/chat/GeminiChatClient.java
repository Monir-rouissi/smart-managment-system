package com.smartmgmt.management.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gemini {@code generateContent} over plain {@link RestClient} -- same shape as
 * {@link com.smartmgmt.management.document.ingest.GeminiEmbeddingClient}: one
 * client, batched-by-nature-of-one-call, bounded retries on 429/5xx only.
 *
 * <p>History turns map {@code USER -> "user"}, {@code ASSISTANT -> "model"};
 * Gemini has no third role for grounding context, so that goes in
 * {@code systemInstruction} instead, built fresh per call in {@link ChatService}.
 */
public class GeminiChatClient implements ChatCompletionClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatClient.class);

    private final RestClient client;
    private final ChatProperties properties;

    public GeminiChatClient(RestClient.Builder builder, ChatProperties properties) {
        this.properties = properties;
        this.client = builder
                .baseUrl(properties.getGemini().getBaseUrl())
                .defaultHeader("x-goog-api-key", properties.getGemini().getApiKey())
                .build();
    }

    @Override
    public String complete(String systemInstruction, List<ChatTurn> history, String userMessage) {
        if (properties.getGemini().getApiKey() == null || properties.getGemini().getApiKey().isBlank()) {
            throw new ChatCompletionException(
                    "No GEMINI_API_KEY configured (app.chat.gemini.api-key) -- the chat endpoint has no LLM to call.");
        }
        List<Content> contents = new ArrayList<>(history.size() + 1);
        for (ChatTurn turn : history) {
            contents.add(new Content(turn.role() == ChatRole.USER ? "user" : "model",
                    List.of(new Part(turn.content()))));
        }
        contents.add(new Content("user", List.of(new Part(userMessage))));

        GenerateRequest request = new GenerateRequest(
                contents,
                new SystemInstruction(List.of(new Part(systemInstruction))),
                new GenerationConfig(properties.getGemini().getTemperature(), properties.getGemini().getMaxOutputTokens()));

        int maxAttempts = Math.max(1, properties.getGemini().getMaxAttempts());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                GenerateResponse response = client.post()
                        .uri("/models/{model}:generateContent", properties.getGemini().getModel())
                        .body(request)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            throw new RetryableChatException(
                                    "Gemini generateContent returned " + res.getStatusCode(), res.getStatusCode());
                        })
                        .body(GenerateResponse.class);
                return extractText(response);
            } catch (RetryableChatException e) {
                if (!e.retryable() || attempt == maxAttempts) {
                    throw new ChatCompletionException(e.getMessage(), e);
                }
                last = e;
                backoff(attempt);
            } catch (RestClientException e) {
                if (attempt == maxAttempts) {
                    throw new ChatCompletionException("Gemini generateContent request failed: " + e.getMessage(), e);
                }
                last = e;
                backoff(attempt);
            }
        }
        throw new ChatCompletionException("Gemini generateContent failed after %d attempts".formatted(maxAttempts), last);
    }

    private String extractText(GenerateResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            // A prompt blocked by safety filtering lands here with no candidates at
            // all -- not a network failure, so it is not retried, but the caller
            // still needs a clear reason rather than a null answer.
            throw new ChatCompletionException("Gemini returned no candidates"
                    + (response != null && response.promptFeedback() != null
                            ? " (blockReason=" + response.promptFeedback().blockReason() + ")" : ""));
        }
        Content content = response.candidates().get(0).content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            throw new ChatCompletionException("Gemini candidate had no text content");
        }
        StringBuilder text = new StringBuilder();
        for (Part part : content.parts()) {
            if (part.text() != null) {
                text.append(part.text());
            }
        }
        return text.toString();
    }

    private void backoff(int attempt) {
        Duration wait = Duration.ofMillis((long) (500 * Math.pow(2, attempt - 1)));
        log.warn("Gemini chat attempt {} failed, retrying in {} ms", attempt, wait.toMillis());
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatCompletionException("Interrupted while backing off from a Gemini chat retry", e);
        }
    }

    @Override
    public String modelId() {
        return properties.getGemini().getModel();
    }

    record GenerateRequest(List<Content> contents, SystemInstruction systemInstruction, GenerationConfig generationConfig) {
    }

    record GenerationConfig(double temperature, int maxOutputTokens) {
    }

    record Content(String role, List<Part> parts) {
    }

    /** No {@code role} field -- the API doc shows systemInstruction as {parts: [...]} only. */
    record SystemInstruction(List<Part> parts) {
    }

    record Part(String text) {
    }

    record GenerateResponse(List<Candidate> candidates, PromptFeedback promptFeedback) {
    }

    record Candidate(Content content, String finishReason) {
    }

    record PromptFeedback(String blockReason) {
    }

    /** 429 and 5xx are worth another go; 4xx (bad key, bad model, bad request) never is. */
    static class RetryableChatException extends RuntimeException {

        private final HttpStatusCode status;

        RetryableChatException(String message, HttpStatusCode status) {
            super(message);
            this.status = status;
        }

        boolean retryable() {
            return status.is5xxServerError() || status.value() == 429;
        }
    }
}
