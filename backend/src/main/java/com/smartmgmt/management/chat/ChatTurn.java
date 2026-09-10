package com.smartmgmt.management.chat;

/** One prior turn fed back to the model as conversation history. */
public record ChatTurn(ChatRole role, String content) {
}
