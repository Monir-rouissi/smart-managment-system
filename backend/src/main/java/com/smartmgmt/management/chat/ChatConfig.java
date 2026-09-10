package com.smartmgmt.management.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ChatConfig {

    /**
     * Gemini is the only provider wired up so far -- unlike embeddings there is no
     * offline fallback, because there is no meaningful offline chat completion.
     * Without a key the bean still exists (nothing else in the context needs it
     * absent) but every call fails fast with a clear message instead of the app
     * refusing to start; that keeps the rest of the API usable with no LLM key set.
     */
    @Bean
    public ChatCompletionClient chatCompletionClient(ChatProperties properties, RestClient.Builder restClientBuilder) {
        return new GeminiChatClient(restClientBuilder, properties);
    }
}
