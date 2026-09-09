package com.smartmgmt.management.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reciprocal rank fusion.
 *
 * <pre>
 *   score(d) = SUM over lists  1 / (k + rank_in_that_list)      rank starts at 1
 * </pre>
 *
 * <p>Chosen over weighted score blending because the two inputs are not on
 * comparable scales: {@code ts_rank_cd} is unbounded and shifts with the query,
 * while cosine similarity sits in [0, 1]. Blending them needs per-query min-max
 * normalisation, where a single outlier reshapes the whole ranking. RRF uses only
 * the positions, so the scales never have to be reconciled.
 *
 * <p>{@code k = 60} is the value from the original paper; it damps the dominance
 * of the first result so a strong match in one list cannot bury a document that
 * both lists agree on.
 *
 * <p>Pure and static on purpose -- this is the one part of search whose
 * correctness is not obvious by inspection, so it is unit tested without a
 * database.
 */
public final class Rrf {

    public static final int DEFAULT_K = 60;

    private Rrf() {
    }

    public record Fused(UUID chunkId, double score, SearchMatch match) {
    }

    public static List<Fused> fuse(List<UUID> keywordRanked, List<UUID> semanticRanked, int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("RRF k must be > 0");
        }
        Map<UUID, double[]> scores = new LinkedHashMap<>();
        Map<UUID, boolean[]> sources = new LinkedHashMap<>();

        accumulate(keywordRanked, k, scores, sources, 0);
        accumulate(semanticRanked, k, scores, sources, 1);

        List<Fused> fused = new ArrayList<>(scores.size());
        scores.forEach((id, score) -> {
            boolean[] from = sources.get(id);
            SearchMatch match = from[0] && from[1] ? SearchMatch.BOTH
                    : from[0] ? SearchMatch.KEYWORD : SearchMatch.SEMANTIC;
            fused.add(new Fused(id, score[0], match));
        });

        // Ties broken by id so paging is deterministic across requests.
        fused.sort(Comparator.comparingDouble(Fused::score).reversed()
                .thenComparing(f -> f.chunkId().toString()));
        return fused;
    }

    private static void accumulate(List<UUID> ranked, int k, Map<UUID, double[]> scores,
            Map<UUID, boolean[]> sources, int listIndex) {
        for (int i = 0; i < ranked.size(); i++) {
            UUID id = ranked.get(i);
            // A duplicate id inside one list would otherwise be counted twice.
            if (sources.computeIfAbsent(id, x -> new boolean[2])[listIndex]) {
                continue;
            }
            sources.get(id)[listIndex] = true;
            scores.computeIfAbsent(id, x -> new double[1])[0] += 1.0 / (k + i + 1);
        }
    }
}
