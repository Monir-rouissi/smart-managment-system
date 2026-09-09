# Phase 6 — Chunking, embeddings and pgvector

Status: **complete** (items 18–25), with one item verified only offline — see
[Honest gaps](#honest-gaps) at the end.

This document explains what exists, why each piece is shaped the way it is, and where the
sharp edges are. It covers the backend pipeline and the Angular status UI that closed the
phase.

---

## 1. Goal of the phase

Turn an uploaded file into rows that a vector search can query:

```
documents(status=UPLOADED)  ──▶  document_chunks(content, embedding vector(1536))
                                 documents(status=READY, chunk_count=N)
```

**Done when:** upload a PDF, wait, and `document_chunks` holds rows with non-null embeddings,
while the UI shows the document moving from queued to ready without a page refresh.

Numbered items from the plan:

| # | Item | Where it lives |
|---|---|---|
| 18 | `CREATE EXTENSION vector` | `V1__baseline.sql:4` |
| 19 | `document_chunks` table | `V6__document_chunks.sql` |
| 20 | Chunker (500–800 tokens, 10–15% overlap, page locator) | `ingest/Chunker.java` |
| 21 | `EmbeddingClient` + one implementation | `ingest/EmbeddingClient.java`, `OpenAiEmbeddingClient`, `HashEmbeddingClient` |
| 22 | extract → chunk → embed → insert → `READY` | `ingest/IngestionService.java` |
| 23 | Async; upload still returns 202 | `IngestionTrigger`, `IngestionQueue`, `IngestionRecovery` |
| 24 | Angular: processing / ready / failed | `projects/project-detail.ts` + `.html`, `documents/*` |
| 25 | Verify: chunks exist with non-null embeddings | `DocumentIngestionTest` (offline client) |

---

## 2. The pipeline end to end

```
POST /api/documents  (multipart)
        │
        ├─ write bytes to app.storage.documents-dir/<uuid>
        ├─ INSERT documents(status = UPLOADED, uploaded_by = current user)
        ├─ publish DocumentIngestRequestedEvent
        └─ COMMIT ─────────────────────────────────▶ HTTP 202 + DocumentResponse
                        │
                        │  @TransactionalEventListener(AFTER_COMMIT)
                        ▼
                 IngestionQueue.submit(id)      (bounded pool, 2 threads)
                        │
                        ▼
            IngestionService.process(id)        ← worker thread, NO SecurityContext
                        │
                        ├─ store.claim(id)      UPDATE ... WHERE status IN (UPLOADED, FAILED)
                        │                       → PROCESSING   [tx 1, short]
                        │                       0 rows ⇒ someone else won ⇒ return
                        │
                        ├─ read bytes from disk                (no tx)
                        ├─ TextExtractor.extract(...)          (no tx)  Tika / direct read
                        ├─ Chunker.chunk(text)                 (no tx)  jtokkit windows
                        ├─ EmbeddingClient.embed(contents)     (no tx)  HTTP, batched, retried
                        │
                        └─ store.markReady(...)  DELETE old chunks
                                                 INSERT N chunks
                                                 UPDATE documents → READY, chunk_count,
                                                        embedding_model, processed_at
                                                                        [tx 2, short]
             any exception ─▶ store.markFailed(id, message)
                              UPDATE → FAILED, error_message, retry_count += 1  [tx 3]
```

The single most important structural decision: **`IngestionService` is not `@Transactional`.**
Only `IngestionStore` is, and each of its methods uses `Propagation.REQUIRES_NEW`. The slow
parts — disk read, Tika parse, embedding HTTP — run with no transaction open, so a document
with 200 chunks cannot pin a HikariCP connection for the length of an OpenAI round trip.

`IngestionStore` is a **separate bean** for a boring but fatal reason: a `@Transactional`
method called from another method of the *same* bean bypasses the Spring proxy and silently
runs without a transaction. Splitting the writes into their own bean makes the annotations
actually take effect.

---

## 3. Schema

### 3.1 The extension

`V1__baseline.sql:4` already had `CREATE EXTENSION IF NOT EXISTS vector;`, so item 18 needed
no new migration. It lives in the baseline rather than in V6 because the extension is a
property of the database, not of the documents feature.

### 3.2 `V6__document_chunks.sql`

```sql
ALTER TABLE documents
    ADD COLUMN embedding_model VARCHAR(60),
    ADD COLUMN chunk_count     INT         NOT NULL DEFAULT 0,
    ADD COLUMN error_message   TEXT,
    ADD COLUMN retry_count     INT         NOT NULL DEFAULT 0,
    ADD COLUMN processed_at    TIMESTAMPTZ;

CREATE TABLE document_chunks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    chunk_index     INT  NOT NULL,
    content         TEXT NOT NULL,
    page_or_section VARCHAR(120),
    token_count     INT  NOT NULL,
    embedding       vector(1536),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_chunk UNIQUE (document_id, chunk_index)
);

CREATE INDEX idx_document_chunks_document ON document_chunks (document_id, chunk_index);
CREATE INDEX idx_documents_status ON documents (status, updated_at);
```

Column by column:

- **`page_or_section`** is the plan's "locator". It is what a Phase 8 citation will print
  (`"p. 3-4"`). `NULL` for formats with no page structure (txt, md, docx).
- **`token_count`** is stored because it is already known at chunk time and re-tokenising
  later to answer "how big is this chunk" would be wasteful. It also makes a bad chunker
  obvious in one `SELECT`.
- **`embedding vector(1536)`** — nullable in the schema, never null in practice: a document
  only reaches `READY` when every vector was produced. Nullable avoids a migration-time
  chicken-and-egg problem and lets a future "chunk now, embed later" split happen without a
  DDL change.
- **`UNIQUE (document_id, chunk_index)`** makes a duplicated re-ingest a database error
  rather than a silently doubled corpus.
- **`ON DELETE CASCADE`** — deleting a document must not leave orphan vectors that a search
  would still return.
- **`idx_documents_status (status, updated_at)`** serves the recovery scan, which filters on
  exactly those two columns.

**No ANN index yet, on purpose.** An HNSW or IVFFlat index belongs with the Phase 7 query:
building one on an empty table proves nothing, costs insert throughput during ingestion, and
IVFFlat in particular needs representative data present before its lists are trained. The
choice between HNSW and IVFFlat is a query-shape decision, so it is deferred to the phase
that has a query.

**Dimension is hard-coded at 1536.** Switching to a model with a different width is a new
column (or a new table), not an `ALTER` — you cannot reinterpret existing vectors.

---

## 4. Extraction — `TextExtractor`

```
txt / md   --> read the bytes as UTF-8 directly          (one segment, label = null)
pdf / docx --> Tika AutoDetectParser + PageCollectingHandler
                  PDF  --> one Segment per <div class="page">   (label = "1", "2", ...)
                  DOCX --> a single segment                     (label = null)
```

Plain text and markdown skip Tika entirely — running them through the AutoDetectParser would
only add a content-detection round trip to reach the same string.

`PageCollectingHandler` is where the page structure survives. Tika's PDF parser emits each
page as `<div class="page">`; the handler collects the text between those boundaries into a
list of segments. Without it, page numbers are lost at the first step and no later phase can
recover them — this is the whole reason for a custom handler rather than `BodyContentHandler`.

`clean()` runs on every segment and does four things:

1. `U+00A0` (non-breaking space) becomes an ordinary space.
2. Drops `U+0000` (Postgres rejects NUL in a `text` column), the soft hyphen `U+00AD`, the
   zero-width range `U+200B`–`U+200F`, and the BOM `U+FEFF`. PDF extraction produces these
   constantly and each one costs a token while carrying no meaning.
3. Collapses runs of horizontal whitespace to a single space.
4. Collapses three-or-more consecutive newlines to two.

Failure modes, all of which end as `FAILED` with a readable message:

- empty file → `"File is empty"`
- Tika throws (corrupt PDF) → `"Could not read the file (application/pdf): ..."`
- scanned PDF, no text layer →
  `"No extractable text found. A scanned PDF needs OCR, which this phase does not do."`

`maxExtractedCharacters` (default 10,000,000) caps the extracted text so one pathological
file cannot exhaust heap.

---

## 5. Chunking — `Chunker`

### 5.1 Why real tokens

Sizing is done on **cl100k_base tokens via jtokkit**, not on a `characters / 4` estimate.
The estimate is off by 30–40% on code, tables and non-English text — precisely the documents
where an oversized chunk gets rejected by the embeddings API. Paying for a real tokenizer at
ingest time buys a hard guarantee about request size.

### 5.2 One token stream, parallel segment offsets

The naive implementation chunks each page independently, which produces a short runt chunk at
every page boundary. This one tokenises **everything into a single stream** and records where
each segment starts:

```
segments:  [ page 1 ....... ][ page 2 ....... ][ page 3 ....... ]
tokens:    0                420               905              1500
segmentStart = [0, 420, 905]     labels = ["1", "2", "3"]
```

A window is then a plain slice of that stream, and its label comes from where its first and
last token land:

```
window [360, 960)  --> first token in segment 0 ("1"), last token in segment 2 ("3")
                   --> label "p. 1-3"
```

A same-segment window gets `"p. 2"`; one that straddles a break gets `"p. 1-2"`. Nothing is
dropped and nothing is truncated at a page edge.

### 5.3 The window loop

```
windowSize = app.ingest.chunk-tokens   = 600
overlap    = app.ingest.overlap-tokens = 90      (15%)
step       = windowSize - overlap      = 510
```

For a 1500-token document:

| index | start | end | overlap with previous |
|---|---|---|---|
| 0 | 0 | 600 | — |
| 1 | 510 | 1110 | 90 tokens |
| 2 | 1020 | 1500 | 90 tokens; the last window is short, `end = min(start+600, total)` |

The loop breaks as soon as `end == total`, so there is never a trailing empty window.

Validation is fail-fast, per call:

- `chunk-tokens <= 0` → `IllegalStateException`
- `overlap-tokens < 0` or `>= chunk-tokens` → `IllegalStateException`
  (an overlap equal to the window size makes `step == 0`, i.e. an infinite loop — far better
  as a clear exception than as a hung worker)

**Why overlap at all:** a fact that straddles a window boundary would otherwise be split
across two chunks and retrieved by neither. 10–15% is the usual trade between recall and
paying to embed the same text twice.

### 5.4 One latent nit, stated honestly

`index` is advanced by the `for` loop regardless of whether the window survived the
`content.isEmpty()` check:

```java
for (int start = 0, index = 0; start < total; start += step, index++) {
    ...
    if (!content.isEmpty()) { chunks.add(new Chunk(index, ...)); }
```

So a window that decodes to nothing but whitespace **consumes an index**, leaving a gap in
`chunk_index`. After `clean()`, a fully blank 600-token window is close to impossible, and
nothing today depends on contiguity — so this is a note, not a bug. But "chunk indices are
contiguous" is an assumption here, not an invariant.

---

## 6. Embeddings

### 6.1 The interface

```java
public interface EmbeddingClient {
    List<float[]> embed(List<String> texts);
    String modelId();
    int dimensions();
}
```

Two implementations, chosen in `IngestionConfig` by whether `app.ingest.openai.api-key` is set.

### 6.2 `OpenAiEmbeddingClient`

Plain Spring `RestClient` against `POST {base-url}/embeddings`. No vendor SDK.

- **Batching** — `embedding-batch-size` (default 64) inputs per request. At 600 tokens per
  chunk that is roughly 38k tokens in flight per call.
- **Order safety** — response items are re-sorted by their `index` field before being mapped
  to vectors. The API does not promise response order, and a silent misalignment here would
  attach each chunk's text to a neighbour's vector: the worst bug available in this phase,
  because nothing would ever throw.
- **Count check** — a response whose `data.size()` differs from the batch size raises
  `IngestionException` instead of yielding a partially filled list.
- **Retry policy** — `429` and `5xx` are retried; everything else is not. A bad key or a bad
  model name is permanent, and retrying it only burns time. Backoff is
  `500ms * 2^(attempt-1)`, bounded by `max-attempts` (default 3), so an outage surfaces as a
  `FAILED` document rather than a loop that burns quota.
- **Interruption** — an interrupted backoff re-sets the interrupt flag before throwing, so the
  pool can actually shut down.

### 6.3 `HashEmbeddingClient` (offline fallback)

SHA-256 of the chunk text seeds a `Random`, which fills a 1536-float vector normalised to unit
length. Deterministic: the same text always yields the same vector.

Its purpose is to make the *pipeline* runnable and testable with no network and no API key. It
logs a `WARN` at construction saying exactly what it is. **The vectors carry no semantics** —
similarity over them is noise. It stands in for the transport, not for the model.

### 6.4 The dimension guard, and what it misses

```java
@PostConstruct
void verifyEmbeddingDimensions() {
    if (properties.getDimensions() != 1536 || embeddings.dimensions() != 1536) throw ...
}
```

A dimension mismatch is a **startup failure**, not a per-row insert error discovered hours
later.

But note what it cannot catch. `OpenAiEmbeddingClient.dimensions()` returns
`properties.getDimensions()` — the configured number, not anything the model reports — and the
request body is only `{model, input}`: the `dimensions` parameter the API supports is **not
sent**. So setting `model: text-embedding-3-large` while leaving `dimensions: 1536` passes the
startup guard, and the 3072-wide vectors that come back are caught only later, per document,
by `IngestionService.verify()` → the document ends `FAILED`. Loud enough, but at run time
instead of boot time. Sending the `dimensions` parameter (3-large supports shortening) would
make the guard mean what it appears to mean.

---

## 7. Orchestration and transaction boundaries

### 7.1 The claim is the concurrency control

```java
@Modifying
@Query("""
        update Document d
           set d.status = :to, d.errorMessage = null, d.updatedAt = :now
         where d.id = :id and d.status in :from
        """)
int claimForProcessing(...);   // from = {UPLOADED, FAILED}, to = PROCESSING
```

Three independent things can race for the same document: a double submit from the UI, a manual
reprocess, and the startup recovery scan. There is no lock and no queue-level deduplication —
instead exactly one `UPDATE` matches the `status IN (UPLOADED, FAILED)` predicate and returns
`1`; every loser gets `0` and returns immediately. The row is the lock.

Two details that are easy to get wrong here:

- `updatedAt` is set **explicitly**, because a bulk `@Modifying` update bypasses Spring Data's
  auditing listener — and the recovery scan reads exactly that column to decide what is stale.
- `errorMessage` is cleared on claim, so a retried document does not display a stale failure
  reason while it is being reprocessed.

### 7.2 `markReady` replaces, never appends

```java
chunks.deleteByDocumentId(documentId);   // then insert the new ones
```

Re-processing a document that already has chunks must **replace** them. Appending would double
the corpus on every retry and quietly wreck retrieval quality. The `UNIQUE (document_id,
chunk_index)` constraint is the backstop if this delete is ever forgotten.

The status flip and the chunk insert live in **one** transaction, so `READY` and the rows it
promises appear together or not at all.

### 7.3 `markFailed`

Sets `FAILED`, stores the message (truncated to 1000 chars — the UI shows it, and a Tika stack
message can be enormous), and increments `retry_count`. The counter is the record of how many
times this document has been attempted, which is what makes a runaway retry visible.

`describe(e)` stores the exception *message*, falling back to the class name when the message is
null — so the UI never shows an empty red box.

### 7.4 No `SecurityContext` on the worker

The ingestion thread is not the request thread, and the uploader's JWT may well have expired by
the time the job runs. **Nothing in the pipeline may call a `@PreAuthorize` method.** The actor
is already on the row (`documents.uploaded_by`), and when Phase 3 (audit) lands, that column —
not `SecurityUtils.currentUser()` — is where the audit actor must come from.

---

## 8. Async delivery

### 8.1 `IngestionTrigger` — after commit, never inside it

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onIngestRequested(DocumentIngestRequestedEvent event) { queue.submit(event.documentId()); }
```

Submitting from *inside* the uploading transaction is the classic bug: the worker starts
immediately, reads the document row on its own connection, and does not find it — intermittently,
and more often on a fast machine. `AFTER_COMMIT` makes that race structurally impossible.

### 8.2 `IngestionQueue` — bounded, with a real rejection policy

`IngestionConfig` builds a `ThreadPoolTaskExecutor`: 2 worker threads, queue capacity 100,
`CallerRunsPolicy`. Bounded on purpose — an unbounded queue turns a burst of uploads into an
OOM. `CallerRunsPolicy` means that when the queue is full the submitting thread does the work,
which throttles the producer instead of dropping documents.

`submit()` wraps `process()` in a `try/catch` for `RuntimeException`: `process()` already records
its own failures on the row, so this is purely the last line of defence keeping a rogue task from
killing a pool thread.

`app.ingest.enabled=false` makes `submit()` a no-op — useful for tests that only want upload
behaviour.

### 8.3 `IngestionRecovery` — the in-memory queue's insurance

The queue is in memory (no Kafka in this phase), so a restart loses whatever was still queued and
strands anything that was mid-flight in `PROCESSING`. On `ApplicationReadyEvent`:

1. `releaseStale(now - stale-processing-after)` — any row still `PROCESSING` and untouched for
   10 minutes goes back to `UPLOADED`.
2. `findQueued(500)` — everything `UPLOADED`, oldest first, is re-submitted.

This is what makes the claim "the document row *is* the queue" actually true across restarts.
It is also the piece with **no test** (it needs a context restart mid-ingest); it is reasoned
about and coded, not proven.

---

## 9. API surface

| Endpoint | Roles | Notes |
|---|---|---|
| `POST /api/documents` (multipart) | ADMIN, MANAGER, USER | `202 Accepted` + `Location`; USER only on a project they own |
| `POST /api/documents/{id}/reprocess` | ADMIN, MANAGER | `202`; `409` while already `PROCESSING` |
| `GET /api/documents/{id}` | ADMIN, MANAGER, USER | returns `status`, `chunkCount`, `embeddingModel`, `errorMessage`, `processedAt` |
| `GET /api/documents/{id}/download` | ADMIN, MANAGER, USER | original bytes |
| `GET /api/projects/{projectId}/documents` | ADMIN, MANAGER, USER | the list the UI polls |

Reprocess is ADMIN/MANAGER only because it costs embedding calls; a USER able to trigger it in a
loop is a billing problem, not a feature.

Chunk text is **not exposed by any endpoint**. Chunks stay internal until Phase 7 needs them.

---

## 10. The Angular status UI (item 24)

### 10.1 What was actually broken

`project-detail.html` already rendered `<span class="badge">{{ d.status }}</span>`. It rendered
**once** — from `loadDocuments()` in the constructor and again after an upload. At that instant
the document is `UPLOADED`; the worker moves it to `PROCESSING` and then `READY` seconds later,
and the page never found out. In practice every uploaded document looked permanently stuck at
`UPLOADED` unless the user pressed F5.

The TypeScript model was also five fields behind the API: `chunkCount`, `embeddingModel`,
`errorMessage` and `processedAt` had been returned since V6 and were silently dropped.

### 10.2 Files changed

| File | Change |
|---|---|
| `documents/document.ts` | added the four missing fields; added `PENDING_DOCUMENT_STATUSES` and `isDocumentPending()` |
| `documents/document.service.ts` | added `reprocess(id)` |
| `projects/project-detail.ts` | polling, status label/class helpers, `canReprocess()`, `reprocessDocument()` |
| `projects/project-detail.html` | status badge + tooltip, inline failure message, Chunks column, Reprocess button, auto-refresh note |
| `styles.css` | `.badge.queued / .processing / .ready / .failed`, `.ingest-error` |

`isDocumentPending()` lives next to the type rather than in the component so the poll condition
and the status union can never drift apart.

### 10.3 The poll

```ts
private watchIngestion(): void {
  if (this.pollSub && !this.pollSub.closed) return;          // re-entrancy guard
  if (!this.documents().some(isDocumentPending)) { this.watchingIngestion.set(false); return; }

  this.watchingIngestion.set(true);
  this.pollSub = timer(POLL_INTERVAL_MS, POLL_INTERVAL_MS)
    .pipe(
      take(POLL_MAX_TICKS),                                             // 40 ticks, ~2 min
      switchMap(() => this.documentService.listForProject(this.id).pipe(catchError(() => EMPTY))),
      tap((docs) => this.documents.set(docs)),                          // store BEFORE the stop check
      takeWhile((docs) => docs.some(isDocumentPending)),
      takeUntilDestroyed(this.destroyRef),
    )
    .subscribe({ complete: () => this.watchingIngestion.set(false) });
}
```

Every line of that is a decision:

- **`tap` before `takeWhile`.** The first draft put the pending check before `switchMap`, which
  tests *stale* data: the tick that finally returns `READY` would be filtered out and the
  terminal state would never render. Storing first, then deciding whether to continue, renders
  the final state and *then* completes.
- **One request per tick for the whole list**, not one per document. Ten documents is still one
  `GET`.
- **`take(40)`** — a hard cap of about two minutes. A document wedged in `PROCESSING` by a dead
  worker must not poll forever in a backgrounded tab.
- **`catchError(() => EMPTY)`** — an expired token or a deleted project ends the poll instead of
  hammering a failing endpoint every 3s.
- **`takeUntilDestroyed(this.destroyRef)`** — a bare `setInterval` keeps firing after the user
  navigates away. `destroyRef` is injected because `watchIngestion()` is called outside the
  constructor's injection context.
- **Re-entrancy guard** — upload, then reprocess, then upload again must not stack three timers
  on the same list.

### 10.4 What each state shows

| Status | Badge | Extra |
|---|---|---|
| `UPLOADED` | grey — "Queued" | — |
| `PROCESSING` | amber — "Processing..." | list refreshes itself |
| `READY` | green — "Ready" | chunk count in its own column; `embeddingModel` as the badge tooltip |
| `FAILED` | red — "Failed" | `errorMessage` inline under the badge and as the tooltip; Reprocess |

`canReprocess()` mirrors the backend exactly — ADMIN/MANAGER only, and only on a settled
document — so a USER never sees a button that would 403.

### 10.5 Reprocess and the 409

```ts
error: (err) => {
  if (err?.status === 409) { this.loadDocuments(); return; }   // already running: that IS success
  this.uploadError.set(err?.error?.detail ?? 'Reprocess failed.');
}
```

A `409` means ingestion is already running for that document (another tab, or the recovery scan
beat the click). That is the outcome the button asked for, so the UI refreshes and starts
watching rather than showing the user a red error for a successful intention.

---

## 11. Configuration reference

```yaml
app:
  ingest:
    enabled: true
    chunk-tokens: 600             # window size, cl100k_base tokens
    overlap-tokens: 90            # ~15% overlap
    max-chunks-per-document: 2000 # ~1.2M tokens; above this the document FAILS
    embedding-batch-size: 64      # inputs per embeddings request
    dimensions: 1536              # MUST match vector(1536) in V6
    worker-threads: 2
    queue-capacity: 100
    max-extracted-characters: 10000000
    stale-processing-after: 10m
    openai:
      api-key: ""                 # blank -> HashEmbeddingClient (offline)
      model: text-embedding-3-small
      base-url: https://api.openai.com/v1
      timeout: 60s
      max-attempts: 3
```

Dependencies added for the phase: `hibernate-vector`, `tika-core` plus only the PDF and
Microsoft parser modules (not the ~100-jar standard package), `jtokkit`,
`spring-boot-starter-restclient` (Boot 4 does not ship `RestClient` with `starter-webmvc`),
and `awaitility` for tests.

---

## 12. Tests

`ChunkerTest` — 7 cases, no Spring context:

1. chunk indices are contiguous
2. consecutive chunks really overlap by `overlap-tokens`
3. no chunk exceeds the token budget
4. every input token appears in some chunk (no silent loss)
5. page labels are correct within a page
6. a window straddling a page break is labelled `p. 1-2`
7. an invalid overlap is rejected

`DocumentIngestionTest` — 6 cases, Testcontainers, deliberately **not** `@Transactional`
(ingestion fires `AFTER_COMMIT`, so a rolled-back test would never trigger it; `awaitility`
polls for the terminal state):

1. text file → `READY` with 1536-dimension vectors
2. `status` and `chunkCount` visible on the API
3. page labels from a two-page PDF generated with PDFBox
4. corrupt PDF → `FAILED`, message stored, `retry_count = 1`
5. reprocess **replaces** chunks rather than appending
6. deleting a document cascades to its chunks

`DocumentRbacTest` covers reprocess 401 / 403 / 202.

Frontend: `npx ngc -p tsconfig.app.json --noEmit` → exit 0 with `strictTemplates: true`, which
type-checks the templates as well as the TypeScript.

> `npx ng build` **cannot run in the Linux device shell** — `node_modules` contains
> `@esbuild/win32-x64`. Build and `ng serve` from Windows. `ngc` is pure JavaScript and runs
> either way.

---

## 13. How to verify by hand

```bash
docker compose up -d db
cd backend && ./mvnw spring-boot:run     # no API key needed: offline embeddings
cd frontend && npm start                 # from Windows
```

Log in as `manager@local`, open a project, upload a PDF. The badge should go
**Queued → Processing... → Ready** on its own, with a chunk count. Then:

```sql
SELECT chunk_index, token_count, page_or_section, embedding IS NULL AS no_vec
FROM document_chunks
WHERE document_id = '<uuid>'
ORDER BY chunk_index;
```

Expect indices starting at 0, no `no_vec = true`, `documents.chunk_count` equal to the row count,
`embedding_model` set, and `processed_at` populated.

For the failure path, upload a deliberately corrupt PDF: the badge should turn red with the
message, and the Reprocess button should appear for ADMIN/MANAGER.

---

## 14. What was deliberately not done

- **No ANN index.** Belongs with the Phase 7 query.
- **No Kafka / no durable broker.** An in-memory pool plus the recovery scan is the right size
  for this application; a broker is a second piece of infrastructure to run and explain.
- **No OCR.** A scanned PDF ends `FAILED` with that exact message rather than silently storing
  nothing.
- **No chunk-text API.** Nothing needs it before Phase 7.
- **No `@Async` annotation.** An explicit queue bean makes the pool size, the bound and the
  rejection policy visible instead of hiding them behind an annotation default.

---

## Honest gaps

1. **`OpenAiEmbeddingClient` has never been executed.** Every green test embeds with
   `HashEmbeddingClient`, so the 429 backoff, the 401 path and the response-ordering logic are
   unexercised. "Embeddings work" is currently a statement about a hash function — and Phase 7's
   semantic search sits directly on top of it. Run it once with a real key before building
   search.
2. **The dimension guard is weaker than it looks** — see 6.4. A model/dimension mismatch is
   caught per document at run time, not at startup.
3. **`IngestionRecovery` has no test.** It needs a context restart mid-ingest.
4. **The poll gives up silently.** After ~2 minutes a genuinely stuck document keeps showing
   "Processing..." with no further refresh and no explanation. A "last checked" timestamp or a
   manual Refresh button would close that.
5. **`chunk_index` contiguity is an assumption, not an invariant** — see 5.4.
6. **Phase 3 (audit trail) is still owed.** `audit_log` does not exist and ingestion writes no
   audit rows. When it lands, the actor must come from `documents.uploaded_by`, because the
   worker thread has no `SecurityContext`.
