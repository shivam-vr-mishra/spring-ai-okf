# 07 — Retrieval layer

[`OkfGraphRetriever`](../src/main/java/com/okf/rag/retrieval/OkfGraphRetriever.java)
is the heart of the project. Everything else exists to make this class possible.

## The idea in one sentence

**Vector search finds where to land; OKF links find everything else that matters.**

```
stage 1  LAND    vector similarity → entry concepts     probabilistic, fuzzy, ranked
stage 2  WALK    follow authored links → neighbours     deterministic, exact
```

Plain RAG stops after stage 1.

## The one-method interface

```java
@Component
public class OkfGraphRetriever implements DocumentRetriever {
```

`DocumentRetriever` is a Spring AI SPI with exactly one method:
`List<Document> retrieve(Query)`.

This is architecturally important. **All** OKF machinery plugs into Spring AI
through this single seam. `RetrievalAugmentationAdvisor` has no idea there is a
Markdown graph behind it — it just asks for documents.

Swap this class for Spring AI's `VectorStoreDocumentRetriever` and you have plain
RAG, with everything else unchanged. That is exactly what `plainRagChatClient`
does, and it is why `/api/compare` is a fair test: **the two paths differ in one
variable only.**

## Dependencies

```java
private final VectorStore vectorStore;   // probabilistic half — pgvector
private final OkfGraph graph;            // deterministic half — files in memory
private final OkfProperties properties;  // okf.retrieval.*
```

Two data sources, neither able to do the other's job.

## The SPI method is a shim

```java
@Override
public List<Document> retrieve(Query query) {
    return trace(query.text()).documents();
}
```

Spring AI wants documents. The UI wants to know *which concepts came from search
and which from links*. Rather than retrieve twice, the real work lives in
`trace()` which returns both, and `retrieve()` discards the extra.

`query.text()` unwraps Spring AI's `Query` record.

---

## Stage 1 — land

```java
List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
        .query(question)
        .topK(config.topK())                               // 3
        .similarityThreshold(config.similarityThreshold()) // 0.62
        .filterExpression("okf_status != 'deprecated'")
        .build());
```

Spring AI embeds `question` with `nomic-embed-text` and runs a cosine-distance
search in Postgres.

| Parameter | Value | Reasoning |
|---|---|---|
| `topK` | 3 | Low on purpose. Traversal expands from here, so a wide stage 1 compounds into noise |
| `similarityThreshold` | 0.62 | The most important dial — see below |
| `filterExpression` | `okf_status != 'deprecated'` | Defence in depth; deprecated concepts are already unindexed |

### Why the threshold matters so much

This was a real bug. At the original `0.3`, retrieval for *"My laptop arrived
damaged, do I have to pay to ship it back?"* produced:

```
/policies/refund-eligibility.md   0.664   ✓ relevant
/policies/return-shipping.md      0.648   ✓ relevant
/systems/order-management.md      0.571   ✗ noise — but qualified as an ENTRY
```

`order-management.md` then had *its* neighbours pulled in, dragging
`return-rate.md` into the prompt. For a 3B model, five documents of which two are
irrelevant was enough to degrade the answer badly.

`nomic-embed-text` scores this corpus in a narrow 0.55–0.72 band, so the gap
between "relevant" and "irrelevant" is only a few hundredths. **Calibrate against
your own corpus** using `/api/search`, which returns raw scores with no threshold
applied.

---

## Collapsing chunks back to concepts

```java
Map<String, Double> entryScores = new LinkedHashMap<>();
for (Document hit : hits) {
    Object path = hit.getMetadata().get(OkfConcept.MD_PATH);
    if (path == null) {
        continue;
    }
    double score = hit.getScore() == null ? 0d : hit.getScore();
    entryScores.merge(path.toString(), score, Math::max);
}
```

A long concept splits into several chunks, each its own row — so three hits could
be three chunks of one file. This folds them back.

- **`LinkedHashMap`, not `HashMap`** — preserves insertion order, which is
  *relevance order*. Everything downstream depends on best-first: the
  `maxDocuments` cap, the UI display, the order concepts appear in the prompt. A
  `HashMap` here would silently scramble it, and the bug would be subtle.
- **`merge(path, score, Math::max)`** — on a duplicate path, keep the higher
  score. A concept is as relevant as its best-matching chunk.
- **The null-path guard** — a row without `okf_path` isn't ours; skip rather than
  NPE.
- **The null-score guard** — `getScore()` returns `Double` and can be null
  depending on the store. Unboxing null would throw.

After this loop: distinct concept paths, best-first, with scores.

---

## Stage 2 — walk

```java
List<OkfConcept> expanded = graph.traverse(entryScores.keySet(), config.maxHops());
```

One line, and the whole point of the project. `keySet()` on a `LinkedHashMap` is
still rank-ordered.

The algorithm is documented in detail in [Graph layer](05-graph-layer.md). The
key property for this class: **`traverse` seeds its `visited` set with the entry
paths**, so it returns only *newly reached* concepts. Entries and
`reachedByLink` never overlap.

---

## Assembly

```java
List<Document> collected = new ArrayList<>();
List<Entry> entries = new ArrayList<>();
for (Map.Entry<String, Double> entry : entryScores.entrySet()) {
    graph.concept(entry.getKey()).ifPresent(concept -> {
        collected.add(concept.asDocument());
        entries.add(new Entry(concept.path(), concept.title(), entry.getValue()));
    });
}
```

**`concept.asDocument()` is a deliberate substitution.** The vector store returned
a *chunk*; this throws it away and emits the **whole concept file** read from
memory.

So the model never sees a half-sentence fragment — it gets a complete,
self-consistent unit with its heading, body and link list intact. Chunks are an
implementation detail of similarity search; concepts are the unit of knowledge.
Keeping that boundary clean is what makes citations meaningful.

**`ifPresent` hides a failure mode worth knowing.** If pgvector holds a path no
longer on disk — index/disk skew after editing the bundle without reindexing —
the entry is silently dropped from both lists. Correct behaviour (never cite a
deleted file), but invisible. `POST /api/bundle/reindex` is the cure.

```java
List<String> reachedByLink = new ArrayList<>();
for (OkfConcept concept : expanded) {
    collected.add(concept.asDocument());
    reachedByLink.add(concept.path());
}
```

Linked concepts appended **after** entries, so order is: highest-confidence
first, graph expansion second.

---

## The cap

```java
List<Document> documents = collected;
if (documents.size() > config.maxDocuments()) {
    log.debug("Capping retrieval at {} documents (had {})", config.maxDocuments(), documents.size());
    documents = documents.subList(0, config.maxDocuments());
}
```

A context-window guard — eight full concepts is already a large prompt for a 3B
model.

Three things to understand:

1. **It truncates from the end, so linked concepts drop before entries.**
   Defensible (entries are higher confidence) but it means traversal results can
   vanish silently. The `log.debug` line is the only signal. If you raise
   `max-hops`, raise `max-documents` too or the extra work is discarded.
2. **`subList` returns a view, not a copy**, backed by `collected`. It is safe
   *only because* the return statement wraps it in `List.copyOf`. Remove that and
   callers would hold a window onto a mutable list.
3. **`documents = collected` exists for the lambda.** `collected` is captured by
   the `ifPresent` lambda above and must stay effectively final — it cannot be
   reassigned. This line introduces a second reference that can be. (This was the
   single compile error in the entire build.)

---

## The return

```java
return new Trace(question, entries, reachedByLink, List.copyOf(documents));
```

`List.copyOf` makes it immutable and materialises the `subList` view.

```java
public record Entry(String path, String title, double score) { }

public record Trace(String question, List<Entry> entries, List<String> reachedByLink,
        List<Document> documents) {

    public List<String> citedPaths() {
        return documents.stream()
                .map(d -> String.valueOf(d.getMetadata().get(OkfConcept.MD_PATH)))
                .distinct()
                .toList();
    }
}
```

`entries` and `reachedByLink` are what make the demo legible. **`reachedByLink`
is literally the list of concepts plain RAG would have missed.**

`String.valueOf` rather than `.toString()` — tolerates a null metadata value by
yielding `"null"` instead of throwing.

> `Trace` holds raw `Document` objects, which do not serialise cleanly to JSON.
> `SearchController` projects it into a `RetrievalView` DTO before returning it.
> See [Web layer](10-web-layer.md).

---

## The whole shape

```
question
   │
   ├─► pgvector similaritySearch ──► hits (chunks, may repeat a concept)
   │                                   │
   │                      merge by okf_path, keep max score
   │                                   ▼
   │                            entryScores  {path → score}   ◄── rank-ordered
   │                                   │
   └─► graph.traverse(paths, maxHops) ─┤
                                       ▼
            entries (whole concepts) + reachedByLink (whole concepts)
                                       │
                            cap to maxDocuments
                                       ▼
                                     Trace
```

---

## Verified behaviour

`GET /api/retrieve?q=My laptop arrived damaged. Do I pay return shipping, and how
long does inspection take once you receive it?`

```json
{
  "foundBySearch": [
    { "path": "/policies/refund-eligibility.md", "score": 0.719 },
    { "path": "/policies/return-shipping.md",    "score": 0.677 },
    { "path": "/metrics/return-rate.md",         "score": 0.629 }
  ],
  "reachedByLink": [
    "/procedures/warranty-claim.md",
    "/systems/order-management.md"
  ]
}
```

`warranty-claim.md` — which holds the "5 business days" inspection answer — is
**not** in `foundBySearch`. It never is. It is written in operational vocabulary
(RMA, triage, disposition) that no customer-phrased question resembles. Only
link-following reaches it.

The resulting answers:

| | Inspection time |
|---|---|
| Plain RAG | "approximately **3–5 business days**" — **fabricated** |
| OKF graph | "within **5 business days** of receipt (`/procedures/warranty-claim.md`)" — correct |

Plain RAG did not merely omit the answer. It invented a confident, plausible,
wrong number. That is the failure mode this architecture prevents.

---

## Extensions worth considering

- **Re-rank after traversal.** Linked concepts currently keep discovery order. A
  cross-encoder or a cheap LLM relevance pass over the merged set would let you
  cap by relevance rather than by position.
- **Weight by hop distance.** A 2-hop concept is usually less relevant than a
  1-hop one; nothing currently reflects that.
- **Typed links.** `defines` versus `see also` would let traversal prefer
  definitional edges when a question contains an undefined term.
- **Trust-aware ranking.** `okf_trust` is already in metadata but unused in
  scoring. Preferring `human-reviewed` over `unverified` is a one-line filter.
- **Deduplicate by content.** If two concepts say nearly the same thing, both are
  sent. Ingestion can create such near-duplicates.

---

Previous: [06 — Indexing layer](06-indexing-layer.md) · Next: [08 — Chat layer](08-chat-layer.md)
