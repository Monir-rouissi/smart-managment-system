package com.smartmgmt.management.document.ingest;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;

/**
 * Splits extracted text into overlapping token windows.
 *
 * <p>Everything is tokenised once into a single stream, with a parallel record of
 * where each segment (PDF page) starts, so a window may span a page break and
 * still be labelled with the page range it came from. Sizing uses real
 * cl100k_base tokens rather than a characters/4 guess, because the guess is off
 * by 30-40% on code, tables and non-English text -- exactly the documents where
 * an oversized chunk would be rejected by the embeddings API.
 */
@Component
public class Chunker {

    private final Encoding encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    private final IngestionProperties properties;

    public Chunker(IngestionProperties properties) {
        this.properties = properties;
    }

    public List<Chunk> chunk(ExtractedText text) {
        int windowSize = properties.getChunkTokens();
        int overlap = properties.getOverlapTokens();
        if (windowSize <= 0) {
            throw new IllegalStateException("app.ingest.chunk-tokens must be > 0");
        }
        if (overlap < 0 || overlap >= windowSize) {
            throw new IllegalStateException("app.ingest.overlap-tokens must be >= 0 and < chunk-tokens");
        }
        int step = windowSize - overlap;

        IntArrayList tokens = new IntArrayList();
        // segmentStart[i] = index in `tokens` where segment i begins.
        List<Integer> segmentStart = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (ExtractedText.Segment segment : text.segments()) {
            if (segment.text().isBlank()) {
                continue;
            }
            segmentStart.add(tokens.size());
            labels.add(segment.label());
            IntArrayList encoded = encoding.encode(segment.text());
            for (int i = 0; i < encoded.size(); i++) {
                tokens.add(encoded.get(i));
            }
        }

        List<Chunk> chunks = new ArrayList<>();
        int total = tokens.size();
        for (int start = 0, index = 0; start < total; start += step, index++) {
            int end = Math.min(start + windowSize, total);
            String content = decode(tokens, start, end).strip();
            if (!content.isEmpty()) {
                chunks.add(new Chunk(index, content, label(segmentStart, labels, start, end), end - start));
            }
            if (end == total) {
                break;
            }
        }
        return chunks;
    }

    private String decode(IntArrayList tokens, int start, int end) {
        IntArrayList window = new IntArrayList(end - start);
        for (int i = start; i < end; i++) {
            window.add(tokens.get(i));
        }
        return encoding.decode(window);
    }

    /**
     * "p. 3", or "p. 3-4" when the window crosses a page break; null for formats
     * with no page structure (txt, md, docx).
     */
    private String label(List<Integer> segmentStart, List<String> labels, int start, int end) {
        String first = labelAt(segmentStart, labels, start);
        String last = labelAt(segmentStart, labels, end - 1);
        if (first == null) {
            return null;
        }
        return first.equals(last) ? "p. " + first : "p. " + first + "-" + last;
    }

    private String labelAt(List<Integer> segmentStart, List<String> labels, int tokenIndex) {
        int segment = -1;
        for (int i = 0; i < segmentStart.size(); i++) {
            if (segmentStart.get(i) <= tokenIndex) {
                segment = i;
            } else {
                break;
            }
        }
        return segment < 0 ? null : labels.get(segment);
    }
}
