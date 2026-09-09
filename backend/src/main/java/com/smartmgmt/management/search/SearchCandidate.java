package com.smartmgmt.management.search;

import java.util.UUID;

/**
 * One row from a retrieval path, before snippets and fusion. {@code preview} is
 * the leading characters of the chunk, used as the snippet when there are no
 * query terms to highlight.
 */
public record SearchCandidate(
        UUID chunkId,
        UUID documentId,
        String documentName,
        UUID projectId,
        String projectName,
        String pageOrSection,
        String preview,
        double score) {
}
