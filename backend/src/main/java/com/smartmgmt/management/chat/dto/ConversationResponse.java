package com.smartmgmt.management.chat.dto;

import java.time.Instant;
import java.util.UUID;

import com.smartmgmt.management.chat.ChatConversation;

public record ConversationResponse(UUID id, UUID projectId, String title, Instant updatedAt) {

    public static ConversationResponse from(ChatConversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getProject() == null ? null : conversation.getProject().getId(),
                conversation.getTitle(),
                conversation.getUpdatedAt());
    }
}
