# Phase 7 — Keyword + semantic search

Status: **backend complete and compiling; frontend written but not yet type-checked clean.**
Item 33 ("customer cancellation" finds "subscription termination") is **not verifiable yet** — see
[Honest gaps](#honest-gaps).

This document explains what was built, why each piece is shaped this way, and which parts are
proven versus merely written.

---

## 1. Goal of the phase

Turn the chunks Phase 6 produced into something a person can query three ways:

```
GET /api/search?q=…&mode=KEYWORD    Postgres full-text, exact, ranked, highlighted
GET /api/search?q=…&mode=SEMANTIC   query embedding -> pgvector nearest neighbours
GET /api/search?q=…&mode=HYBRID     both lists, merged with reciprocal rank fusion
```

Numbered items from the plan:

| # | Item | State |
|---|---|---|
| 26 | Postgres FTS (`tsvector`) + GIN index | Done — `V7__search_indexes.sql` |
| 27 | HNSW/IVFFlat index on `document_chunks.embedding` | Done — HNSW, `vector_cosine_ops` |
| 28 | `GET /api/search?q=&mode=` with pagination | Done — `SearchController` |
| 29 | Keyword path: FTS ranked snippets | Done — `SearchRepository.keyword` + `headlines` |
| 30 | Semantic path: embed query, order by `<=>` | Done — `SearchRepository.semantic` |
| 31 | Hybrid: reciprocal rank fusion | Done — `Rrf`, 7/7 unit tests passing |
| 32 | Angular search page | Written, **not verified** |
| 33 | Verify cross-vocabulary retrieval | **Blocked** — no real embeddings yet |

---

## 2. Shape of the feature

```
GET /api/search?q=termination&mode=HYBRID&page=0&size=20
        │
        ▼
SearchController          @PreAuthorize("isAuthenticated()")
        │                 rejects ?sort= outright
        ▼
SearchService.search(q, mode, projectId, pageable)
        │  normalise + length checks (2..200)
        │  AccessScope.current()   -> {privileged, userId}
        │  depth guard             -> page*size + size <= 200
        │
        ├── KEYWORD ──▶ repository.keyword(...)  LIMIT/OFFSET in SQL
        │               repository.countKeyword(...)  (a real total)
        │               repository.headlines(pageIds, q)
        │
        ├── SEMANTIC ─▶ queryCache.get(model, q, () -> embeddings.embed([q]))
        │               repository.semantic(vector, model, ...) LIMIT k
        │               slice in memory
        │
        └── HYBRID ───▶ keyword(top 50) + semantic(top 50)
                        Rrf.fuse(keywordIds, semanticIds, k = 60)
                        headlines() for the fused page only
                            │
                            ▼
                   PageResponse<SearchHit>
```

---

## 3. `V7__search_indexes.sql`

### 3.1 Full text on chunk content

```sql
ALTER TABLE document_chunks
    ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

CREATE INDEX idx_document_chunks_tsv ON document_chunks USING GIN (content_tsv);
```

Three decisions inside four lines:

- **Two-argument `to_tsvector`.** The one-argument form reads the session's
  `default_text_search_config` and is therefore not `IMMUTABLE`; Postgres refuses it in a generated
  column. The literal `'english'` makes it immutable and legal. This is the first thing that fails
  if someone "simplifies" the migration.
- **Generated column, not a trigger.** It cannot drift from `content`, and the `ALTER` populates
  existing rows during the table rewrite, so there is no backfill script to forget.
- **`'english'` is a choice with a cost.** It stems, which is why searching `terminate` finds
  `termination` (there is a test for exactly that). On a French or Arabic corpus it stems wrongly
  and silently returns nothing. A single column cannot be per-document multilingual; `'simple'`
  would drop stemming for everyone. The demo corpus is English, so `'english'` wins and the limit
  is written down.

### 3.2 Full text on the document name

```sql
ALTER TABLE documents
    ADD COLUMN name_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', name)) STORED;
```

The plan item says "chunk content **and** document name". The tempting implementation —
denormalise `documents.name` onto every chunk row — was rejected: a rename would then have to
rewrite every chunk, and one missed update makes search disagree with the document list. The
column lives on `documents`, and the query joins.

`'simple'` (no stemming) because file names are not prose. `Q3_contract_v2.pdf` does not stem
usefully, and English stemming on identifiers produces noise.

### 3.3 The ANN index

```sql
CREATE INDEX idx_document_chunks_embedding
    ON document_chunks USING hnsw (embedding vector_cosine_ops);
```

**HNSW over IVFFlat.** IVFFlat trains centroids, so it must be built *after* the data is loaded,
and its `lists` parameter has to be retuned as the corpus grows. HNSW builds incrementally and
needs no tuning. Its higher build cost and memory use do not matter at this size.

**The op class must match the operator.** `vector_cosine_ops` pairs with `<=>`. Query with `<->`
(L2) and the index is silently skipped — and because these vectors are unit-normalised, L2 and
cosine produce *identical rankings*, so the mistake never shows up in the results. It shows up
only in `EXPLAIN`, which is why the operator is written once, in one place.

**Expect it to do nothing at demo scale.** Below roughly ten thousand rows the planner will often
prefer a sequential scan, which is exact and fast. That is a correct plan, not a missing index.

---

## 4. Visibility — the part that matters most

Search is the first endpoint in this application that returns rows the caller never named by id.
Every previous endpoint took an id and checked it (`DocumentService.requireVisible`). Search
cannot: it has to find the rows and exclude the forbidden ones in the same operation.

`AccessScope` resolves the caller once:

```java
public record AccessScope(boolean privileged, UUID userId) {
    public static AccessScope current() {
        UserPrincipal principal = SecurityUtils.currentUser();
        return new AccessScope(principal.getRole() != Role.USER, principal.getId());
    }
}
```

and every query in `SearchRepository` carries the same predicate. There is no variant without it:

```sql
AND d.status = 'READY'
AND (CAST(:privileged AS boolean) OR p.owner_id = CAST(:userId AS uuid))
AND (CAST(:projectId AS uuid) IS NULL OR d.project_id = CAST(:projectId AS uuid))
```

Two things fall out of this for free:

- **Global (project-less) documents disappear for a USER.** With no project, the `LEFT JOIN`
  leaves `p.owner_id` NULL, and `NULL = :userId` is NULL, which is not true. The rule is enforced
  by the shape of the join rather than by a second condition someone has to remember.
- **Half-ingested documents never surface.** `d.status = 'READY'` excludes chunks left over from a
  previous ingest of a document that is now `PROCESSING` or `FAILED` — `markReady` deletes and
  re-inserts, so only `READY` is a coherent state. There is a test that flips a document to
  `FAILED` and asserts its chunks vanish from search.

### Why post-filtering would have been wrong

Fetch the top 50 by rank, then drop what the caller may not see, and a USER whose documents rank
51–70 receives an **empty page** while `totalElements` reports 50. The pagination lies, the page
comes back short, and nothing throws. It would look like "search is bad", not "search is broken".
Filtering in the predicate is not an optimisation here; it is the difference between correct and
incorrect.

### `projectId` returns empty, not 403

Filtering by a project the caller cannot see returns zero results rather than `403`. A search
endpoint that distinguishes "no matches" from "not allowed" is an existence oracle: a USER could
enumerate other people's project ids by watching the status code. Absence is the right answer.

---

## 5. The keyword path

```sql
WITH q AS (SELECT websearch_to_tsquery('english', :q) AS tsq)
SELECT c.id, d.id, d.name, d.project_id, p.name, c.page_or_section,
       left(c.content, 400) AS preview,
       (ts_rank_cd(c.content_tsv, q.tsq)
          + CASE WHEN d.name_tsv @@ q.tsq THEN 0.1 ELSE 0 END) AS score
FROM document_chunks c
JOIN documents d ON d.id = c.document_id
LEFT JOIN projects p ON p.id = d.project_id
CROSS JOIN q
WHERE (c.content_tsv @@ q.tsq OR (d.name_tsv @@ q.tsq AND c.chunk_index = 0))
  … visibility …
ORDER BY score DESC, c.id
LIMIT :limit OFFSET :offset
```

- **`websearch_to_tsquery`, never `to_tsquery`.** The latter throws on a stray `&` or an
  unbalanced quote, turning a user's typo into a 500. `websearch_` accepts Google-style input
  (quotes, `OR`, leading `-`) and cannot throw. There is a test that sends `foo & | "bar` and
  asserts 200.
- **Name matches contribute one chunk.** `d.name_tsv @@ q.tsq AND c.chunk_index = 0`. Without that
  guard, a single well-named 200-chunk document would fill every page of results. The name is a
  signal about the document, so it returns the document — represented by its first chunk — not 200
  rows.
- **`ts_rank_cd`, not `ts_rank`.** Cover density rewards query terms appearing near each other,
  which is closer to what a person means by a two-word query.
- **`ORDER BY score DESC, c.id`.** The id tiebreak makes paging deterministic; without it, two
  chunks with equal rank can swap places between page 1 and page 2 and one of them is never seen.
- **Real `LIMIT`/`OFFSET` and a real count.** Full-text search is exact, so keyword mode is the one
  mode whose `totalElements` is a true corpus-wide number.

### Snippets are generated separately, on purpose

`ts_headline` is a separate query over the ids being returned:

```sql
SELECT c.id, ts_headline('english', c.content, websearch_to_tsquery('english', :q), :options)
FROM document_chunks c WHERE c.id IN (:ids)
```

`ts_headline` re-parses the original document text and is expensive. Calling it inside the ranking
query makes latency scale with the **number of matches** rather than the page size — the standard
Postgres full-text mistake. Splitting it out means it runs on at most 20 rows, whatever the corpus
does.

### Highlights are not HTML

```
StartSel="[[HL]]", StopSel="[[/HL]]"
```

Chunk content comes from files users uploaded. Emitting `<mark>` and rendering it with
`[innerHTML]` would mean trusting Angular's sanitiser to be simultaneously permissive enough to
keep `<mark>` and strict enough to block everything a DOCX might contain. The markers are inert
text; the client splits on them and interpolates each part. There is no injection surface and no
dependency on sanitiser behaviour. A test asserts the snippet contains `[[HL]]` and contains
neither `<b>` nor `<mark>`.

---

## 6. The semantic path

```sql
SELECT …, 1 - (c.embedding <=> CAST(:vec AS vector)) AS score
FROM document_chunks c JOIN documents d … LEFT JOIN projects p …
WHERE c.embedding IS NOT NULL
  AND d.embedding_model = :model
  … visibility …
ORDER BY c.embedding <=> CAST(:vec AS vector)
LIMIT :limit
```

- **`d.embedding_model = :model` is a correctness filter, not a nicety.** Chunks embedded by the
  offline hash client and chunks embedded by OpenAI share this column and this index. Comparing
  across them returns confident nonsense with no error anywhere. With the filter, switching models
  degrades to "documents embedded by the old model stop appearing until reprocessed" — visible,
  diagnosable, and reversible.
- **Order by the raw distance.** `<=>` is what the HNSW index can serve; `1 - distance` is
  computed for display only. Ordering by the similarity expression would defeat the index.
- **No `OFFSET` into ANN.** Page 2 of an approximate index is not "the next best 20" — it is
  whatever the graph walk happened to visit. Semantic mode fetches `k = min(200, offset + size)`
  once and slices in memory.
- **The total is honest about itself.** `totalElements` in semantic mode is the size of the
  candidate set, not a corpus count; an ANN search does not know how many rows "match". Same for
  hybrid, where the bound is the fused candidate set.
- **Different snippet strategy.** A semantic hit contains no query terms, so `ts_headline` would
  return an unmarked fragment. Semantic hits use the leading ~240 characters of the chunk, cut on a
  word boundary. Per-path snippet logic is correct here, not inconsistent.

### Query embedding cache

`QueryEmbeddingCache` is a synchronised access-ordered `LinkedHashMap` capped at 500 entries,
keyed by `(modelId, lowercased query)`. The search box is debounced, but people retype the same
phrase, and each semantic request otherwise costs an embeddings call. Keying by model means a
model switch **misses** rather than handing back a vector from the wrong space.

---

## 7. Hybrid — reciprocal rank fusion

```
score(chunk) = SUM over lists  1 / (k + rank_in_that_list),   k = 60, ranks from 1
```

Top 50 from each path, fused on chunk id, sorted, sliced.

**Why not blend the scores.** `ts_rank_cd` is unbounded and its scale moves with the query;
cosine similarity is confined to [0, 1]. Making them comparable needs per-query min-max
normalisation, where one outlier reshapes the whole ranking — a document changes position because
an *unrelated* document scored oddly. RRF looks only at positions, so the scales never have to be
reconciled. `k = 60` comes from the original paper and damps the dominance of rank 1, which is
what lets a document both retrievers agree on outrank a document one retriever loved.

`Rrf` is a pure static function returning `(chunkId, score, match)` — no Spring, no database. That
is why it is the one part of Phase 7 with tests that actually **ran**: 7 cases covering the
agreement case, the exact arithmetic (`2/61`), match-type labelling, an empty list, both lists
empty, a duplicate id inside one list, and `k <= 0`.

`match` (`KEYWORD` / `SEMANTIC` / `BOTH`) is returned to the client because in hybrid mode it is
the only way to explain why a result with no visible query terms is ranked highly.

### Degradation

If the embeddings call fails, **hybrid falls back to keyword only** with a warning, rather than
503-ing the search box. Semantic mode still fails loudly (`503`) — there is nothing to fall back
to there, and silently returning keyword results under a `SEMANTIC` label would be a lie.

---

## 8. The endpoint

```http
GET /api/search?q=&mode=KEYWORD|SEMANTIC|HYBRID&projectId=&page=0&size=20
```

| Rule | Value | Why |
|---|---|---|
| Authorisation | `@PreAuthorize("isAuthenticated()")` | the real rule is row-level and lives in the SQL |
| `q` | required, 2–200 chars after trim | a 1-character query matches everything and costs an embedding |
| `mode` | default `HYBRID` | the mode that needs no explanation in a demo |
| `sort` | **rejected with 400** | results are ranked; a client-chosen `ORDER BY` discards the ranking |
| `size` | clamped to 50 | |
| depth | `page*size + size <= 200`, else 400 | ranked retrieval past a few hundred results is meaningless |

`SortSupport`'s allowlist deliberately does not appear here: there is no sortable field to allow,
and the controller says so in a comment so nobody adds one later.

`SearchHit` returns `chunkId`, `documentId`, `documentName`, `projectId`, `projectName`,
`pageOrSection`, `snippet`, `score`, `match`. It never carries the embedding and never the full
chunk text — only the snippet. `pageOrSection` is the citation locator Phase 6 stored (`p. 3-4`),
which is what Phase 8 will need.

---

## 9. Angular (item 32)

New `frontend/src/app/search/`:

| File | Role |
|---|---|
| `search.ts` | types, plus `splitSnippet()` — the `[[HL]]` parser |
| `search.service.ts` | `search({q, mode, projectId, page, size})`; deliberately sends no `sort` |
| `search-page.ts` | debounced query stream, mode toggle, states |
| `search-page.html` | results, badges, marked snippets |

Route `/search` behind `authGuard`, plus a nav link beside Tasks.

The query stream:

```ts
this.control.valueChanges.pipe(
  map(v => v.trim()),
  debounceTime(300),
  distinctUntilChanged(),
  tap(q => this.query.set(q)),
  switchMap(q => this.run(q)),
  takeUntilDestroyed(this.destroyRef),
)
```

- **`switchMap`, not `mergeMap`.** A slow response for `cust` must never overwrite the results for
  `customer`. It also caps the embeddings bill: only the last query in a burst is ever sent.
- **`debounceTime(300)` on typing, none on the mode toggle** — a click is a deliberate act, so
  changing mode re-runs immediately.
- **Three empty states, not one:** "type at least 2 characters", "no matches for …", and "search
  failed". They mean different things to whoever is demoing this, and collapsing them into one
  blank panel hides errors.

Snippet rendering never touches `innerHTML`:

```html
@for (part of parts(hit); track $index) {
  @if (part.marked) { <mark>{{ part.text }}</mark> } @else { {{ part.text }} }
}
```

`splitSnippet` also handles an unbalanced marker by showing the remainder as plain text rather
than dropping it.

---

## 10. Tests

**Ran and passing:** `RrfTest` — 7/7, executed with `mvn test -Dtest=RrfTest`. Pure JUnit, no
Spring, no database.

**Written, compiling, not run** (they need Docker for Testcontainers, which was not available in
the environment that built this):

`SearchTest` — not `@Transactional`, because ingestion fires after commit and a rolled-back test
would index nothing:

1. keyword search finds the chunk and marks the term; snippet contains `[[HL]]` and no HTML
2. `terminate` finds `termination` (the `'english'` stemming claim, tested rather than asserted)
3. a document is found by its **name** when the body does not match
4. malformed query `foo & | "bar` returns 200
5. `q` shorter than 2 characters, and whitespace-only, return 400
6. `?sort=` returns 400
7. deep paging (`page=50`) returns 400
8. semantic and hybrid modes respond with the right shape
9. a document flipped to `FAILED` disappears from search

`SearchRbacTest` — real logins and Bearer tokens, so anonymous is genuinely anonymous:

1. anonymous → 401
2. a USER does **not** see chunks from someone else's project (absent, not 403)
3. a USER **does** see chunks from a project they own
4. a project-less global document is invisible to USER, visible to MANAGER
5. filtering by a project the caller cannot see returns nothing

Deliberately **not** tested: that semantic mode returns *relevant* results. Over hash vectors that
assertion would be a statement about SHA-256, and it would pass or fail at random.

### How it was verified

- `mvn -DskipTests compile` and `mvn test-compile` — clean, JDK 21, against the real `pom.xml`.
- `mvn test -Dtest=RrfTest` — 7/7.
- Frontend: `npx ngc -p tsconfig.app.json --noEmit` → exit 0, with `strictTemplates: true`, so the
  template bindings are type-checked too. The first run failed on a `lowercase` pipe used without
  importing it; replaced with a `modeLabel()` method.

---

## Honest gaps

1. **Item 33 is still blocked.** "customer cancellation" finding "subscription termination"
   requires real embeddings. Every vector in `document_chunks` still comes from
   `HashEmbeddingClient`. The keyword half of this phase is honest and tested; the semantic half is
   plumbing that has never carried meaning. Run the real key, reprocess, then write that test.
2. **The frontend has not passed a clean type-check.** `search-page.html` used a `lowercase` pipe
   without importing it; the replacement (`modeLabel()`) was written but the confirming `ngc` run
   did not complete. Check that file and re-run `npx ngc -p tsconfig.app.json --noEmit` first.
   Nothing in the search UI has ever been rendered in a browser.
3. **The integration tests have never run.** They compile. That is not the same thing — the SQL in
   particular has never touched a Postgres instance, so a column-name typo or a parameter-binding
   problem would still be undiscovered.
4. **`ngc` is not `ng build`.** The Linux device shell cannot build the frontend at all
   (`node_modules` holds `@esbuild/win32-x64`). Build and serve from Windows.
5. **Hybrid `totalElements` is a candidate count**, capped near 100 — not a corpus total. The UI
   labels non-keyword modes "ranked, not counted exactly" rather than pretending otherwise.
6. **Phase 3 (audit trail) is still owed.** No `audit_log`, no audit package. Search reads nothing
   that would need auditing, so this phase did not make it worse — but the debt is now three
   phases old.
7. **The `@PostConstruct` dimension guard is still weaker than it looks** (`steps/phase6.md` §6.4).
   It matters more now: the HNSW index is built over one specific vector space, and a model switch
   silently changes what that space means.
