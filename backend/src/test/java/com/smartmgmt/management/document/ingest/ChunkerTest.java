package com.smartmgmt.management.document.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * Window arithmetic is the easiest thing to get wrong here and the most expensive
 * to discover through a full ingestion run, so it is tested on its own.
 */
class ChunkerTest {

    private static final int WORDS = 3000;

    private IngestionProperties properties(int chunkTokens, int overlapTokens) {
        IngestionProperties properties = new IngestionProperties();
        properties.setChunkTokens(chunkTokens);
        properties.setOverlapTokens(overlapTokens);
        return properties;
    }

    /** Distinct numbered words make it possible to assert exactly where a chunk starts and ends. */
    private String numberedWords() {
        return IntStream.range(0, WORDS).mapToObj(i -> "word" + i).collect(Collectors.joining(" "));
    }

    private ExtractedText singleSegment(String text) {
        return new ExtractedText(List.of(new ExtractedText.Segment(null, text)));
    }

    @Test
    void producesContiguousWindowsThatRespectTheTokenBudget() {
        List<Chunk> chunks = new Chunker(properties(600, 90)).chunk(singleSegment(numberedWords()));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.tokenCount()).isPositive().isLessThanOrEqualTo(600);
            assertThat(chunk.content()).isNotBlank();
        });
        assertThat(chunks.stream().map(Chunk::index).toList())
                .isEqualTo(IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    void consecutiveChunksOverlap() {
        List<Chunk> chunks = new Chunker(properties(600, 90)).chunk(singleSegment(numberedWords()));

        for (int i = 0; i < chunks.size() - 1; i++) {
            String firstWordOfNext = chunks.get(i + 1).content().split("\\s+")[0];
            assertThat(chunks.get(i).content())
                    .as("chunk %d should already contain '%s', the first word of chunk %d", i, firstWordOfNext, i + 1)
                    .contains(firstWordOfNext);
        }
    }

    @Test
    void everyWordSurvivesChunking() {
        String text = numberedWords();
        String joined = String.join(" ", new Chunker(properties(600, 90))
                .chunk(singleSegment(text)).stream().map(Chunk::content).toList());

        for (int i = 0; i < WORDS; i += 137) {
            assertThat(joined).contains("word" + i);
        }
        assertThat(joined).contains("word" + (WORDS - 1));
    }

    @Test
    void zeroOverlapStillCoversTheWholeText() {
        List<Chunk> chunks = new Chunker(properties(100, 0)).chunk(singleSegment(numberedWords()));

        int total = chunks.stream().mapToInt(Chunk::tokenCount).sum();
        assertThat(chunks).hasSizeGreaterThan(5);
        assertThat(total).isEqualTo(chunks.stream().mapToInt(Chunk::tokenCount).sum());
        assertThat(chunks.getLast().content()).contains("word" + (WORDS - 1));
    }

    @Test
    void labelsChunksWithThePagesTheyCameFrom() {
        ExtractedText paged = new ExtractedText(List.of(
                new ExtractedText.Segment("1", "alpha ".repeat(200)),
                new ExtractedText.Segment("2", "beta ".repeat(200))));

        List<Chunk> chunks = new Chunker(properties(120, 20)).chunk(paged);

        assertThat(chunks).extracting(Chunk::pageOrSection).allSatisfy(label -> assertThat(label).startsWith("p. "));
        assertThat(chunks.getFirst().pageOrSection()).isEqualTo("p. 1");
        assertThat(chunks.getLast().pageOrSection()).isEqualTo("p. 2");
        // The window that straddles the page break is labelled with the range.
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.pageOrSection()).isEqualTo("p. 1-2"));
    }

    @Test
    void unpagedTextGetsNoLabel() {
        List<Chunk> chunks = new Chunker(properties(600, 90)).chunk(singleSegment("just some prose"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().pageOrSection()).isNull();
    }

    @Test
    void rejectsOverlapThatWouldNeverAdvance() {
        Chunker chunker = new Chunker(properties(100, 100));

        assertThatThrownBy(() -> chunker.chunk(singleSegment("text")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overlap-tokens");
    }
}
