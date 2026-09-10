# Phase 8 — RAG chatbot

Status: **backend written, compiles against documented Gemini API shapes; frontend
type-checks clean (`ngc --noEmit`, strictTemplates). Nothing has been run.** Item 39
("a policy question is answered from your docs, not generic knowledge") is
**not verified** -- see [Honest gaps](#honest-gaps). This carries forward exactly the
same caveat `steps/phase7.md` left open for item 33, for the same reason: no real
embeddings key, no Docker, no JDK 21 were available where this was built.

## 1. Numbered items from the plan

| # | Item | State |
|---|---|---|
| 34 | `chat_conversation`, `chat_message` tables | Done — `V8__chat.sql` |
| 35 | `POST /api/chat` `{conversationId?, message}` | Done, plus an added optional `projectId` -- see §2 |
| 36 | embed → top-k → grounded prompt → LLM, refuse outside context | Done — `ChatService` + `GeminiChatClient` |
| 37 | persist user + assistant messages | Done |
| 38 | Angular chat panel, project-level and global | Done — `app-chat-panel`, embedded on project detail and at `/chat` |
| 39 | verify: policy question answered from docs, not generic knowledge | **Blocked** — needs a real `GEMINI_API_KEY` and a real run |

## 2. Shape of the feature

```
POST /api/chat  {conversationId?, message, projectId?}
        │
        ▼
ChatController        @PreAuthorize("isAuthenticated()")
        │
        ▼
ChatService.send()
        │  resolveConversation: existing (owned, else 404) or new (project
        │                       visibility checked like DocumentService)
        │  retrieve(): embed question -> SearchRepository.semantic(...) with the
        │              SAME AccessScope rule GET /api/search?mode=SEMANTIC uses,
        │              scoped to the conversation's project (or none)
        │  filter candidates below MIN_RELEVANCE (0.15 cosine) -- noise is not context
        │
        ├── no candidates ──▶ "I don't know." — skip the LLM call entirely
        │
        └── candidates ─────▶ buildContext(): full chunk text (not the 400-char
                               search preview), numbered [1] [2] ...
                               systemInstruction = grounding rules + context
                               history = last N turns, oldest-first
                               GeminiChatClient.complete(...)
                                    │
                                    ▼
                          persist USER + ASSISTANT ChatMessage rows
                          (sources = null if the answer was "I don't know.")
                                    │
                                    ▼
                          ChatResponse{conversationId, message}
```

`projectId` is an addition beyond the plan's literal `{conversationId?, message}`: item
38 asks for a project-level panel, and retrieval needs a scope on the *first* message
of a new conversation -- there is nothing else to scope it from. Once a conversation
exists, its stored `project` wins; a `projectId` sent on a later message to the same
conversation is ignored, not validated against it, so scope cannot silently drift
mid-thread.

## 3. `V8__chat.sql`

`chat_conversation` is a normal audited-ish row (id, user, optional project, title,
created/updated). `chat_message` is deliberately **not** built on `BaseEntity`: it is an
append-only log, same call the (still unbuilt) `audit_log` design made, so there is no
`updated_at` to maintain. `sources` is `JSONB`, mapped via Hibernate's
`@JdbcTypeCode(SqlTypes.JSON)` on a `List<SourceRef>` field rather than a hand-rolled
`String` + Jackson `ObjectMapper` pair -- untested against a real Postgres instance in
this environment, flagged below.

## 4. Retrieval: reused, not reinvented

`ChatService` calls `SearchRepository.semantic(...)` directly -- the exact method
`SearchService`'s `SEMANTIC` mode calls. That is deliberate: it means a USER cannot get
from the chatbot an answer built on a document they could not have found by searching,
without writing a second visibility rule to keep in sync with the first. A new
`SearchRepository.content(ids)` method was added alongside the existing `headlines(ids)`
to fetch the *full* chunk text for the prompt -- the search preview is capped at 400
characters, too short to ground an answer in.

`MIN_RELEVANCE = 0.15` exists so that on a real embedding model, a handful of
barely-related chunks above the raw top-k cutoff don't get stuffed into the prompt as if
they were relevant. Its threshold is a guess, not a tuned value -- there was no real
embedding model available to tune it against. **This is exactly the kind of number
that needs revisiting once item 33/39 can actually run**, and it interacts with both:
too high and a real question against real documents returns zero candidates and a false
"I don't know."; too low and irrelevant chunks reach the LLM anyway, where the system
prompt (not this filter) is what is actually supposed to catch them.

## 5. The "I don't know" contract, and why it has two layers

1. **Structural**: if retrieval returns nothing (embedding call failed, or nothing
   clears `MIN_RELEVANCE`), the code answers "I don't know." directly and never calls
   the LLM. Cheaper, and deterministic.
2. **Prompted**: if retrieval returns *something*, the system prompt instructs the model
   to answer only from the numbered passages and to say exactly "I don't know." if they
   don't contain the answer. This is the layer item 39 actually tests -- candidates can
   be non-empty but still not answer the question, and only the model (not this code)
   can tell the difference.

The response strips `sources` when the answer is exactly "I don't know." (case-insensitive,
exact match after trimming) -- a refusal should not carry citations that imply it looked
at something relevant and rejected it, versus not knowing.

## 6. `GeminiChatClient`

Same shape as `GeminiEmbeddingClient` (Phase 5-6's Gemini wiring): `RestClient`,
`x-goog-api-key` header, bounded exponential backoff on 429/5xx only. Built against
Google's documented `generateContent` REST shape (`contents[]` with `role`/`parts`,
`systemInstruction` with no `role` field, `generationConfig`) -- confirmed against
`https://ai.google.dev/api/generate-content` at write time, not against a live call.
History turns map `USER -> "user"`, `ASSISTANT -> "model"`; there is no third role for
grounding context, so that lives in `systemInstruction`, rebuilt fresh every turn from
that turn's retrieval (not accumulated across turns).

Model default is `gemini-3.8-flash`, `app.chat.gemini.model` overrides it.
`GEMINI_API_KEY` is shared with the embedding client (`app.ingest.gemini.api-key` and
`app.chat.gemini.api-key` both read the same env var) -- one key, two uses.

Unlike `EmbeddingClient`, there is **no offline fallback** for chat: there is no
meaningful "deterministic offline LLM". Without a key, the bean still exists (so the
rest of the app starts fine) but every call fails fast with a clear
`ChatCompletionException` before any network request.

## 7. Angular (item 38)

`app-chat-panel` (`frontend/src/app/chat/`) is one reusable standalone component:
`[projectId]` unset is the global panel at `/chat`; set (on the project detail page) it
scopes both the retrieval and every conversation started from that panel. A left rail
lists the caller's own conversations (`GET /api/chat/conversations`, filtered by
`projectId` when set); selecting one loads its history. Sending a message renders an
optimistic USER bubble immediately and rolls it back if the request fails, so a slow or
failed call never leaves a phantom "sent" message the server never saw. Assistant
messages show cited source documents (deduplicated by document, not one badge per
chunk) as plain badges -- there is no document detail route in this app yet, so they are
informational, not links.

## 8. Tests

`ChatControllerTest` (`backend/src/test/.../chat/`) covers what is actually verifiable
without a live LLM key:

1. anonymous -> 401 on both `POST /api/chat` and `GET /api/chat/conversations`
2. no matching documents -> the assistant refuses with exactly `"I don't know."`,
   `sources` empty, both turns persisted (`GET .../conversations/{id}` returns 2 rows
   in order)
3. passing an existing `conversationId` continues that thread (4 rows after a second turn)
4. a caller cannot open or post into someone else's conversation -- **404, not 403**,
   same "absent, not forbidden" rule search uses, so this endpoint is not an oracle for
   other users' conversation ids
5. a USER may start a conversation scoped to a project they own; may **not** scope one
   to a project they don't (403, `AccessDeniedException`)
6. a blank message is rejected (400)
7. `GET /api/chat/conversations` returns only the caller's own conversations

**Ran and passing:** none of it, in this environment -- see below. It compiles against
the same `TestcontainersConfiguration` / `MockMvcSecurityConfiguration` pattern
`SearchRbacTest` uses, but that needs Docker, which this environment does not have.

**Deliberately not tested here:** that the assistant produces a *correct, grounded*
answer. Every test in this suite runs on `HashEmbeddingClient` vectors (no real key
configured), so `MIN_RELEVANCE` filters every candidate to nothing and the "no context"
path is the only path these tests can reach -- `GeminiChatClient.complete()` is never
actually invoked by this suite. That is the same limitation `SearchTest` documents for
semantic search's relevance claim, one layer further up the stack.

### How it was verified

- Nothing. This environment (see `steps/phase7.md` for the same list) has no Docker, no
  JDK 21 (only 11), and no `GEMINI_API_KEY`. `npx ngc -p tsconfig.app.json --noEmit`
  passed with `strictTemplates: true`; the backend has not been compiled at all.

## Honest gaps

1. **The backend has not compiled.** Not "compiles but tests didn't run" like Phase 7 --
   actually compiled, in this environment, zero times. `mvnw -DskipTests compile` needs
   to happen before anything else here is trusted, including basic things like whether
   `@JdbcTypeCode(SqlTypes.JSON)` on a `List<SourceRef>` field is even the right
   annotation for this Hibernate version.
2. **Item 39 is blocked**, same shape as Phase 7's item 33: needs a real `GEMINI_API_KEY`,
   a document with an actual policy in it, reprocessing so its chunks carry real vectors
   (not `HashEmbeddingClient` noise), then a question whose answer is only in that
   document -- and a second question with no answer in any document, to confirm the
   refusal path holds under a real model rather than just the "zero candidates" shortcut.
3. **`MIN_RELEVANCE = 0.15` is an unvalidated guess.** It has never seen a real cosine
   score. Tune it once item 39 can run, not before -- there is nothing to tune it against
   yet.
4. **`GeminiChatClient`'s request/response shape is unverified against a live call.** It
   matches Google's documented REST reference at write time; a field name typo or a
   subtlety in how `systemInstruction` or empty `candidates` actually behaves would only
   surface on a real call.
5. **The frontend has never been rendered.** `ngc --noEmit` proves the types line up, not
   that the panel looks right, that the optimistic-message rollback behaves under a real
   network failure, or that `ng build`/`ng serve` even succeeds -- same Windows-only
   build limitation `steps/phase7.md` §Honest-gaps #4 already names for this environment.
6. **No document-level citation UI.** Source badges name the document and page/section
   but do not link anywhere -- there is no document detail route to link to yet.
7. **Phase 3 (audit trail) is still owed**, now five phases old. Chat writes rows to a
   new table of its own; when audit lands, decide deliberately whether a chat message is
   worth an audit row (probably not -- it is not a change to project/customer/task data)
   rather than bolting it on by default.
8. **No rate limiting or per-user cost controls on `/api/chat`.** Every message is a real
   LLM call once a key is configured; nothing here stops a user from sending one every
   second. Not in the plan's scope for this phase, but worth flagging before this goes
   anywhere near production.
