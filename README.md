# OKF + Spring AI — RAG with a knowledge graph

A proof of concept that combines **Google Cloud's Open Knowledge Format (OKF v0.2)**
with **Spring AI 2.0**, running entirely locally on Ollama and pgvector.

The point it demonstrates: **vector search finds where to land, OKF links find
everything else that matters.** Plain RAG stops after the first step.

> 📚 **Full documentation: [docs/](docs/README.md)** — every layer and every class
> explained line by line, plus a troubleshooting guide covering every problem hit
> while building this.

---

## What OKF is

OKF is an open, vendor-neutral specification from Google Cloud for packaging
knowledge as a directory of Markdown files with YAML frontmatter, cross-linked
into a graph. It is *not* a database — it is the curated layer that sits in front
of one.

```yaml
---
type: policy                 # the only required field
title: Return Shipping Costs
status: stable               # draft | stable | deprecated
generated: { by: "human:smishra", at: "2026-11-01T09:00:00Z" }
verified:
  - { by: "human:smishra", at: "2026-11-01T09:05:00Z" }
---
```

Spec: <https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md>

---

## Architecture

```
  upload (PDF/DOCX)
        │  TikaDocumentReader + TokenTextSplitter
        ▼
  ┌─────────────────┐
  │  CURATE         │  Ollama distils chunks into concept files
  │  → okf-bundle/  │  with frontmatter + authored links
  └────────┬────────┘
           │  nomic-embed-text
           ▼
  ┌─────────────────┐
  │  pgvector       │  each row carries okf_path — the join key
  └────────┬────────┘
           ▼
  ┌─────────────────────────────────────────────┐
  │  OkfGraphRetriever                          │
  │   stage 1  similarity search → entry concepts│  ← probabilistic
  │   stage 2  follow authored links, N hops     │  ← deterministic
  └────────┬────────────────────────────────────┘
           ▼
  RetrievalAugmentationAdvisor → llama3.2:3b → answer + cited paths
```

Raw chunks are **never** embedded. Only curated concepts reach the vector store,
so everything retrievable has a type, a status and a place in the link graph.

---

## Prerequisites

| Need | Version here |
|---|---|
| Java | 21 (`/usr/lib/jvm/java-21-openjdk-amd64`) |
| Maven | use `./mvnw` — it fetches 3.10.0. System Maven 3.6.3 runs on Java 17 and is too old |
| Docker | for pgvector |
| Ollama | native on the host, with `llama3.2:3b` and `nomic-embed-text` |

```bash
ollama pull llama3.2:3b
ollama pull nomic-embed-text
```

### Ports — read this

This machine already had things on the obvious ports, so the project deliberately
avoids them:

- **App runs on 8085**, not 8080 (something else owns 8080).
- **pgvector uses an ephemeral host port**, not 5432 — a pre-existing
  `rag-pgvector` container owns 5432 and is left alone. Spring Boot discovers the
  mapped port through the `org.springframework.boot.service-connection` label in
  `compose.yaml`, so no JDBC URL appears in config.

```bash
docker compose port pgvector 5432   # find the mapped port if you want psql
```

---

## Run

```bash
./start.sh
```

That's it. The script finds Java 21 itself (the system default here is 17, and
Maven inherits `JAVA_HOME`, so relying on `java` from `PATH` silently builds
against the wrong JDK), verifies Docker and Ollama, checks both models are
pulled, checks the port, warns if memory is tight, then runs the app.

| Command | Does |
|---|---|
| `./start.sh` | Preflight checks, then run |
| `./start.sh --check` | Checks only — useful for diagnosing a broken environment |
| `./start.sh --pull` | Pull any missing Ollama models first |
| `./start.sh --skip-checks` | Straight to run |
| `./stop.sh` | Stop the app and the pgvector container |
| `./stop.sh --purge` | Also delete the volume — embeddings rebuild from `okf-bundle/` next start |

Sample preflight output:

```
  ✓ Java 21  →  /usr/lib/jvm/java-1.21.0-openjdk-amd64
  ✓ Docker is running
  ✓ Ollama is running at http://localhost:11434
  ✓ model llama3.2:3b
  ✓ model nomic-embed-text
  ✓ Port 8085 is free
  ! Swap is 1928/2047MB — inference will thrash.
```

Or run it manually:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw spring-boot:run
```

Either way, Spring Boot starts the pgvector container itself, creates the schema,
reads `okf-bundle/`, and embeds every non-deprecated concept.

Then open <http://localhost:8085> — a three-tab UI (ask, upload, knowledge base).

Expected startup log:

```
Loaded 6 OKF concepts from .../okf-bundle
Not indexing /policies/restocking-fee.md — status is deprecated
Indexed 5 concepts into 5 chunks (6 concepts in bundle, 0 with broken links)
```

---

## Tests

```bash
./mvnw test        # 17 tests, ~1s, no Docker/Ollama needed
```

The tests cover the two pure layers — frontmatter parsing and graph traversal:
link resolution across all three OKF link forms, trust-tier derivation, the §11
permissive-consumption rules (malformed YAML, missing `type`, broken links,
reserved filenames), cycle handling, hop limits and deprecated-concept exclusion.

> The Spring Initializr `contextLoads` test was **removed deliberately**.
> `spring-boot-docker-compose` does not apply to `@SpringBootTest`, so the test
> context had no DataSource and failed with *"Failed to determine a suitable
> driver class"*. Testing the real logic without infrastructure is both faster and
> more useful; add Testcontainers if you later want a true integration test.

---

## Repository layout

```
start.sh           Preflight checks + run. stop.sh tears it down.
okf-bundle/        The OKF knowledge base — plain Markdown, versioned in git.
                   This is the point of the format: diffable, reviewable in a
                   pull request, editable by hand. 6 hand-authored concepts.
samples/           A sample document for trying ingestion.
docs/              Full documentation — one file per layer, code explained.
src/main/java/     The application.
src/main/resources/static/index.html   The UI. No build step.
compose.yaml       pgvector, started automatically by Spring Boot.
```

### Try ingestion

```bash
curl -X POST http://localhost:8085/api/ingest -F "file=@samples/gift-returns.txt"
```

Or use the **Upload a document** tab. The model distils the text into draft
concepts, writes them to `okf-bundle/` as Markdown with
`status: draft` / no `verified` key (so they land in the `unverified` trust
tier), resolves their `relatedTitles` into real links against the existing
bundle, and re-indexes.

A successful run links the new concepts **into** the hand-authored graph —
`gift-return-time-frame` → `refund-eligibility`, because the source text says
*"double the standard 30 day window described in the refund eligibility policy"*.

> Ingestion writes files into `okf-bundle/`. To get back to the seed state:
> `git checkout okf-bundle/ && git clean -fd okf-bundle/`, then
> `POST /api/bundle/reindex`.

---

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/search?q=` | Stage 1 only — raw vector similarity, no LLM |
| `GET` | `/api/retrieve?q=` | Both stages — shows what search found vs what links reached |
| `POST` | `/api/chat` | The chatbot. `{"question": "...", "conversationId": "..."}` |
| `POST` | `/api/compare` | Same question through plain RAG and OKF graph, side by side |
| `POST` | `/api/ingest` | Upload a document → draft concepts → re-index |
| `GET` | `/api/bundle` | Parsed concepts with frontmatter and resolved links |
| `GET` | `/api/bundle/diagnostics` | Broken links, stale and deprecated concepts |
| `POST` | `/api/bundle/reindex` | Re-read bundle from disk and rebuild embeddings |

### The demo

```bash
curl -s -G http://localhost:8085/api/retrieve \
  --data-urlencode "q=My laptop arrived damaged. Do I pay return shipping, and how long does inspection take?"
```

`foundBySearch` lists concepts the embedder matched. `reachedByLink` lists
`/procedures/warranty-claim.md` — which holds the inspection window and is
written in operational vocabulary (RMA, triage, disposition) that no
customer-phrased question will ever match. Only link-following reaches it.

---

## How the pieces map to code

| Concern | Class |
|---|---|
| Parse frontmatter + links, permissively (§11) | [`OkfBundleReader`](src/main/java/com/okf/rag/okf/OkfBundleReader.java) |
| In-memory graph, bounded BFS traversal | [`OkfGraph`](src/main/java/com/okf/rag/okf/OkfGraph.java) |
| Trust tiers from `verified` (§5.2, §7) | [`TrustTier`](src/main/java/com/okf/rag/okf/TrustTier.java) |
| Embed concepts, skip deprecated | [`OkfIndexer`](src/main/java/com/okf/rag/index/OkfIndexer.java) |
| **Two-stage retrieval** | [`OkfGraphRetriever`](src/main/java/com/okf/rag/retrieval/OkfGraphRetriever.java) |
| Chat clients, prompts | [`ChatConfig`](src/main/java/com/okf/rag/chat/ChatConfig.java) |
| Upload → concepts → disk | [`IngestionService`](src/main/java/com/okf/rag/ingest/IngestionService.java) |

---

## What the frontmatter does at runtime

- **`status: deprecated`** — `/policies/restocking-fee.md` states the *old* 15%
  restocking rule. It scores highly on returns questions and would give a wrong,
  more expensive answer. It is never indexed, purely because of that one field.
- **Trust tiers** — concepts the model wrote carry `generated: {by: "ollama/..."}`
  and no `verified` key, so they sit in `unverified`. A human reviewing the file
  in git and adding `verified: [{by: "human:..."}]` promotes it to
  `human-reviewed`. Visible on `/api/search` and `/api/bundle`.
- **`stale_after`** — surfaced by `/api/bundle/diagnostics`.

---

## Known limitations

- **Answers take 5–10 minutes on this machine, and that is a RAM problem, not a
  model problem.** Swap is 100% full (the host runs a microservices stack using
  ~4.5 GB), so Ollama's prefill thrashes: measured **5.1 tokens/sec prefill even
  on `llama3.2:1b`**, roughly 50× slower than the CPU is capable of. Lowering
  `num-ctx`, capping documents and raising `keep_alive` were all applied and
  verified — and **none of them helped**. Freeing several GB of RAM is the only
  fix. Full measurements in [docs/11-troubleshooting.md](docs/11-troubleshooting.md).
- **Retrieval endpoints are fast regardless.** `/api/search` and `/api/retrieve`
  use only the embedding model and return in well under a second. Demo with those.
- **llama3.2:3b is small**, and the whole project had to be tuned around that.
  Three concrete things it forced:
  - `ContextualQueryAugmenter` needed a custom prompt template. Its stock template
    carries a "say you can't answer" rule which, stacked on a system prompt saying
    the same, made the model refuse despite having good context.
  - Concept extraction needs Ollama's `format` option (grammar-constrained JSON).
    Prompt-only instructions produced `concepts: ["some text"]` — strings where
    objects were required.
  - Even with a valid schema the first attempt returned bodies that merely
    restated the title. `ConceptDraft.isUsable()` now rejects those outright.
- **Extraction output is a draft, not an answer.** Files land as `status: draft`
  with no `verified` key, so they sit in the `unverified` trust tier by design.
  Review them in git and add `verified: [{by: "human:you", at: ...}]` to promote
  them. Expect to edit the prose. A 7B/8B model would need less correction.
- **Ingestion does not deduplicate.** Uploading a document that restates an
  existing policy produces a second, near-duplicate concept rather than updating
  the original. Reconciling by content hash is the obvious next step.
- **Re-indexing is a full rebuild.** `OkfIndexer` truncates and rewrites the
  table. Fine for a handful of concepts; a real system would diff by content hash.
- **Ingestion caps at 12 chunks per upload** and logs what it dropped. Raise
  `MAX_CHUNKS` in `ConceptExtractor` if you need more.
- **Traversal is capped** at 1 hop / 8 documents (`okf.retrieval.*`). Raising
  `max-hops` on a densely linked bundle will drag in the whole graph.
