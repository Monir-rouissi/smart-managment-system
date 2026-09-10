package com.smartmgmt.management.chat;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/** Tuning for the RAG chat endpoint, bound from {@code app.chat.*}. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.chat")
public class ChatProperties {

    /** Top-k chunks pulled into the prompt as grounding context. */
    private int contextChunks = 6;

    /** Prior turns replayed to the model for continuity; the whole thread is not resent. */
    private int historyTurns = 20;

    private final Gemini gemini = new Gemini();

    @Getter
    @Setter
    public static class Gemini {

        /** Same GEMINI_API_KEY the embedding client reads -- one key, two uses. */
        private String apiKey = "";

        private String model = "gemini-3.8-flash";

        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

        private Duration timeout = Duration.ofSeconds(60);

        private int maxAttempts = 3;

        private double temperature = 0.2;

        private int maxOutputTokens = 1024;
    }
}
