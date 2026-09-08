package com.smartmgmt.management.document.ingest;

import java.util.List;

/**
 * Text pulled out of a file, split into labelled segments (PDF pages) where the
 * format has them. A format with no natural divisions yields a single segment
 * with a null label.
 */
public record ExtractedText(List<Segment> segments) {

    public record Segment(String label, String text) {
    }

    public boolean isBlank() {
        return segments.stream().allMatch(s -> s.text().isBlank());
    }

    public int characterCount() {
        return segments.stream().mapToInt(s -> s.text().length()).sum();
    }
}
