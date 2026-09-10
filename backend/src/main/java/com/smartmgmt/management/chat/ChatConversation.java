package com.smartmgmt.management.chat;

import com.smartmgmt.common.BaseEntity;
import com.smartmgmt.management.project.Project;
import com.smartmgmt.management.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A chat thread, owned by the user who started it. {@code project} scopes
 * retrieval to that project's documents (the "project-level" panel); null means
 * "everything I can see" (the global panel) -- see {@link ChatService}.
 */
@Getter
@Setter
@Entity
@Table(name = "chat_conversation")
public class ChatConversation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    /** Derived from the first message; a label for a conversation list, nothing more. */
    @Column(length = 200)
    private String title;
}
