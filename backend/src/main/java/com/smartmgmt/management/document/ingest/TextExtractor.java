package com.smartmgmt.management.document.ingest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.springframework.stereotype.Component;

/**
 * Turns an uploaded file into plain text.
 *
 * <p>Plain text and markdown are read directly -- running them through Tika would
 * only add a detection round-trip. PDF and DOCX go through Tika's
 * AutoDetectParser; the PDF path collects one segment per page so chunks can
 * carry a page number for later citations.
 */
@Component
public class TextExtractor {

    private final IngestionProperties properties;

    public TextExtractor(IngestionProperties properties) {
        this.properties = properties;
    }

    public ExtractedText extract(byte[] bytes, String mimeType, String filename) {
        if (bytes.length == 0) {
            throw new IngestionException("File is empty");
        }
        if ("text/plain".equals(mimeType) || "text/markdown".equals(mimeType)) {
            return new ExtractedText(List.of(new ExtractedText.Segment(null, clean(readText(bytes)))));
        }
        return parseWithTika(bytes, mimeType, filename);
    }

    private ExtractedText parseWithTika(byte[] bytes, String mimeType, String filename) {
        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, mimeType);
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);

        PageCollectingHandler handler = new PageCollectingHandler(properties.getMaxExtractedCharacters());
        Parser parser = new AutoDetectParser();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            parser.parse(in, handler, metadata, new ParseContext());
        } catch (Exception e) {
            throw new IngestionException("Could not read the file (%s): %s"
                    .formatted(mimeType, e.getMessage()), e);
        }

        List<ExtractedText.Segment> segments = handler.pages().stream()
                .map(page -> new ExtractedText.Segment(page.label(), clean(page.text())))
                .filter(segment -> !segment.text().isBlank())
                .toList();

        if (segments.isEmpty()) {
            throw new IngestionException(
                    "No extractable text found. A scanned PDF needs OCR, which this phase does not do.");
        }
        return new ExtractedText(segments);
    }

    private String readText(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        int max = properties.getMaxExtractedCharacters();
        return text.length() > max ? text.substring(0, max) : text;
    }

    /**
     * Collapses runs of whitespace and drops characters Postgres will not accept
     * in a text column (NUL) or that only add tokens (soft hyphens, zero-width
     * joiners left behind by PDF extraction).
     */
    static String clean(String raw) {
        return raw
                .replace('\u00a0', ' ')
                .replaceAll("[\\u0000\\u00ad\\u200b-\\u200f\\ufeff]", "")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("(?: *\\n *){3,}", "\n\n")
                .strip();
    }
}
