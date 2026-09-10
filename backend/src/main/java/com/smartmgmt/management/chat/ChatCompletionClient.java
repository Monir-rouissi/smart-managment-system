package com.smartmgmt.management.chat;

import java.util.List;

/**
 * Generates the assistant's next turn. Narrow on purpose, same reasoning as
 * {@link com.smartmgmt.management.document.ingest.EmbeddingClient}: one call in,
 * one answer out, so the RAG flow in {@link ChatService} does not know which
 * provider is behind it.
 */
public interface ChatCompletionClient {

    /**
     * @param systemInstruction grounding rules + the retrieved context for this turn
     * @param history           prior turns in the conversation, oldest first (may be empty)
     * @param userMessage       the caller's new message
     */
    String complete(String systemInstruction, List<ChatTurn> history, String userMessage);

    String modelId();
}
