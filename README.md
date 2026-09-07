# Smart Management System

Enterprise project & knowledge platform: Angular + Spring Boot + PostgreSQL/pgvector,
with a RAG assistant that answers from **app data and documents** (not a generic chatbot).

**Today this repo has the management CRUD foundation plus JWT auth and RBAC.** Audit,
documents, and RAG are planned, not implemented. Full roadmap: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).

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
| Document upload → extract → chunk → embeddings | Not started |
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

After that: Phase 3 audit trail, then documents, then search/RAG. Do not start the chatbot
until auth and document search work. See the plan for the rest.

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
rejected).

## API (current)

| Resource | Endpoints | Filters |
|---|---|---|
| Auth | `POST /api/auth/login`, `refresh`, `logout`, `GET /api/me` | — |
| Customers | `GET/POST /api/customers`, `GET/PUT/DELETE /api/customers/{id}` | `q` |
| Projects | `GET/POST /api/projects`, `GET/PUT/DELETE /api/projects/{id}` | `q`, `status`, `customerId`, `ownerId`, `overdue` |
| Tasks | `GET/POST /api/tasks`, `GET/PUT/DELETE /api/tasks/{id}` | `q`, `status`, `projectId`, `assigneeId` |

All endpoints except `/api/auth/**` and `/api/health` require a `Bearer` access token.
List endpoints take `page`, `size` (max 100), `sort` (allowlisted per resource). Bodies
use DTOs; lists return a `PageResponse` envelope. Validation errors are RFC 9457
`ProblemDetail` (400 + `errors`); unknown ids return 404; RBAC/ownership denials return
403; missing/invalid/expired tokens return 401.

README sections for the audit trail, RAG + citations, SQL-tool safety, and eval will be
added when those phases land.
