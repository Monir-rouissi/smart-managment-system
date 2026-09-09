package com.smartmgmt.management.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Fusion is the one part of search whose correctness is not obvious by reading
 * it, and it needs no database — so it is tested as the pure function it is.
 */
class RrfTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    @Test
    void aChunkFoundByBothListsOutranksAChunkThatOnlyOneListRankedFirst() {
        // B is second in both lists; A tops the keyword list but is absent from the
        // semantic one. Agreement across both retrievers is what RRF rewards.
        List<Rrf.Fused> fused = Rrf.fuse(List.of(A, B), List.of(C, B), Rrf.DEFAULT_K);

        assertThat(fused).extracting(Rrf.Fused::chunkId).containsExactly(B, A, C);
        assertThat(fused.get(0).match()).isEqualTo(SearchMatch.BOTH);
    }

    @Test
    void scoreIsTheSumOfReciprocalRanks() {
        List<Rrf.Fused> fused = Rrf.fuse(List.of(A), List.of(A), 60);

        // rank 1 in each list: 1/(60+1) twice.
        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).score()).isEqualTo(2.0 / 61, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void matchTypeRecordsWhichListFoundIt() {
        List<Rrf.Fused> fused = Rrf.fuse(List.of(A), List.of(B), Rrf.DEFAULT_K);

        assertThat(fused).extracting(Rrf.Fused::match)
                .containsExactlyInAnyOrder(SearchMatch.KEYWORD, SearchMatch.SEMANTIC);
    }

    @Test
    void anEmptyListDegradesToTheOtherListsOrder() {
        // This is the hybrid-with-embeddings-down path: it must still rank sanely.
        List<Rrf.Fused> fused = Rrf.fuse(List.of(A, B, C), List.of(), Rrf.DEFAULT_K);

        assertThat(fused).extracting(Rrf.Fused::chunkId).containsExactly(A, B, C);
        assertThat(fused).allMatch(f -> f.match() == SearchMatch.KEYWORD);
    }

    @Test
    void bothListsEmptyYieldsNothing() {
        assertThat(Rrf.fuse(List.of(), List.of(), Rrf.DEFAULT_K)).isEmpty();
    }

    @Test
    void aRepeatedIdInsideOneListIsCountedOnce() {
        List<Rrf.Fused> duplicated = Rrf.fuse(List.of(A, A), List.of(), Rrf.DEFAULT_K);
        List<Rrf.Fused> single = Rrf.fuse(List.of(A), List.of(), Rrf.DEFAULT_K);

        assertThat(duplicated).hasSize(1);
        assertThat(duplicated.get(0).score()).isEqualTo(single.get(0).score());
    }

    @Test
    void kMustBePositive() {
        assertThatThrownBy(() -> Rrf.fuse(List.of(A), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
