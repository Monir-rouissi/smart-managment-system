# AI-Powered Management & Knowledge Platform — Implementation Plan

Position this as an **enterprise application + AI/RAG** project, not a basic CRUD app with a chatbot bolted on.

The chatbot must use application data: overdue projects, customers not contacted, document summaries, policy lookup, account changes.

---

## Constraints

- **One Spring Boot application** with modules (Management, Documents, Search, Chat/RAG). Not three deployable microservices.
- **Do not add:** Kafka, RabbitMQ, MongoDB, Elasticsearch, GraphQL, gRPC, WebFlux, Kubernetes, multiple LLM providers.
- Complementary to an event-driven Project 1. This project focuses on security, PostgreSQL/pgvector, RAG, and enterprise architecture.

---

## Locked stack

| Layer | Choice |
|---|---|
| UI | Angular |
| API | Spring Boot 3, Java 21, one JAR |
| Auth | Spring Security, JWT, roles `ADMIN` / `MANAGER` / `USER` |
| Data | PostgreSQL 16 + pgvector |
| Files | Local disk first (`uploads/`), object storage later |
| Jobs | Spring `@Async` + status table (not Kafka) |
| Cache | Redis (after RAG works) |
| Realtime | Spring WebSocket / STOMP (after Redis) |
| LLM | One provider (OpenAI or Ollama) behind an interface |
| Ops | Docker Compose: Postgres, Redis, Prometheus, Grafana |

---

## Domain

**AI-Powered Project & Knowledge Platform**

Entities: users, customers, projects, tasks, documents, activity/audit.

Project status: `PLANNING` | `IN_PROGRESS` | `ON_HOLD` | `COMPLETED` | `CANCELLED`

---

## Repo layout

```text
smart-managment-sytem/
  docker-compose.yml
  backend/                 # Spring Boot
    src/main/java/.../
      auth/
      management/          # users, customers, projects, tasks
      audit/
      documents/           # upload, extract, chunk, embed
      search/              # keyword + vector
      chat/                # RAG + intent router + citations
      notify/
      config/
  frontend/                # Angular
  observability/           # prometheus.yml, grafana dashboards
  eval/                    # RAG eval dataset + runner
```

---

## Architecture (target)

```text
                         Angular
                            │
                            ▼
                  ┌───────────────────┐
                  │ Spring Boot API   │
                  │ Security / JWT    │
                  │ RBAC / REST / WS  │
                  └─────────┬─────────┘
                            │
          ┌─────────────────┼─────────────────┐
          │                 │                 │
          ▼                 ▼                 ▼
   Management         Chat/RAG          Document
   Module             Module             Module
          │                 │                 │
          │          ┌──────┴──────┐          │
          │          ▼             ▼          ▼
          │      PostgreSQL     pgvector   File Storage
          │          └──────┬──────┘
          │                 ▼
          │                LLM
          ▼
      Audit Logs

                     Redis · Prometheus · Grafana · OpenTelemetry
```

Chat routing (later phases):

```text
                 User Question
                      │
              ┌───────▼────────┐
              │ Intent / Router│
              └───────┬────────┘
                      │
          ┌───────────┼───────────┐
          ▼           ▼           ▼
       SQL data     Vector      General
       (tools)      search
          │           │
          └──────┬────┘
                 ▼
                LLM → Answer + sources
```

---

## RBAC

| Role | Can |
|---|---|
| ADMIN | Manage users, permissions, system configuration |
| MANAGER | Manage projects, view reports, manage team |
| USER | Own tasks and assigned projects |

Use method-level authorization (`@PreAuthorize`), not URL rules only.

---

## Implementation rules

1. CRUD before chat. No LLM until documents and search work.
2. One LLM interface (`ChatModel`, `EmbeddingModel`) so swapping OpenAI/Ollama is config.
3. Tools, not raw SQL, for the first database-aware version.
4. Read-only DB role if generated SQL is ever executed.
5. Citations are mandatory on RAG answers.
6. `202 Accepted` + background job for every embedding pipeline.
7. README must cover architecture, RBAC, RAG flow, SQL safety, and how to run eval.

---

## Suggested calendar (solo)

| Weeks | Focus |
|---|---|
| 1 | Scaffold + CRUD |
| 2 | Auth + audit |
| 3 | Documents + async ingest + embeddings |
| 4 | Search + RAG + citations |
| 5 | DB-aware chat (tools) |
| 6 | Redis + WebSocket |
| 7 | Observability + eval + Docker |

If time is short, **stop after the database-aware chatbot (tools)**. Redis, WebSocket, Grafana, and eval are extras.

---

# First milestone (start here)

Do this before any of the numbered steps below.

1. Docker Compose + PostgreSQL with pgvector, volume, healthcheck.
2. Spring Boot app: Flyway, JPA, Actuator (`health`, `info` only at first).
3. Angular app: routing, `apiUrl` env, interceptor stub.
4. `GET /api/health` reachable from Angular.
5. Flyway tables: `users`, `customers`, `projects`, `tasks`.
6. CRUD APIs with pagination, sorting, filtering, Bean Validation, DTOs (not entities):
   - `/api/customers`
   - `/api/projects`
   - `/api/tasks`
7. Angular list + detail + create/edit for customers, projects, tasks.
8. JWT login/refresh/logout + `GET /api/me`.
9. Roles `ADMIN` / `MANAGER` / `USER` with `@PreAuthorize`.
10. Seed users: `admin@local`, `manager@local`, `user@local`.
11. Angular login, auth interceptor, route guards; hide actions the role cannot use.

**Done when:** a manager can create a customer, a project, and tasks; a USER cannot `POST /api/projects`; expired JWT is rejected.

---

# All steps after the first milestone

Work in order. Do not skip ahead to the chatbot.

## Phase 3 — Audit trail

1. Create Flyway table `audit_log` (`id`, `user_id`, `action`, `entity_type`, `entity_id`, `old_value`, `new_value`, `timestamp`).
2. Add an `AuditService` that writes one row per change (JSON for old/new).
3. Hook it on create/update/delete for customers, projects, tasks (same transaction as the write).
4. Expose `GET /api/audit?entityType=&entityId=` with pagination; restrict to MANAGER/ADMIN.
5. Add an **Activity** timeline on project detail and customer detail in Angular.
6. Verify: change a project status and see `PLANNING → IN_PROGRESS` with user + time.

## Phase 4 — Document upload (no AI yet)

7. Create `documents` table (`id`, `name`, `mime_type`, `storage_path`, `uploaded_by`, `project_id`, `status`, `created_at`). Status: `UPLOADED` | `PROCESSING` | `READY` | `FAILED`.
8. Add local file storage (`uploads/`) and a `DocumentStorage` service.
9. `POST /api/documents` (multipart) → save file, insert row, return **202** + document id.
10. `GET /api/documents/{id}`, `GET /api/projects/{id}/documents`, download endpoint.
11. Angular: upload on project page, file list, download.
12. Verify: PDF/DOCX/TXT/MD upload and download work; status stays `UPLOADED`.

## Phase 5 — Text extraction (still no embeddings)

13. Add Apache Tika (or equivalent) for PDF/DOCX; plain/markdown readers for TXT/MD.
14. `DocumentExtractionService`: file → cleaned text.
15. Store extracted text (file or `documents.extracted_text`) for debugging.
16. On failure, set status `FAILED` + `error_message`.
17. Verify: a sample PDF produces readable text.

## Phase 6 — Chunking + embeddings + pgvector

18. Enable `CREATE EXTENSION vector` in Flyway.
19. Create `document_chunks` (`id`, `document_id`, `chunk_index`, `content`, `locator`, `embedding vector(dim)`, `embedding_model`).
20. Implement chunker (~500–800 tokens, 10–15% overlap); keep page/section in `locator`.
21. Add `EmbeddingClient` interface + one implementation (OpenAI or Ollama).
22. After extract: chunk → embed → insert chunks → set document `READY`.
23. Make this **async**: upload still returns 202; a worker sets `PROCESSING` then `READY`/`FAILED`.
24. Angular: show processing vs ready vs failed on the document.
25. Verify: upload PDF → wait → chunks exist in DB with non-null embeddings.

Pipeline:

```text
Upload → 202 Accepted → extract → clean → split chunks → embed → pgvector
```

## Phase 7 — Keyword + semantic search

26. Add Postgres FTS (`tsvector` on chunk content + document name) and a GIN index.
27. Add an HNSW (or IVFFlat) index on `document_chunks.embedding`.
28. `GET /api/search?q=&mode=KEYWORD|SEMANTIC|HYBRID` (pagination).
29. Keyword path: FTS ranked snippets.
30. Semantic path: embed query → `ORDER BY embedding <=> $1 LIMIT k`.
31. Hybrid: merge (e.g. Reciprocal Rank Fusion).
32. Angular search page: query, mode toggle, snippet, document name, link to file.
33. Verify: “customer cancellation” finds a doc that says “subscription termination.”

```text
                    SEARCH
                       │
            ┌──────────┴──────────┐
            ▼                     ▼
       Keyword Search       Semantic Search
         PostgreSQL              pgvector
```

## Phase 8 — RAG chatbot

34. Tables: `chat_conversation`, `chat_message` (role, content, sources JSON).
35. `POST /api/chat` `{ conversationId?, message }`.
36. Flow: embed question → top-k chunks → build prompt (answer **only** from chunks; else “I don’t know”) → LLM.
37. Persist user + assistant messages.
38. Angular chat panel (project-level and/or global).
39. Verify: a policy question is answered from your docs, not generic model knowledge.

```text
User question → Chat API → embed → pgvector → chunks → prompt → LLM → answer
```

## Phase 9 — Citations

40. Return `sources[]`: `documentId`, `name`, `locator` (page/section), `chunkId`.
41. Prompt the model to only cite retrieved chunk ids.
42. Angular: source chips; click opens document (and locator if available).
43. Verify: “refund policy” cites `Refund Policy.pdf` / the right page.

Example response:

```json
{
  "answer": "Customers can request a refund within 30 days...",
  "sources": [
    {
      "documentId": "...",
      "name": "Refund Policy.pdf",
      "locator": "Page 3",
      "chunkId": "..."
    }
  ]
}
```

## Phase 10 — Database-aware chatbot (tools first, not free SQL)

44. Add an **intent router**: `SQL_DATA` | `VECTOR_RAG` | `HYBRID` | `GENERAL`.
45. Implement **read-only tools** (no generated SQL yet), for example:
    - count overdue projects
    - project by name (owner, status, due)
    - tasks for a project
    - customers not updated since date
46. Router: data questions → tools; policy/docs → RAG; “compare policy vs project X” → both, then LLM.
47. `GENERAL` = short help about the product, no DB dump.
48. Enforce RBAC inside tools (USER only sees assigned projects).
49. Verify: “Who owns Project Alpha?” hits Postgres; delay policy still hits RAG.

## Phase 11 — Optional safe SQL generation (only after tools work)

50. Create a **read-only** Postgres user; app SQL-gen path uses only that user.
51. LLM may emit a single `SELECT`; reject anything else (`INSERT`/`UPDATE`/`DELETE`, `;`, DDL).
52. Allowlist tables/columns (`projects`, `tasks`, `customers`, …).
53. Timeout + max rows (e.g. 200).
54. Pass result rows back to the LLM for a human answer.
55. Document this security model in the README.
56. Verify: “How many projects completed last month?” works; `DROP TABLE` cannot run.

```text
Natural language → LLM → SQL → validation → READ ONLY → PostgreSQL → LLM → answer
```

## Phase 12 — Redis

57. Add Redis to Docker Compose; Spring Data Redis / cache.
58. Cache `GET /api/dashboard` (TTL 30–60s); invalidate on project/task writes.
59. Optional: cache recent query embeddings.
60. Rate-limit `POST /api/chat` per user.
61. Verify: second dashboard request is a cache hit (log or metric).

## Phase 13 — WebSocket notifications

62. Spring WebSocket/STOMP + JWT handshake.
63. Notification events: task assigned, document `READY`/`FAILED`, project overdue.
64. Scheduler: mark/notify overdue projects daily (or hourly).
65. Push to `/user/queue/notifications`.
66. Angular: STOMP client, bell, toast.
67. Verify: uploader sees “Document processed” without refresh; assignee sees new task.

## Phase 14 — Observability

68. Micrometer + Prometheus registry; scrape Actuator.
69. Grafana in Compose + one dashboard.
70. Custom timers/counters: HTTP, DB, `embedding.latency`, `vector.search.latency`, `llm.latency`, `rag.chunks_retrieved`, token usage if available.
71. Log correlation id on chat + document jobs.
72. Verify: Grafana shows API p95 and RAG/LLM latency while you chat.

## Phase 15 — RAG evaluation

73. Add `eval/dataset.jsonl`: question, expected source file, optional gold answer (10–30 items).
74. Script: run retrieval, score **hit-rate** (expected source in top-k).
75. Optional: answer quality (contains gold phrase / LLM-as-judge).
76. Change **one** knob (chunk size, top-k, or prompt); record before/after.
77. Put numbers + how to run eval in the README.
78. Verify: two documented scores (e.g. retrieval 72% → 89%).

## Phase 16 — Docker + deploy

79. Multi-stage Dockerfile for backend; Nginx Dockerfile for Angular.
80. Compose for demo: app, postgres+pgvector, redis, prometheus, grafana.
81. Env vars: DB, Redis, JWT secret, LLM key, upload path. No secrets in git.
82. Seed users + a few sample projects/docs for a live demo.
83. Deploy to one host (VM or PaaS). Health check URL.
84. Verify: clean machine `docker compose up` → login → CRUD → upload → search → chat with citations.

## README (write as you go, finish at the end)

85. Architecture diagram (modules, not microservices).
86. RBAC matrix.
87. RAG + citation flow.
88. Tool/SQL safety (read-only user, allowlist).
89. How to run, seed, eval, and open Grafana.

---

## Stop points

| Must ship for the portfolio story | Nice if time remains |
|---|---|
| First milestone + steps 1–43 (audit → RAG + citations) | Steps 50–56 (free SQL) |
| Steps 44–49 (DB tools + router) | Steps 57–72 (Redis, WebSocket, Grafana) |
| | Steps 73–84 (eval + cloud) |

---

## Next action

After JWT and RBAC work: **step 1** — `audit_log` table + write-on-change + Activity UI.
