package com.smartmgmt.management.chat.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.smartmgmt.management.chat.ChatMessage;
import com.smartmgmt.management.chat.ChatRole;

public record ChatMessageResponse(
        UUID id,
        ChatRole role,
        String content,
        List<SourceRef> sources,
        Instant createdAt) {

    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(message.getId(), message.getRole(), message.getContent(),
                message.getSources(), message.getCreatedAt());
    }
}
