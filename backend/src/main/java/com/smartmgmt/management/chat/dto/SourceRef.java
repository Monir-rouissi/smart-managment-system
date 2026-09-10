package com.smartmgmt.management.chat.dto;

import java.util.UUID;

import com.smartmgmt.management.search.SearchCandidate;

/**
 * One retrieved chunk cited in an assistant answer. Stored as-is in
 * {@code chat_message.sources} (JSONB) and returned as-is to the client --
 * the same shape both places, so there is nothing to keep in sync.
 */
public record SourceRef(
        UUID chunkId,
        UUID documentId,
        String documentName,
        String pageOrSection,
        double score) {

    public static SourceRef from(SearchCandidate candidate) {
        return new SourceRef(candidate.chunkId(), candidate.documentId(), candidate.documentName(),
                candidate.pageOrSection(), candidate.score());
    }
}
