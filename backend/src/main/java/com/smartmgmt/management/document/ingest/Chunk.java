package com.smartmgmt.management.document.ingest;

/** One slice of a document, ready to embed. */
public record Chunk(int index, String content, String pageOrSection, int tokenCount) {
}
