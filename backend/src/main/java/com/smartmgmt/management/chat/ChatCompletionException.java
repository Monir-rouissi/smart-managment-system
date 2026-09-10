package com.smartmgmt.management.chat;

/** The LLM could not be reached or returned something unusable. */
public class ChatCompletionException extends RuntimeException {

    public ChatCompletionException(String message) {
        super(message);
    }

    public ChatCompletionException(String message, Throwable cause) {
        super(message, cause);
    }
}
