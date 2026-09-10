package com.smartmgmt.management.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartmgmt.management.chat.dto.ChatMessageResponse;
import com.smartmgmt.management.chat.dto.ChatRequest;
import com.smartmgmt.management.chat.dto.ChatResponse;
import com.smartmgmt.management.chat.dto.ConversationResponse;

import jakarta.validation.Valid;

/**
 * {@code isAuthenticated()} is the whole authorisation annotation here, same as
 * {@code SearchController}: which chunks ground an answer is a row-level rule
 * baked into the retrieval query, and which conversations a caller can open is
 * ownership, not role -- see {@link ChatService}.
 */
@RestController
public class ChatController {

    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/api/chat")
    public ChatResponse send(@Valid @RequestBody ChatRequest request) {
        return service.send(request);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/api/chat/conversations")
    public List<ConversationResponse> listConversations(@RequestParam(required = false) UUID projectId) {
        return service.listConversations(projectId);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/api/chat/conversations/{id}")
    public List<ChatMessageResponse> history(@PathVariable UUID id) {
        return service.history(id);
    }
}
