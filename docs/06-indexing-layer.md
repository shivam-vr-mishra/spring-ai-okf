# 06 — Indexing layer

[`OkfIndexer`](../src/main/java/com/okf/rag/index/OkfIndexer.java) turns concepts
into embeddings in pgvector. One class, but several decisions worth understanding.

## The governing rule

**Only curated OKF concepts are ever embedded. Raw document chunks never are.**

In the ingestion pipeline, Tika's output and the `TokenTextSplitter` chunks exist
only long enough to be distilled into concepts, then are discarded. Nothing
reaches the vector store without first being given a `type`, a `status` and a
place in the link graph.

That constraint is what makes everything downstream possible:

| Because every row is a concept… | you get |
|---|---|
| it has an `okf_path` | the join key back to the filesystem graph |
| it has a `status` | `deprecated` content can be excluded |
| it has a `type` and `tags` | SQL-filterable routing |
| it has a trust tier | a ranking signal and a review workflow |
| it is a file in git | an editable, reviewable, citable artefact |

## Construction

```java
public OkfIndexer(OkfGraph graph, VectorStore vectorStore, JdbcTemplate jdbcTemplate,
        @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
```

- **`VectorStore`** — the Spring AI interface. `PgVectorStore` is injected by
  auto-configuration, but nothing here depends on it being Postgres.
- **`JdbcTemplate`** — needed only for the truncate (see below). Available because
  `spring-ai-starter-vector-store-pgvector` pulls in Spring JDBC.
- **`@Value` with a default** — reads the same property `PgVectorStore` uses, so
  the two can never disagree about the table name.

## Startup indexing

```java
@EventListener(ApplicationReadyEvent.class)
public void indexOnStartup() {
    try {
        reindex();
    }
    catch (Exception e) {
        log.error("Initial OKF indexing failed. Fix the cause and POST /api/admin/reindex. Reason: {}",
                e.toString());
    }
}
```

**`ApplicationReadyEvent`, not `@PostConstruct`.** Indexing calls Ollama for
embeddings and Postgres for writes. Both must be fully up, and the web server
must be listening so you can hit the reindex endpoint if something is wrong.
`@PostConstruct` runs too early in the lifecycle for that.

**The try/catch is deliberate.** If Ollama is down, the app still starts. The
bundle is still parsed, `/api/bundle` still works, and you get one clear error
line instead of a 200-line startup failure. The alternative — letting the
exception propagate and kill the context — makes diagnosis harder, not easier.

> The log message references `/api/admin/reindex`, but the actual endpoint is
> `POST /api/bundle/reindex`. A stale string; harmless but worth fixing.

## `reindex()`

```java
public IndexReport reindex() {
    graph.reload();

    jdbcTemplate.update("DELETE FROM " + tableName);

    TokenTextSplitter splitter = new TokenTextSplitter();
    int concepts = 0;
    int chunks = 0;

    for (OkfConcept concept : graph.concepts()) {
        if (concept.isDeprecated()) {
            log.info("Not indexing {} — status is deprecated", concept.path());
            continue;
        }
        List<Document> split = splitter.apply(List.of(concept.asDocument()));
        vectorStore.add(split);
        concepts++;
        chunks += split.size();
    }
    ...
}
```

### `graph.reload()` first

Re-reads the bundle from disk before indexing. Without this, a reindex after an
upload would embed the *old* in-memory graph and the new files would be invisible.
It also means the graph and the index are always built from the same snapshot.

### The truncate

```java
jdbcTemplate.update("DELETE FROM " + tableName);
```

A POC simplification, and an honest one. Rebuilding the whole table:

- guarantees concepts **deleted** from disk also disappear from the index
- avoids any need for upsert semantics or stale-row detection
- takes well under a second for a handful of concepts

The production alternative is to hash each concept's content, compare against
what is stored, and re-embed only what changed. That matters once embedding costs
real time or money — here, with 5 local concepts, it would be premature.

> **Note on the string concatenation.** `tableName` comes from application
> configuration, not user input, so this is not an injection vector. It is still
> worth flagging as the kind of line that becomes one if the source of the value
> ever changes. `JdbcTemplate` cannot parameterise identifiers, so a whitelist
> check would be the fix if that happened.

### Deprecated concepts are never indexed

```java
if (concept.isDeprecated()) {
    log.info("Not indexing {} — status is deprecated", concept.path());
    continue;
}
```

This is the strongest possible exclusion — a row that does not exist cannot be
retrieved by any query, filter bug, or threshold change.

In the seed bundle, `/policies/restocking-fee.md` states a withdrawn 15%
restocking fee. It is lexically very close to returns questions and scores highly,
and acting on it would give customers a wrong, more expensive answer. One
frontmatter field keeps it out of every answer while leaving it on disk for audit.

Logged at `INFO` rather than `DEBUG` — "why isn't this concept being found?" is a
question worth answering in default logs.

### Chunking

```java
TokenTextSplitter splitter = new TokenTextSplitter();
List<Document> split = splitter.apply(List.of(concept.asDocument()));
```

Defaults: 800 tokens per chunk, 350-character minimum, `CL100K_BASE` encoding.

For this bundle every concept is well under 800 tokens, so **each concept becomes
exactly one chunk** — the ideal case, since one chunk means one `okf_path` and no
score reconciliation.

Larger concepts split into several chunks, each inheriting the same metadata
(`TextSplitter` copies it). The retriever then collapses them back by `okf_path`,
keeping the best score — see [Retrieval layer](07-retrieval-layer.md).

The no-arg constructor is used rather than the builder because it has been stable
across Spring AI versions and the defaults suit this corpus. To tune:

```java
TokenTextSplitter.builder()
        .withChunkSize(1000)
        .withMinChunkSizeChars(400)
        .build();
```

### Where the embedding happens

```java
vectorStore.add(split);
```

This single line does more than it looks. `PgVectorStore.add()`:

1. calls the `EmbeddingModel` — here `OllamaEmbeddingModel` with
   `nomic-embed-text` — to turn each document's text into a 768-float vector
2. serialises the metadata map to JSONB
3. inserts rows into `vector_store`

The embedding model is never referenced directly anywhere in this project.
Auto-configuration wires it into the vector store, and the dimension agreement
between model (768) and config (`dimensions: 768`) is what makes it work. Get
that wrong and the first `add()` fails loudly — which is the fastest sanity check
available.

## The report

```java
public record IndexReport(int conceptsIndexed, int chunksWritten, int conceptsInBundle,
        int conceptsWithBrokenLinks) {
}
```

Returned from `POST /api/bundle/reindex` and embedded in the ingest response.
The four numbers answer different questions:

| Field | Tells you |
|---|---|
| `conceptsIndexed` | How many are retrievable |
| `chunksWritten` | Whether anything was split — if `chunks > concepts`, some concept exceeded 800 tokens |
| `conceptsInBundle` | Total parsed. **`conceptsInBundle - conceptsIndexed` = the deprecated count** |
| `conceptsWithBrokenLinks` | Graph health |

A healthy startup for the seed bundle:

```
Indexed 5 concepts into 5 chunks (6 concepts in bundle, 0 with broken links)
```

6 − 5 = 1, the deprecated restocking fee. `chunks == concepts`, so nothing split.

## Metadata design

What lands in each row's JSONB column, from `OkfConcept.metadata()`:

```json
{
  "okf_path":   "/policies/return-shipping.md",
  "okf_type":   "policy",
  "okf_title":  "Return Shipping Costs",
  "okf_tags":   "returns,shipping,costs,refunds",
  "okf_status": "stable",
  "okf_trust":  "human-reviewed"
}
```

Two decisions carried over from the parsing layer:

1. **`okf_tags` is a comma-joined String, not an array.** pgvector filters
   metadata in SQL, and scalars filter cleanly where arrays do not. The trade-off
   is that "has tag X" becomes a substring match rather than an array containment
   check.
2. **Every value is a scalar.** No nested objects, so every field is directly
   usable in a filter expression.

### Filters this enables

```java
// exclude withdrawn knowledge (used by OkfGraphRetriever)
.filterExpression("okf_status != 'deprecated'")

// route a "what's our policy on…" question
.filterExpression("okf_type == 'policy'")

// prefer reviewed knowledge
.filterExpression("okf_trust == 'human-reviewed'")

// combine
.filterExpression("okf_type == 'procedure' && okf_status == 'stable'")
```

Spring AI parses these into SQL against the JSONB column. This is structure that
plain chunked RAG simply does not have — a chunk has no type, no status and no
provenance to filter on.

## Verifying the index

```bash
docker compose port pgvector 5432          # find the mapped host port
```

```sql
SELECT metadata->>'okf_path'   AS path,
       metadata->>'okf_status' AS status,
       metadata->>'okf_trust'  AS trust
FROM vector_store;
```

Or without SQL:

```bash
curl -s 'http://localhost:8085/api/search?q=return%20shipping' | python3 -m json.tool
```

## Limitations

- **Full rebuild on every reindex.** No incremental diffing.
- **Not transactional.** The `DELETE` commits before the `add()` calls. If Ollama
  dies mid-reindex you are left with a partially populated table. Re-running
  `POST /api/bundle/reindex` fixes it, but a production version would stage into a
  temporary table and swap.
- **Single-threaded.** Each concept is embedded sequentially. Fine for a handful;
  batch the `add()` calls for a large bundle.
- **No concurrency guard.** Two simultaneous reindex requests would interleave a
  `DELETE` with another's inserts.

---

Previous: [05 — Graph layer](05-graph-layer.md) · Next: [07 — Retrieval layer](07-retrieval-layer.md)
