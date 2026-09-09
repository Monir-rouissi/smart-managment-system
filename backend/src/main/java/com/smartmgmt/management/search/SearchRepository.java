package com.smartmgmt.management.search;

import java.sql.Types;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The two retrieval queries plus snippet generation, as native SQL.
 *
 * <p>Native rather than JPQL because none of this is expressible in JPQL:
 * {@code tsvector} matching, {@code ts_rank_cd}, {@code ts_headline} and the
 * pgvector {@code <=>} operator.
 *
 * <p>Every query carries the visibility predicate inline. There is no variant
 * without it.
 */
@Repository
public class SearchRepository {

    /**
     * Highlight markers. Not HTML: chunk text comes from an uploaded file, so the
     * client splits on these and renders interpolated text rather than trusting a
     * sanitiser with content an attacker influenced.
     */
    private static final String HEADLINE_OPTIONS =
            "StartSel=\"[[HL]]\", StopSel=\"[[/HL]]\", MaxFragments=2, MinWords=8, MaxWords=28";

    /** How much of the chunk is carried back for the no-highlight snippet. */
    private static final int PREVIEW_CHARS = 400;

    /**
     * A USER sees only documents on a project they own. `p.owner_id = :userId` is
     * NULL -- hence false -- for a project-less "global" document, so those drop
     * out for a USER by construction rather than by a second rule to remember.
     */
    private static final String VISIBILITY = """
             AND d.status = 'READY'
             AND (CAST(:privileged AS boolean) OR p.owner_id = CAST(:userId AS uuid))
             AND (CAST(:projectId AS uuid) IS NULL OR d.project_id = CAST(:projectId AS uuid))
            """;

    private static final String KEYWORD_FROM = """
            FROM document_chunks c
            JOIN documents d ON d.id = c.document_id
            LEFT JOIN projects p ON p.id = d.project_id
            CROSS JOIN q
            WHERE (c.content_tsv @@ q.tsq OR (d.name_tsv @@ q.tsq AND c.chunk_index = 0))
            """;

    private static final String SEMANTIC_FROM = """
            FROM document_chunks c
            JOIN documents d ON d.id = c.document_id
            LEFT JOIN projects p ON p.id = d.project_id
            WHERE c.embedding IS NOT NULL
              AND d.embedding_model = :model
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SearchCandidate> CANDIDATE = (rs, i) -> new SearchCandidate(
            rs.getObject("chunk_id", UUID.class),
            rs.getObject("document_id", UUID.class),
            rs.getString("document_name"),
            rs.getObject("project_id", UUID.class),
            rs.getString("project_name"),
            rs.getString("page_or_section"),
            rs.getString("preview"),
            rs.getDouble("score"));

    /**
     * Full-text candidates, ranked.
     *
     * <p>A document whose *name* matches contributes only its first chunk: without
     * that guard, one well-named 200-chunk document would fill every page.
     *
     * <p>{@code websearch_to_tsquery} rather than {@code to_tsquery}: the latter
     * throws on a stray {@code &} or an unbalanced quote, turning a user's typo
     * into a 500.
     */
    public List<SearchCandidate> keyword(String query, AccessScope scope, UUID projectId, int limit, int offset) {
        String sql = """
                WITH q AS (SELECT websearch_to_tsquery('english', :q) AS tsq)
                SELECT c.id               AS chunk_id,
                       d.id               AS document_id,
                       d.name             AS document_name,
                       d.project_id       AS project_id,
                       p.name             AS project_name,
                       c.page_or_section  AS page_or_section,
                       left(c.content, :previewChars) AS preview,
                       (ts_rank_cd(c.content_tsv, q.tsq)
                          + CASE WHEN d.name_tsv @@ q.tsq THEN 0.1 ELSE 0 END) AS score
                """ + KEYWORD_FROM + VISIBILITY + """
                ORDER BY score DESC, c.id
                LIMIT :limit OFFSET :offset
                """;
        return jdbc.query(sql, params(scope, projectId)
                .addValue("q", query)
                .addValue("previewChars", PREVIEW_CHARS)
                .addValue("limit", limit)
                .addValue("offset", offset), CANDIDATE);
    }

    /** Exact total for the keyword path: FTS is not approximate, so this number is real. */
    public long countKeyword(String query, AccessScope scope, UUID projectId) {
        String sql = """
                WITH q AS (SELECT websearch_to_tsquery('english', :q) AS tsq)
                SELECT count(*)
                """ + KEYWORD_FROM + VISIBILITY;
        Long total = jdbc.queryForObject(sql, params(scope, projectId).addValue("q", query), Long.class);
        return total == null ? 0 : total;
    }

    /**
     * Nearest neighbours of the query vector.
     *
     * <p>Ordering uses the raw distance operator so the HNSW index can serve it;
     * the {@code 1 - distance} similarity is for display only.
     *
     * <p>{@code d.embedding_model = :model} matters: chunks embedded by different
     * models share this column and this index, and comparing across them produces
     * confident nonsense. With the filter, switching models degrades to "older
     * documents stop appearing until reprocessed", which is visible.
     */
    public List<SearchCandidate> semantic(float[] queryVector, String model, AccessScope scope,
            UUID projectId, int limit) {
        String sql = """
                SELECT c.id               AS chunk_id,
                       d.id               AS document_id,
                       d.name             AS document_name,
                       d.project_id       AS project_id,
                       p.name             AS project_name,
                       c.page_or_section  AS page_or_section,
                       left(c.content, :previewChars) AS preview,
                       1 - (c.embedding <=> CAST(:vec AS vector)) AS score
                """ + SEMANTIC_FROM + VISIBILITY + """
                ORDER BY c.embedding <=> CAST(:vec AS vector)
                LIMIT :limit
                """;
        return jdbc.query(sql, params(scope, projectId)
                .addValue("model", model)
                .addValue("vec", toVectorLiteral(queryVector))
                .addValue("previewChars", PREVIEW_CHARS)
                .addValue("limit", limit), CANDIDATE);
    }

    /**
     * Highlighted snippets for exactly the chunks being returned.
     *
     * <p>{@code ts_headline} re-parses the original text and is expensive. Running
     * it inside the ranking query would make latency scale with the number of
     * matches instead of the page size -- the standard Postgres FTS mistake.
     */
    public Map<UUID, String> headlines(Collection<UUID> chunkIds, String query) {
        if (chunkIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
                SELECT c.id AS chunk_id,
                       ts_headline('english', c.content,
                                   websearch_to_tsquery('english', :q),
                                   :options) AS snippet
                FROM document_chunks c
                WHERE c.id IN (:ids)
                """;
        List<Map.Entry<UUID, String>> rows = jdbc.query(sql, new MapSqlParameterSource()
                .addValue("q", query)
                .addValue("options", HEADLINE_OPTIONS)
                .addValue("ids", chunkIds),
                (rs, i) -> Map.entry(rs.getObject("chunk_id", UUID.class), rs.getString("snippet")));

        Map<UUID, String> snippets = new HashMap<>(rows.size());
        rows.forEach(row -> snippets.put(row.getKey(), row.getValue()));
        return snippets;
    }

    /**
     * {@code projectId} is bound as text with an explicit SQL type and cast in the
     * query: a bare null UUID parameter leaves the driver with no type to send, and
     * Postgres cannot infer one for a null.
     */
    private static MapSqlParameterSource params(AccessScope scope, UUID projectId) {
        return new MapSqlParameterSource()
                .addValue("privileged", scope.privileged())
                .addValue("userId", scope.userId() == null ? null : scope.userId().toString(), Types.VARCHAR)
                .addValue("projectId", projectId == null ? null : projectId.toString(), Types.VARCHAR);
    }

    /** pgvector's text input format: [0.1,0.2,...]. */
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
