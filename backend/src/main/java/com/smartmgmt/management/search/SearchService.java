package com.smartmgmt.management.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.smartmgmt.management.document.ingest.EmbeddingClient;
import com.smartmgmt.management.search.dto.SearchHit;

/**
 * Keyword, semantic and hybrid retrieval over document chunks.
 *
 * <p>Paging differs per mode, on purpose:
 * <ul>
 *   <li><b>keyword</b> pages in SQL with LIMIT/OFFSET and reports a real total --
 *       full-text search is exact.</li>
 *   <li><b>semantic</b> fetches the top K once and slices in memory. Offsetting
 *       into an approximate index is meaningless: page 2 is not "the next best",
 *       it is whatever the graph walk happened to visit.</li>
 *   <li><b>hybrid</b> fuses two bounded candidate lists, so its total is the size
 *       of the fused set, not a corpus-wide count.</li>
 * </ul>
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private static final int MIN_QUERY_LENGTH = 2;
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_PAGE_SIZE = 50;
    /** Deepest reachable result. Beyond this, ranked retrieval stops being meaningful. */
    private static final int MAX_DEPTH = 200;
    /** Candidates pulled from each path before fusion. */
    private static final int HYBRID_CANDIDATES = 50;
    private static final int SNIPPET_CHARS = 240;

    private final SearchRepository repository;
    private final EmbeddingClient embeddings;
    private final QueryEmbeddingCache queryCache;

    public SearchService(SearchRepository repository, EmbeddingClient embeddings, QueryEmbeddingCache queryCache) {
        this.repository = repository;
        this.embeddings = embeddings;
        this.queryCache = queryCache;
    }

    @Transactional(readOnly = true)
    public Page<SearchHit> search(String rawQuery, SearchMode mode, UUID projectId, Pageable pageable) {
        String query = normalise(rawQuery);
        AccessScope scope = AccessScope.current();

        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        int page = pageable.getPageNumber();
        int offset = page * size;
        if (offset + size > MAX_DEPTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Search results are limited to the first %d hits. Narrow the query instead."
                            .formatted(MAX_DEPTH));
        }

        return switch (mode) {
            case KEYWORD -> keyword(query, scope, projectId, page, size, offset);
            case SEMANTIC -> semantic(query, scope, projectId, page, size, offset);
            case HYBRID -> hybrid(query, scope, projectId, page, size, offset);
        };
    }

    // --- modes -----------------------------------------------------------------

    private Page<SearchHit> keyword(String query, AccessScope scope, UUID projectId,
            int page, int size, int offset) {
        List<SearchCandidate> rows = repository.keyword(query, scope, projectId, size, offset);
        long total = repository.countKeyword(query, scope, projectId);
        Map<UUID, String> highlights = repository.headlines(ids(rows), query);

        List<SearchHit> hits = rows.stream()
                .map(c -> toHit(c, highlights.get(c.chunkId()), SearchMatch.KEYWORD))
                .toList();
        return new PageImpl<>(hits, PageRequest.of(page, size), total);
    }

    private Page<SearchHit> semantic(String query, AccessScope scope, UUID projectId,
            int page, int size, int offset) {
        List<SearchCandidate> rows = semanticCandidates(query, scope, projectId, Math.min(MAX_DEPTH, offset + size))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Semantic search is unavailable: the query could not be embedded."));

        List<SearchCandidate> slice = slice(rows, offset, size);
        List<SearchHit> hits = slice.stream()
                // A semantic hit contains no query terms to mark, so there is nothing to
                // highlight -- a truncated preview is the honest snippet here.
                .map(c -> toHit(c, null, SearchMatch.SEMANTIC))
                .toList();
        // The total is the size of the candidate set, not a corpus count: an ANN
        // search does not know how many rows "match".
        return new PageImpl<>(hits, PageRequest.of(page, size), rows.size());
    }

    private Page<SearchHit> hybrid(String query, AccessScope scope, UUID projectId,
            int page, int size, int offset) {
        List<SearchCandidate> keywordRows = repository.keyword(query, scope, projectId, HYBRID_CANDIDATES, 0);
        List<SearchCandidate> semanticRows = semanticCandidates(query, scope, projectId, HYBRID_CANDIDATES)
                // Degrade rather than fail: if embeddings are down, hybrid is still a
                // working keyword search, which is better than a 503 on the search box.
                .orElseGet(() -> {
                    log.warn("Semantic path unavailable; serving hybrid search from the keyword path only");
                    return List.of();
                });

        Map<UUID, SearchCandidate> byId = new LinkedHashMap<>();
        semanticRows.forEach(c -> byId.put(c.chunkId(), c));
        keywordRows.forEach(c -> byId.put(c.chunkId(), c));

        List<Rrf.Fused> fused = Rrf.fuse(ids(keywordRows), ids(semanticRows), Rrf.DEFAULT_K);
        List<Rrf.Fused> slice = slice(fused, offset, size);

        List<UUID> needHighlight = slice.stream()
                .filter(f -> f.match() != SearchMatch.SEMANTIC)
                .map(Rrf.Fused::chunkId)
                .toList();
        Map<UUID, String> highlights = repository.headlines(needHighlight, query);

        List<SearchHit> hits = new ArrayList<>(slice.size());
        for (Rrf.Fused f : slice) {
            SearchCandidate candidate = byId.get(f.chunkId());
            if (candidate == null) {
                continue;
            }
            hits.add(new SearchHit(
                    candidate.chunkId(), candidate.documentId(), candidate.documentName(),
                    candidate.projectId(), candidate.projectName(), candidate.pageOrSection(),
                    snippet(candidate, highlights.get(f.chunkId())),
                    f.score(), f.match()));
        }
        return new PageImpl<>(hits, PageRequest.of(page, size), fused.size());
    }

    // --- helpers ---------------------------------------------------------------

    /**
     * Returns empty when the query could not be embedded, so each caller can decide
     * whether that is fatal (semantic) or a degraded mode (hybrid).
     */
    private java.util.Optional<List<SearchCandidate>> semanticCandidates(String query, AccessScope scope,
            UUID projectId, int limit) {
        try {
            String model = embeddings.modelId();
            float[] vector = queryCache.get(model, query, () -> embeddings.embed(List.of(query)).get(0));
            return java.util.Optional.of(repository.semantic(vector, model, scope, projectId, limit));
        } catch (RuntimeException e) {
            log.warn("Could not embed search query", e);
            return java.util.Optional.empty();
        }
    }

    private String normalise(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < MIN_QUERY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Search query must be at least %d characters".formatted(MIN_QUERY_LENGTH));
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Search query must be at most %d characters".formatted(MAX_QUERY_LENGTH));
        }
        return query;
    }

    private SearchHit toHit(SearchCandidate c, String highlighted, SearchMatch match) {
        return new SearchHit(c.chunkId(), c.documentId(), c.documentName(), c.projectId(), c.projectName(),
                c.pageOrSection(), snippet(c, highlighted), c.score(), match);
    }

    private String snippet(SearchCandidate candidate, String highlighted) {
        if (highlighted != null && !highlighted.isBlank()) {
            return highlighted;
        }
        return truncateOnWord(candidate.preview());
    }

    /** Cuts at a word boundary so a snippet never ends mid-token. */
    static String truncateOnWord(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        if (trimmed.length() <= SNIPPET_CHARS) {
            return trimmed;
        }
        String cut = trimmed.substring(0, SNIPPET_CHARS);
        int lastSpace = cut.lastIndexOf(' ');
        return (lastSpace > SNIPPET_CHARS / 2 ? cut.substring(0, lastSpace) : cut).stripTrailing() + "…";
    }

    private static List<UUID> ids(List<SearchCandidate> candidates) {
        return candidates.stream().map(SearchCandidate::chunkId).toList();
    }

    private static <T> List<T> slice(List<T> all, int offset, int size) {
        if (offset >= all.size()) {
            return List.of();
        }
        return all.subList(offset, Math.min(offset + size, all.size()));
    }
}
