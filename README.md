# Smart Management System

AI-Powered Management & Knowledge Platform — an enterprise Spring Boot application with
RBAC, document ingestion, vector search, and a retrieval-augmented (RAG) chatbot that
answers from application data.

See [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) for the full roadmap.

## Stack

| Layer | Choice |
|---|---|
| UI | Angular 20 |
| API | Spring Boot 4, Java 21, single JAR |
| Data | PostgreSQL 16 + pgvector |
| Migrations | Flyway |
| Ops | Docker Compose |

> The plan targets "Spring Boot 3"; it was written before Boot 4 GA. Spring Initializr
> now only offers Boot 4.x and all 3.x lines are out of OSS support, so this project
> uses **Spring Boot 4.1.1** on Java 21.

## Repo layout

```
smart-managment-sytem/
  docker-compose.yml      # Postgres + pgvector
  backend/                # Spring Boot API
  frontend/               # Angular app
  observability/          # prometheus / grafana config (later phases)
  eval/                   # RAG eval dataset + runner (later phases)
```

## Run it

### 1. Database

```bash
docker compose up -d db
```

Postgres listens on **host port 5433** (5432 is commonly taken by a native install).
Credentials: `smartmgmt` / `smartmgmt`, database `smartmgmt`.

### 2. Backend

```bash
cd backend
./mvnw spring-boot:run
```

- API: http://localhost:8080
- Liveness: `GET /api/health`
- Actuator: `GET /actuator/health`

Override DB connection with env vars if needed: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.

### 3. Frontend

```bash
cd frontend
npm install
npm start
```

- App: http://localhost:4200
- `/api/*` is proxied to the backend (`proxy.conf.json`)
- The home page shows a live backend health indicator.

## Tests

```bash
cd backend && ./mvnw test     # uses Testcontainers (Docker required)
cd frontend && npm test
```

## Current status

First milestone — scaffold: Docker Compose + Postgres/pgvector, Spring Boot app with
Flyway and Actuator, Angular app with routing + auth interceptor stub, and a
`GET /api/health` endpoint reachable from Angular. Next: Flyway domain tables and CRUD
for customers / projects / tasks.
