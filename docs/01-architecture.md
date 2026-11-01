# 01 — Architecture

## The core idea

Most RAG tutorials look like this:

```
PDF → chunks → embeddings → similarity search → LLM
```

That works, but chunking **destroys the relationships between ideas**. If page 4
says *"the customer pays return shipping unless it is a defect return"* and page
11 defines what a defect return is, those two pages become unrelated vectors.
Ask a question that needs both and the model gets half the picture — and will
often invent the other half.

This project inserts a curation step and a traversal step:

```
PDF → chunks → CONCEPTS (OKF) → embeddings → search → TRAVERSE LINKS → LLM
                   ▲                                        ▲
             relationships made               relationships followed
             explicit and durable               deterministically
```

## The layers

```
┌──────────────────────────────────────────────────────────────────┐
│  WEB            ChatController · SearchController                │
│                 BundleController · IngestController · index.html │
├──────────────────────────────────────────────────────────────────┤
│  CHAT           ChatConfig (2 ChatClients) · ChatService          │
│                 RetrievalAugmentationAdvisor                      │
├──────────────────────────────────────────────────────────────────┤
│  RETRIEVAL      OkfGraphRetriever   ← implements DocumentRetriever│
│                 stage 1: vector search    stage 2: link traversal │
├──────────────────────────────────────────────────────────────────┤
│  INDEXING       OkfIndexer · TokenTextSplitter · VectorStore      │
├──────────────────────────────────────────────────────────────────┤
│  GRAPH          OkfGraph  (in-memory, bounded BFS)                │
├──────────────────────────────────────────────────────────────────┤
│  PARSING        OkfBundleReader · OkfConcept · TrustTier          │
├──────────────────────────────────────────────────────────────────┤
│  INGESTION      IngestionService · ConceptExtractor               │
│                 OkfBundleWriter · TikaDocumentReader              │
└──────────────────────────────────────────────────────────────────┘
          │                                    │
    ┌─────▼─────┐                        ┌─────▼──────┐
    │ okf-bundle│  Markdown, in git      │  pgvector  │  embeddings
    │  on disk  │  human-editable        │  Postgres  │  + metadata
    └───────────┘                        └────────────┘
```

## Two stores, one join key

This is the single most important structural fact in the project.

| Store | Holds | Answers |
|---|---|---|
| **pgvector** | 768-dim vectors + metadata | *"What looks like this question?"* |
| **okf-bundle/** | Markdown files + links | *"What is connected to this concept?"* |

They are joined by one metadata field: **`okf_path`**.

Every row written to pgvector carries the bundle-relative path of the concept it
came from (`/policies/return-shipping.md`). So the moment vector search returns a
hit, the system can leave the database entirely and walk the filesystem graph.

```
 similarity search  ──returns──►  hit.metadata["okf_path"]
                                          │
                                          ▼
                              graph.concept(path).links()
                                          │
                                          ▼
                                   neighbour concepts
```

Without that field, the two halves could not talk to each other.

## Request flow — asking a question

`POST /api/chat`

```
1. ChatController                   receives {question, conversationId}
2. ChatService.askOkf()             calls okfChatClient
3. MessageChatMemoryAdvisor         prepends prior turns for this conversationId
4. RetrievalAugmentationAdvisor     calls the DocumentRetriever…
5.   └─ OkfGraphRetriever.retrieve()
6.        stage 1: vectorStore.similaritySearch()  → entry concepts by okf_path
7.        stage 2: graph.traverse(paths, maxHops)  → linked concepts
8.        assemble: whole concept files, capped at maxDocuments
9. ContextualQueryAugmenter         renders OKF_CONTEXT_TEMPLATE with the docs
10. OllamaChatModel                 generates the answer
11. ChatService.citedPaths()        reads documents back out of the response
                                    context → okf_path list
12. ChatController                  returns {answer, citedConcepts}
```

Step 11 is worth noting: citations come from
`RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT` on the response, **not** from a
second retrieval pass. So what is cited is exactly what the model was shown.

## Request flow — uploading a document

`POST /api/ingest`

```
1. IngestController                 receives the MultipartFile
2. TikaDocumentReader               PDF/DOCX/PPTX/TXT → raw Document(s)
3. TokenTextSplitter                → chunks (800 tokens, 350 min chars)
4. OkfGraph.concepts()              collect existing titles
5. ConceptExtractor.extract()       per chunk, Ollama + JSON schema → ConceptDraft
                                    (existing titles passed in so new concepts
                                     can link INTO the existing graph)
6. ConceptDraft.isUsable()          reject empty / title-only bodies
7. OkfBundleWriter.write()          render frontmatter + body + ## Related Concepts
                                    resolve relatedTitles → bundle-relative paths
8. OkfIndexer.reindex()             reload bundle from disk, re-embed everything
9. IngestController                 returns {chunks, conceptsExtracted, written, index}
```

Note step 4/5: without feeding the existing titles into the prompt, newly
ingested concepts become isolated islands with no links back into the graph.
That was an actual bug during development — see
[Troubleshooting](11-troubleshooting.md).

## Why raw chunks are never embedded

`OkfIndexer` only ever writes **concept files** to the vector store. The raw
chunks from Tika exist only long enough to be turned into concepts, then are
discarded.

This is a deliberate constraint, and it buys three things:

1. **Everything retrievable has a type and a status.** You can filter retrieval by
   `okf_type == 'policy'` or exclude `okf_status == 'deprecated'`.
2. **Everything retrievable has a position in the graph.** A chunk has no
   neighbours; a concept does.
3. **Everything retrievable is human-editable.** When the bot gets something
   wrong, you fix a Markdown file and open a pull request — no re-ingestion, no
   retraining.

## Package layout

```
com.okf.rag
├── OkfRagApplication.java        @SpringBootApplication + @ConfigurationPropertiesScan
├── okf/                          the OKF format itself
│   ├── OkfConcept.java           one concept (record)
│   ├── TrustTier.java            unverified | machine-confirmed | human-reviewed
│   ├── OkfBundleReader.java      disk → OkfConcept, permissively
│   ├── OkfGraph.java             in-memory graph + traversal
│   └── OkfProperties.java        okf.* config binding
├── index/
│   └── OkfIndexer.java           concepts → pgvector
├── retrieval/
│   └── OkfGraphRetriever.java    the two-stage retriever
├── chat/
│   ├── ChatConfig.java           two ChatClients + prompts
│   └── ChatService.java          orchestration + citations
├── ingest/
│   ├── ConceptDraft.java         model output before it hits disk
│   ├── ConceptExtractor.java     chunks → drafts (Ollama, JSON schema)
│   ├── OkfBundleWriter.java      drafts → Markdown files
│   └── IngestionService.java     the upload pipeline
└── web/
    ├── ChatController.java       /api/chat, /api/compare
    ├── SearchController.java     /api/search, /api/retrieve
    ├── BundleController.java     /api/bundle, /diagnostics, /reindex
    └── IngestController.java     /api/ingest
```

## The one seam that makes the comparison honest

`OkfGraphRetriever` implements Spring AI's `DocumentRetriever`, which has exactly
one method. That means **all** the OKF machinery plugs into Spring AI through a
single interface.

Swap `OkfGraphRetriever` for Spring AI's own `VectorStoreDocumentRetriever` and
you have plain RAG — everything else unchanged. That is precisely what
`plainRagChatClient` does in [ChatConfig](../src/main/java/com/okf/rag/chat/ChatConfig.java),
which is why `/api/compare` is a fair test: the two paths differ in **one
variable only**.

---

Next: [02 — The OKF format](02-okf-format.md)
