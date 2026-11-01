# OKF + Spring AI — Documentation

A complete walkthrough of this project, layer by layer, with the actual code
explained line by line.

## What this project is

A Retrieval-Augmented Generation (RAG) system that combines **two retrieval
strategies** instead of one:

1. **Vector search** (pgvector + embeddings) — finds concepts that *look like*
   the question. Fuzzy, ranked, good at getting into the right neighbourhood.
2. **Graph traversal** (OKF Markdown links) — follows connections a human
   *wrote down*. Exact, deterministic, finds things no embedding would match.

Plain RAG only does step 1. This project demonstrates, with a measurable result,
why step 2 matters.

## Read in this order

| # | Document | What it covers |
|---|---|---|
| 01 | [Architecture](01-architecture.md) | The layers, how a request flows, why it's split this way |
| 02 | [The OKF format](02-okf-format.md) | What OKF v0.2 actually is, every frontmatter field, our bundle |
| 03 | [Build & configuration](03-build-and-config.md) | `pom.xml`, `compose.yaml`, every property in `application.yml` |
| 04 | [Parsing layer](04-parsing-layer.md) | `OkfConcept`, `TrustTier`, `OkfBundleReader`, `OkfProperties` |
| 05 | [Graph layer](05-graph-layer.md) | `OkfGraph` and the traversal algorithm |
| 06 | [Indexing layer](06-indexing-layer.md) | `OkfIndexer`, chunking, embeddings, metadata design |
| 07 | [Retrieval layer](07-retrieval-layer.md) | `OkfGraphRetriever` — the heart of the project |
| 08 | [Chat layer](08-chat-layer.md) | `ChatConfig`, advisors, prompts, `ChatService` |
| 09 | [Ingestion layer](09-ingestion-layer.md) | Tika, `ConceptExtractor`, `OkfBundleWriter` |
| 10 | [Web layer](10-web-layer.md) | Controllers, DTOs, the browser UI |
| 11 | [Troubleshooting](11-troubleshooting.md) | Every real problem hit while building this, and the fix |

## The one-paragraph summary

A document is uploaded. Tika reads it, `TokenTextSplitter` chunks it, and a local
Ollama model distils those chunks into **concept files** — one topic per Markdown
file, with YAML frontmatter and explicit links to related concepts. Only those
curated files are embedded into pgvector, each row tagged with its `okf_path`.
When a question arrives, vector search finds entry concepts, then
`OkfGraphRetriever` follows the authored links outward to pull in whatever the
author said was related. The result goes to the LLM with citations.

## The proof

The same question through both strategies:

| | "how long does inspection take?" |
|---|---|
| Plain RAG | "approximately **3–5 business days**" — fabricated |
| OKF graph | "within **5 business days** of receipt (`/procedures/warranty-claim.md`)" — correct |

Ground truth is 5 days. The procedure file ranks 4th on similarity — below the
`topK` cut — because it is written in operational vocabulary (RMA, triage,
disposition) that no customer-phrased question resembles. Plain RAG never saw it
and invented a confident wrong number. Traversal reached it deterministically.

## Stack

| Component | Choice | Why |
|---|---|---|
| Framework | Spring Boot 4.1 + Spring AI 2.0.1 | Spring AI 2.x requires Boot 4 / Framework 7 |
| LLM | Ollama `llama3.2:3b` | Local, no API key, already on the machine |
| Embeddings | Ollama `nomic-embed-text` | 768 dimensions, local |
| Vector store | pgvector (Postgres 16) in Docker | HNSW index, cosine distance |
| Knowledge format | OKF v0.2 | Google Cloud open spec, plain Markdown in git |
