package com.smartmgmt.management.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /** Bounded history for prompt-building -- the last N turns, not the whole thread. */
    List<ChatMessage> findTop20ByConversationIdOrderByCreatedAtDesc(UUID conversationId);
}
