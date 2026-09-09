# Smart Management System

Enterprise project & knowledge platform: Angular + Spring Boot + PostgreSQL/pgvector,
with a RAG assistant that answers from **app data and documents** (not a generic chatbot).

**Today this repo has the management CRUD foundation, JWT auth/RBAC, and document
upload.** Audit trail and RAG are planned, not implemented. Full roadmap:
[`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).

## Stack

| Layer | Choice |
|---|---|
| UI | Angular 20 |
| API | Spring Boot 4.1.1, Java 21, single JAR |
| Auth | Spring Security, JWT (access + refresh), roles `ADMIN` / `MANAGER` / `USER` |
| Data | PostgreSQL 16 + pgvector |
| Migrations | Flyway |
| Ops | Docker Compose |

Spring Initializr currently offers Boot 4.x (3.x is out of OSS support), so this project
uses **Spring Boot 4.1.1** on Java 21.

## Repo layout

```
smart-managment-sytem/
  docker-compose.yml      # Postgres + pgvector
  backend/                # Spring Boot API
  frontend/               # Angular app
  observability/          # Prometheus / Grafana (later)
  eval/                   # RAG eval dataset + runner (later)
```

## Current status

| Milestone | Status |
|---|---|
| Scaffold (Compose, Flyway, Actuator, Angular, `/api/health`) | Done |
| Core CRUD (customers, projects, tasks + UI + Testcontainers) | Done |
| JWT + RBAC (`ADMIN` / `MANAGER` / `USER`) | Done |
| Audit trail | **Next** |
| Document upload (disk storage) | Done |
| Extract → chunk → embeddings → pgvector (async) | Done |
| Keyword + semantic search | Not started |
| RAG chatbot + citations | Not started |
| Database-aware chat (read-only tools) | Not started |
| Redis, WebSocket, observability, eval, Docker deploy | Later |

## Auth & RBAC

Spring Security + stateless JWT. `POST /api/auth/login` returns a short-lived access
token (15 min, HS256) and an opaque refresh token (7 days); `POST /api/auth/refresh`
rotates it (the old refresh token is revoked the moment a new one is issued), and
`POST /api/auth/logout` revokes it outright. `GET /api/me` returns the caller's profile.
Refresh tokens are stored hashed (HMAC-SHA256), never in plaintext.

Every write endpoint and every list/get endpoint enforces `@PreAuthorize` on top of the
stateless-session URL rule (`anyRequest().authenticated()`):

| Role | Customers | Projects | Tasks |
|---|---|---|---|
| ADMIN | full CRUD | full CRUD | full CRUD |
| MANAGER | full CRUD | full CRUD | full CRUD |
| USER | read only | read/list **own projects only** | read/list **assigned tasks only**; can update status/description/title/due date on a task assigned to them (cannot reassign, unassign, or move it) |

Seed accounts (BCrypt-hashed, Flyway `V4__seed_users.sql`) for local dev/demo:

| Email | Password | Role |
|---|---|---|
| `admin@local` | `admin123` | ADMIN |
| `manager@local` | `manager123` | MANAGER |
| `user@local` | `user123` | USER |

Change or remove these before any non-local deployment, and set a real `JWT_SECRET`
(the app refuses to start with a signing key under 32 bytes).

List endpoints also allowlist `?sort=` fields per resource, so a client cannot sort by
a related entity's column (e.g. `owner.passwordHash`) — invalid fields get a 400.

After that: Phase 3 audit trail, then extract/chunk/embed, then search/RAG. Do not start
the chatbot until auth and document search work. See the plan for the rest.

## Documents (upload + ingestion — Phases 4-6)

Files are stored on disk, not in the database — only their metadata is. Upload returns
`202` immediately and ingestion runs in the background: `UPLOADED` → `PROCESSING` →
`READY` (or `FAILED`).

- Allowed types: PDF, DOCX, TXT, MD (checked by file extension, not the client's
  `Content-Type`, since browsers send inconsistent values for `.md`).
- Max size: 25 MB (`spring.servlet.multipart.max-file-size`, kept in sync with
  `app.storage.max-file-size-bytes`).
- A document can optionally belong to a project (`projectId`); one with no project is
  a global upload.

Visibility follows the same ownership rule as projects: ADMIN/MANAGER see and upload
anything; a USER can only upload to, list, or download documents on a project they own,
and cannot create a project-less (global) document at all.

Uploaded files are written under `app.storage.documents-dir` (default
`backend/uploads/documents`, gitignored) with a generated UUID filename — the original
name is only ever used for the `Content-Disposition` header on download, never as a
path segment.

### Ingestion pipeline (Phase 5-6)

```text
POST /api/documents  ->  store bytes, INSERT documents(status=UPLOADED), COMMIT, 202
                              |
                    AFTER_COMMIT event -> bounded executor (2 threads)
                              |
   claim (UPDATE ... WHERE status IN ('UPLOADED','FAILED'))  -- one winner
      -> extract (Tika; PDF keeps one segment per page)
      -> clean  (collapse whitespace, drop NULs/soft hyphens)
      -> chunk  (600 cl100k_base tokens, 90 overlap, page label per chunk)
      -> embed  (batched, outside any transaction)
      -> INSERT document_chunks + status READY        (one transaction)
   failure at any step -> status FAILED + error_message, retry_count += 1
```

Locked choices, and what they cost:

- **No broker.** The queue is an in-memory `ThreadPoolTaskExecutor` with a bounded queue
  and `CallerRunsPolicy`, so a burst makes uploads slow rather than dropping them. A
  restart still loses whatever is queued — which is why the *document row* is the real
  queue: `IngestionRecovery` re-submits `UPLOADED` rows at startup and returns rows
  abandoned in `PROCESSING` (older than `app.ingest.stale-processing-after`) to the queue.
- **Enqueue after commit.** The listener is `@TransactionalEventListener(AFTER_COMMIT)`;
  submitting inside the transaction races the worker against the insert.
- **No transaction across the embedding call.** Every DB write is a short transaction in
  `IngestionStore`; the HTTP call happens with nothing open, so it cannot pin a pooled
  connection.
- **Dimension is fixed at 1536** (`text-embedding-3-small`) by `vector(1536)` in `V6`.
  A different model dimension is a new migration, not an `ALTER`; a mismatch between
  `app.ingest.dimensions` and the column fails at startup rather than per row.
- **Re-processing replaces.** `POST /api/documents/{id}/reprocess` (ADMIN/MANAGER, `409`
  while `PROCESSING`) deletes the document's chunks before inserting new ones. There is
  no automatic retry: an embeddings outage should surface as `FAILED`, not as a loop
  that burns quota.
- **No embeddings API key needed to run it.** With `app.ingest.openai.api-key` blank
  (the default), a deterministic offline `HashEmbeddingClient` produces unit vectors, so
  the pipeline and its tests run with no network. Those vectors carry no meaning —
  semantic search needs a real key (`OPENAI_API_KEY`).
- **No ANN index yet.** The HNSW index arrives with the Phase 7 search query; indexing an
  empty table only slows inserts.
- **No OCR.** A scanned PDF yields no text and ends `FAILED` with that message.

Chunks are internal: nothing returns their text yet. `GET /api/documents/{id}` exposes
`status`, `chunkCount`, `embeddingModel`, `errorMessage` and `processedAt`.

### Ingestion status in the UI

Project detail shows each document's ingestion state (`Queued` / `Processing…` / `Ready` /
`Failed`), the chunk count once it is `READY`, and the failure message when it is not.

Because ingestion is asynchronous, the page **polls the project's document list every 3s**
while any document is still `UPLOADED` or `PROCESSING`, and stops as soon as all of them
settle. One request per tick covers the whole list. The poll is capped at 40 ticks (~2 min)
so a document wedged in `PROCESSING` by a dead worker cannot poll forever in a background
tab — reload the page in that case. ADMIN/MANAGER also get a **Reprocess** button on
settled documents; a `409` (ingestion already running) is treated as success, not an error.

Deleting a document deletes its chunks (`ON DELETE CASCADE`).

## Run it

### 1. Database

```bash
docker compose up -d db
```

Postgres listens on **host port 5433** (5432 is often taken by a native install).
Credentials: `smartmgmt` / `smartmgmt`, database `smartmgmt`.

### 2. Backend

```bash
cd backend
./mvnw spring-boot:run
```

- API: http://localhost:8080
- Liveness: `GET /api/health`
- Actuator: `GET /actuator/health`

Override DB with `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` if needed, and set `JWT_SECRET`
to a real 32+ byte value outside local dev (there's a dev-only default otherwise).
Uploaded documents are written under `backend/uploads/documents` by default (gitignored);
override the location with `DOCUMENTS_DIR`.

### 3. Frontend

```bash
cd frontend
npm install
npm start
```

- App: http://localhost:4200
- `/api/*` is proxied to the backend (`proxy.conf.json`)
- You'll land on `/login` — use one of the seed accounts above. Home shows a live
  backend health indicator; the nav hides actions a role can't use (e.g. a USER never
  sees "New project").

## Tests

```bash
cd backend && ./mvnw test     # Testcontainers (Docker required)
cd frontend && npm test
```

`AuthControllerTest` covers login/refresh-rotation/logout/`/api/me`; `RbacTest` and
`ExpiredTokenTest` cover the milestone's "done when" criteria directly (USER blocked
from `POST /api/projects`, MANAGER allowed, expired token rejected, bad sort field
rejected). `DocumentControllerTest` covers upload → get → download → list-by-project
and rejects an unsupported file type; `DocumentRbacTest` covers USER upload restricted
to an owned project (and blocked for a project-less upload) plus the anonymous-401 case,
and that reprocessing is ADMIN/MANAGER-only. `ChunkerTest` pins the window arithmetic
(contiguous indices, real overlap, page labels, full coverage); `DocumentIngestionTest`
runs the pipeline end to end against Postgres — READY with 1536-dim vectors, page labels
from a generated two-page PDF, a corrupt file ending FAILED with a message, reprocess
replacing rather than appending chunks, and cascade delete. It is deliberately *not*
`@Transactional`: ingestion fires after commit, so a rolled-back test would never
trigger it.

## API (current)

| Resource | Endpoints | Filters |
|---|---|---|
| Auth | `POST /api/auth/login`, `refresh`, `logout`, `GET /api/me` | — |
| Customers | `GET/POST /api/customers`, `GET/PUT/DELETE /api/customers/{id}` | `q` |
| Projects | `GET/POST /api/projects`, `GET/PUT/DELETE /api/projects/{id}` | `q`, `status`, `customerId`, `ownerId`, `overdue` |
| Tasks | `GET/POST /api/tasks`, `GET/PUT/DELETE /api/tasks/{id}` | `q`, `status`, `projectId`, `assigneeId` |
| Documents | `POST /api/documents` (multipart, `file` + optional `projectId`), `GET /api/documents/{id}`, `GET /api/documents/{id}/download`, `POST /api/documents/{id}/reprocess` (ADMIN/MANAGER), `GET /api/projects/{id}/documents` | — |

All endpoints except `/api/auth/**` and `/api/health` require a `Bearer` access token.
List endpoints take `page`, `size` (max 100), `sort` (allowlisted per resource). Bodies
use DTOs; lists return a `PageResponse` envelope. Validation errors are RFC 9457
`ProblemDetail` (400 + `errors`); unknown ids return 404; RBAC/ownership denials return
403; missing/invalid/expired tokens return 401.

README sections for the audit trail, RAG + citations, SQL-tool safety, and eval will be
added when those phases land.

## Known gaps

- The **audit trail (Phase 3) was skipped** — the plan's order was audit → documents →
  ingestion, and ingestion was built first. `audit_log` does not exist yet.
- Ingestion writes no audit rows. When Phase 3 lands, note that the worker thread has no
  `SecurityContext` (it is not the request thread, and the uploader's token may have
  expired), so the actor must come from `documents.uploaded_by`, not
  `SecurityUtils.currentUser()`.
- A `USER` cannot trigger reprocessing even on their own document, by design.
- `OpenAiEmbeddingClient` has never been run against the real API. Every green test embeds
  with the offline `HashEmbeddingClient`, so the 429 backoff, the 401 path and the
  `dimensions` request parameter are unexercised.
