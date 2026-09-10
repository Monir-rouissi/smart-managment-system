package com.smartmgmt.management.chat.dto;

import java.util.UUID;

public record ChatResponse(UUID conversationId, ChatMessageResponse message) {
}
