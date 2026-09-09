package com.smartmgmt.management.search.dto;

import java.util.UUID;

import com.smartmgmt.management.search.SearchMatch;

/**
 * One search result.
 *
 * <p>Deliberately never carries the embedding, and never the full chunk text --
 * only the snippet. Highlights are marked with {@code [[HL]]} / {@code [[/HL]]}
 * rather than HTML: the text comes from a file somebody uploaded, so the client
 * splits on those markers and renders interpolated text instead of trusting a
 * sanitiser with attacker-influenced markup.
 */
public record SearchHit(
        UUID chunkId,
        UUID documentId,
        String documentName,
        UUID projectId,
        String projectName,
        String pageOrSection,
        String snippet,
        double score,
        SearchMatch match) {
}
