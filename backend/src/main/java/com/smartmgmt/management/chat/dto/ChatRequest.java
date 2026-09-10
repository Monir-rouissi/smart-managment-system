package com.smartmgmt.management.chat.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code conversationId} and {@code message} are the plan's contract (item 35).
 * {@code projectId} is an addition the plan implies but does not spell out: item
 * 38 asks for a project-level panel, and retrieval has to be scoped to *something*
 * on the first message of a new conversation. Once a conversation exists its own
 * {@code project} wins and this field is ignored -- see {@link com.smartmgmt.management.chat.ChatService}.
 */
public record ChatRequest(
        UUID conversationId,
        @NotBlank @Size(max = 4000) String message,
        UUID projectId) {
}
