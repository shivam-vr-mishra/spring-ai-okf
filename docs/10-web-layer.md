# 10 — Web layer

Four controllers and one static page. Thin by design — all logic lives in the
services below.

| Controller | Paths | Purpose |
|---|---|---|
| [`ChatController`](../src/main/java/com/okf/rag/web/ChatController.java) | `/api/chat`, `/api/compare` | Ask the bot; run the comparison |
| [`SearchController`](../src/main/java/com/okf/rag/web/SearchController.java) | `/api/search`, `/api/retrieve` | Inspect retrieval without an LLM |
| [`BundleController`](../src/main/java/com/okf/rag/web/BundleController.java) | `/api/bundle`, `/diagnostics`, `/reindex` | Inspect and rebuild the graph |
| [`IngestController`](../src/main/java/com/okf/rag/web/IngestController.java) | `/api/ingest` | Upload a document |

---

## `SearchController` — the most useful endpoint

### `GET /api/search?q=…&topK=5`

Stage 1 only. No traversal, no LLM.

```java
List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
        .query(query)
        .topK(topK)
        .similarityThreshold(0.0)      // deliberately unfiltered
        .build());
```

**`similarityThreshold(0.0)` is the point of this endpoint.** It shows *every*
result with its raw score, including ones the configured 0.62 threshold would
reject. That is how you calibrate the threshold for your own corpus — run a few
real questions, see where meaningful results stop, set the threshold just below.

```json
[
  { "path": "/policies/refund-eligibility.md", "title": "Refund Eligibility",
    "type": "policy", "trust": "human-reviewed", "score": 0.664 },
  { "path": "/procedures/warranty-claim.md",   "title": "Warranty Claim Procedure",
    "type": "procedure", "trust": "human-reviewed", "score": 0.561 }
]
```

Returns in well under a second — only the embedding model is involved, not the
chat model. **For demos, use this and `/api/retrieve` rather than `/api/chat`.**

### `GET /api/retrieve?q=…`

Both stages, with the split made explicit.

```java
@GetMapping("/retrieve")
public RetrievalView retrieve(@RequestParam("q") String query) {
    OkfGraphRetriever.Trace trace = retriever.trace(query);
    return new RetrievalView(trace.question(), trace.entries(),
            trace.reachedByLink(), trace.citedPaths());
}
```

**Why a DTO rather than returning `Trace` directly:** `Trace` holds
`List<Document>`, and Spring AI's `Document` does not serialise cleanly to JSON —
it carries `Media`, a `ContentFormatter` and other internals that either blow up
or produce unusable output. `RetrievalView` projects only what the UI needs.

```json
{
  "foundBySearch":  [ {"path": "...", "score": 0.719}, ... ],
  "reachedByLink":  [ "/procedures/warranty-claim.md", "/systems/order-management.md" ],
  "contextSentToModel": [ ...all five paths... ]
}
```

`reachedByLink` **is the demo** — it is literally the list of concepts plain RAG
would have missed.

---

## `ChatController`

```java
@PostMapping("/chat")
public ChatService.Answer chat(@RequestBody ChatRequest request) {
    String conversationId = request.conversationId() == null || request.conversationId().isBlank()
            ? "default"
            : request.conversationId();
    return chatService.askOkf(conversationId, request.question());
}

public record ChatRequest(String question, String conversationId) { }
```

The `conversationId` default matters: `MessageChatMemoryAdvisor` requires one,
and passing null would fail inside the advisor rather than at the boundary.
Defaulting here keeps the API forgiving — omit the field and you get a shared
`"default"` thread.

```bash
curl -X POST http://localhost:8085/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"Do I pay return shipping for a damaged item?","conversationId":"demo"}'
```

```json
{
  "answer": "You don't pay... (/policies/return-shipping.md)",
  "citedConcepts": ["/policies/refund-eligibility.md", "/policies/return-shipping.md", ...]
}
```

### `POST /api/compare`

Same body, runs both strategies. **Two LLM calls**, so roughly double the wait.

---

## `BundleController`

### `GET /api/bundle`

Every concept with its parsed frontmatter and resolved links — the fastest way to
confirm the parser did what you expect.

```java
return graph.concepts().stream()
        .map(c -> new ConceptView(c.path(), c.type(), c.title(), c.status(),
                c.trust().tag(), c.generatedBy(), c.tags(), c.links()))
        .toList();
```

Again a DTO: `OkfConcept` carries the full `body`, which would make the response
enormous, and `TrustTier` serialises better as its `tag()` string.

```
/policies/refund-eligibility.md   stable      human-reviewed   links=[return-shipping, warranty-claim]
/policies/restocking-fee.md       deprecated  unverified       links=[refund-eligibility]
/policies/gift-return-time-frame  draft       unverified       links=[refund-eligibility]
```

That third row is an ingested concept — `draft`, `unverified`, and successfully
linked into the hand-authored graph.

### `GET /api/bundle/diagnostics`

```java
return Map.of(
        "conceptCount", graph.size(),
        "brokenLinks", graph.brokenLinks(),
        "staleConcepts", graph.staleConcepts(),
        "deprecatedConcepts", graph.concepts().stream()
                .filter(OkfConcept::isDeprecated)
                .map(OkfConcept::path)
                .toList());
```

Graph health. OKF §11 requires *consuming* a broken link permissively — it does
not require hiding it. A dangling link means a concept silently stopped being
reachable, which is exactly the bug that produces "the bot used to know this".

### `POST /api/bundle/reindex`

Re-reads the bundle from disk and rebuilds embeddings. Use it after editing
Markdown by hand — the graph does **not** watch the filesystem.

---

## `IngestController`

```java
@PostMapping("/ingest")
public IngestionService.IngestResult ingest(@RequestParam("file") MultipartFile file) {
    return ingestionService.ingest(file);
}
```

```bash
curl -X POST http://localhost:8085/api/ingest -F "file=@handbook.pdf"
```

```json
{
  "filename": "gift-returns.txt",
  "chunks": 1,
  "conceptsExtracted": 3,
  "conceptsWritten": ["/policies/gift-return-time-frame.md", ...],
  "index": { "conceptsIndexed": 8, "chunksWritten": 8,
             "conceptsInBundle": 9, "conceptsWithBrokenLinks": 0 }
}
```

Every stage count is reported, so a failure is attributable: `chunks > 0` but
`conceptsExtracted == 0` means extraction failed, not reading.

Limits come from `application.yml`:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 25MB
      max-request-size: 25MB
```

---

## The browser UI

`src/main/resources/static/index.html` — served automatically from the classpath
at `/`. No build step, no framework, no dependencies.

Three buttons mapping to three endpoints:

| Button | Endpoint | Speed |
|---|---|---|
| Show retrieval only | `GET /api/retrieve` | **sub-second** |
| Ask the chatbot | `POST /api/chat` | one LLM call |
| Compare plain RAG vs OKF | `POST /api/compare` | two LLM calls |

Concepts are colour-coded by provenance — blue for `found by search`, purple for
`reached by link` — which makes the architecture visible at a glance.

```js
const esc = s => String(s ?? '').replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
```

All interpolated values go through `esc`. Model output and concept paths are
inserted via `innerHTML`, so without escaping a concept body containing markup
would inject into the page.

> **Missing: there is no upload control in the UI.** `POST /api/ingest` works via
> curl, but the page does not expose it. Adding a file input that posts
> `FormData` to `/api/ingest` and then refreshes the concept list is a small,
> obvious improvement.

---

## Cross-cutting notes

**No global exception handler.** An unhandled exception returns Spring Boot's
default error JSON with a 500. For a POC that is acceptable — the stack trace in
the log is what you actually want. A `@RestControllerAdvice` mapping
`IllegalArgumentException` → 400 and `UncheckedIOException` → 503 would be the
first thing to add for anything real.

**No validation.** `question` could be null or empty; `topK` could be 10000.
Add `@Valid` with `@NotBlank`/`@Max` for a production version.

**No authentication.** Every endpoint is open, including `POST /api/ingest`,
which writes files to disk, and `POST /api/bundle/reindex`, which truncates a
table. Fine on localhost, not fine anywhere else.

**DTOs everywhere.** Every controller projects domain objects into records rather
than returning them directly. Two concrete reasons here, not dogma: `Document`
does not serialise, and `OkfConcept` would dump every concept body into every
response.

---

Previous: [09 — Ingestion layer](09-ingestion-layer.md) · Next: [11 — Troubleshooting](11-troubleshooting.md)
