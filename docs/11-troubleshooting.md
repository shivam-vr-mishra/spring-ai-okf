# 11 — Troubleshooting

Every problem actually hit while building this project, with the diagnosis and
the fix. These are not hypotheticals.

---

## Build & startup

### `Plugin org.springframework.boot:spring-boot-maven-plugin:4.1.0 could not be resolved`

Running with `-o` (offline) before the plugin had ever been downloaded. Drop the
flag for the first build.

### Wrong Java version at build time

```
$ mvn -version
Apache Maven 3.6.3
Java version: 17.0.20.1
```

System Maven is 3.6.3 on Java 17 — too old for Spring Boot 4, and the wrong JDK.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw spring-boot:run        # wrapper fetches Maven 3.10.0
```

Always `./mvnw`, never `mvn`.

### `Web server failed to start. Port 8080 was already in use.`

Something else owned 8080. Rather than hunt it down:

```yaml
server:
  port: 8085
```

### `docker compose up` fails with exit code 1

The original `compose.yaml` pinned `ports: ['5432:5432']`, and this machine
already had a `rag-pgvector` container bound to 5432.

**Fix — publish the container port only:**

```yaml
ports:
  - '5432'        # Docker picks a free host port
```

Spring Boot reads the mapped port via the
`org.springframework.boot.service-connection=postgres` label, so nothing else
changes. To find it: `docker compose port pgvector 5432`.

### `relation "vector_store" does not exist`

`initialize-schema` defaults to **false**.

```yaml
spring.ai.vectorstore.pgvector.initialize-schema: true
```

### Dimension mismatch on first write

`dimensions` must equal the embedding model's output. `nomic-embed-text` is 768.
This fails loudly and immediately, which makes it the fastest sanity check
available.

---

## Retrieval quality

### Irrelevant concepts in the context

**Symptom.** `order-management.md` and `return-rate.md` appearing in answers about
return shipping.

**Diagnosis.** `similarity-threshold` was `0.3`. `nomic-embed-text` scores this
corpus in a narrow 0.55–0.72 band, so 0.3 admitted almost everything as an *entry
point* — and traversal then pulled in *their* neighbours too. Noise compounded.

```
/policies/refund-eligibility.md   0.664   relevant
/policies/return-shipping.md      0.648   relevant
/systems/order-management.md      0.571   noise, but qualified at 0.3
```

**Fix.** `similarity-threshold: 0.62`.

**How to calibrate yours.** `GET /api/search?q=…` applies **no** threshold and
returns raw scores. Run several real questions, see where meaningful results
stop, set the threshold just below. There is no universal value.

### A concept is never retrieved

Work through in order:

1. Is it indexed? `conceptsInBundle - conceptsIndexed` = the deprecated count.
2. Is it `status: deprecated`? Those are never indexed.
3. `GET /api/search?q=…` — what is its raw score? Below the threshold?
4. `GET /api/retrieve?q=…` — is it in `reachedByLink`?
5. `GET /api/bundle` — does anything link to it? An orphan is only reachable by
   direct similarity.
6. `GET /api/bundle/diagnostics` — is the link that should reach it broken?

### Edited a file but nothing changed

The graph does not watch the filesystem. `POST /api/bundle/reindex`.

---

## Generation quality

### The model refuses despite good context

**Symptom.** Retrieval returned five correct concepts, and the answer was:

> "You don't know. The query is not related to any of the concepts in the
> provided context."

**Diagnosis.** That wording comes from `ContextualQueryAugmenter`'s **default
prompt template**, which contains its own "if the answer is not in the context,
inform the user you can't answer" rule. The original system prompt said
essentially the same thing. Stacked, llama3.2:3b read the combination as
permission to refuse.

**Fix.** Two changes:

1. Shorten the system prompt to a positive task description.
2. Supply a custom template that states the task **once**:

```java
ContextualQueryAugmenter.builder()
        .promptTemplate(OKF_CONTEXT_TEMPLATE)   // positive framing
        .allowEmptyContext(false)
        .build()
```

The custom template must contain `{context}` and `{query}` — `PromptAssert`
throws at startup otherwise.

**Generalises to:** with a small model, prefer "do this" over "don't do that",
and never state the same constraint in two places.

### Answers ignore later documents

Ollama defaults to a **2048-token** context window. Five concepts overflow it and
the tail is silently dropped.

```yaml
spring.ai.ollama.chat.options.num-ctx: 8192
```

Silent truncation is the nastiest failure mode here — nothing errors, the answer
is just confidently incomplete.

### Model invents numbers

Observed, and it is the project's central demonstration. Plain RAG answered
*"approximately **3–5 business days**"* when the policy says **5**. It never
retrieved `/procedures/warranty-claim.md`, so it filled the gap.

Check `/api/retrieve`. If the concept holding the fact is absent from both
`foundBySearch` and `reachedByLink`, no prompt engineering will fix it — add the
link, or lower the threshold.

---

## Structured output

### `Unrecognized token 'A': was expecting (JSON String, Number, Array, Object...)`

```
(through reference chain: ConceptDraftList["concepts"]->ArrayList[0])
```

**Diagnosis.** The model returned `{"concepts": ["A gift return is..."]}` — an
array of **strings** where objects were required. `BeanOutputConverter` injects
the schema into the *prompt* and hopes; a 3B model does not reliably comply.

**Fix.** Constrain decoding at the token level with Ollama's `format`:

```java
this.converter = new BeanOutputConverter<>(ConceptDraftList.class);
this.schema = this.converter.getJsonSchemaMap();
...
builder.format(this.schema);
```

The model becomes structurally incapable of emitting invalid JSON.

### `method options cannot be applied to given types`

```
required: B
found:    OllamaChatOptions
reason: inference variable B has incompatible bounds
```

Two separate issues:

1. `.options()` in Spring AI 2.0 takes a **builder**, not a built options object.
2. `format()` is declared on the generic `AbstractBuilder<B>` and returns it
   **raw**, so a fluent chain loses the type parameter.

**Fix — build with statements:**

```java
OllamaChatOptions.Builder builder = OllamaChatOptions.builder();
builder.format(this.schema);
builder.temperature(0.0);
builder.numCtx(8192);
return builder;          // pass the BUILDER to .options()
```

### Extracted concepts have empty bodies

**Symptom.** Valid JSON, valid frontmatter, and a body that just restates the
title:

```markdown
# Gift Return Code

Gift Return Code
```

The 60-day window, the store-credit rule — all lost. **Worse than no draft**: it
gets embedded, scores well on its topic, contributes nothing.

**Fix — two parts:**

1. A quality guard in `ConceptDraft.isUsable()`: reject bodies under 80
   characters or equal to the title.
2. A blunter prompt — *"body — THE FULL TEXT OF THE RULE… must NEVER be just the
   title repeated"* — plus a worked example showing a body carrying a `$500`
   figure.

Subtle phrasing did not work. Shouting and a few-shot example did.

### Ingested concepts have no links

**Symptom.** Three new concepts, zero links to the existing bundle — even though
the source said *"double the standard 30 day window described in the refund
eligibility policy"*.

**Diagnosis.** The prompt restricted `relatedTitles` to *"concepts in YOUR OWN
list"*, added to stop the model inventing duplicates. That also made it
structurally impossible to reference anything already in the bundle.

**Fix.** Pass the existing titles into the prompt:

```java
List<String> existingTitles = graph.concepts().stream()
        .map(OkfConcept::title).filter(t -> t != null && !t.isBlank()).toList();
List<ConceptDraft> drafts = extractor.extract(chunks, existingTitles);
```

After the fix, all three concepts linked correctly into the hand-authored graph.

---

## Performance

This deserves its own section, because the obvious explanation was **wrong**.

### Symptom

A single `/api/chat` call took 2–4 minutes.

### Wrong first diagnosis

"CPU-only inference is slow." Partly true — there is no usable GPU (Intel Iris Xe
integrated; Ollama cannot use it) — but it is not the main cause.

### Measuring properly

Ollama's `/api/generate` returns timing breakdowns. **Cold**, small prompt:

```
prompt tokens : 32
prompt time   : 6.77s
output tokens : 3
output time   : 0.78s
total         : 280.18s     ← 7.5s of compute, 272s of something else
```

97% of the time was not computation. Running the same call twice more, **warm**:

```
run: load=0.35s  prefill=0.20s  gen=0.88s  total=1.46s
run: load=0.62s  prefill=0.33s  gen=0.91s  total=1.88s
```

So the model is *fast* once resident. The 280s was almost entirely **model
loading**.

### The real cause

A realistic RAG-sized prompt, warm:

```
prompt tokens : 1447
load          :   0.57s
prefill       : 377.69s     ← 3.8 tokens/sec
generation    :  18.85s     (44 tokens, 2.3 tok/s)
TOTAL         : 397.34s
```

**Prefill of 1447 tokens took 377 seconds.** Eight cores should manage 50–200
tok/s, not 3.8. That is not compute — that is memory thrashing.

```
$ free -m | grep -i swap
Swap:           2047        2046           1      ← 100% full
```

```
$ ps -eo rss,comm --sort=-rss | head
2975 MB  ollama
 747 MB  claude-desktop
 645 MB  java    (kafka)
 556 MB  java    (order-service)
 493 MB  java    (inventory-service)
 463 MB  java    (api-gateway)
 451 MB  java    (product-service)
 411 MB  java    (notification-service)
 372 MB  java    (discovery-server)
 263 MB  java    (config-server)
```

**A full microservices stack — roughly 4.5 GB — was running alongside.** 15 GB
total, 12 GB used, 2 GB free, swap exhausted. Ollama's KV cache allocation (sized
for `num-ctx`, regardless of actual prompt length) pushed it over, and prefill
ran out of swap.

### What was tried, and what actually happened

All four in-application tunings were applied and **verified to take effect**:

```
$ ollama ps
NAME          SIZE     PROCESSOR   CONTEXT   UNTIL
llama3.2:3b   2.5 GB   100% CPU    4096      29 minutes from now
```

`CONTEXT` dropped 8192 → 4096, resident size 3.0 GB → 2.5 GB, and `keep_alive`
held the model for 30 minutes instead of 5.

**Latency did not improve.** Measured end-to-end through `/api/chat`:

| Run | Config | Elapsed |
|---|---|---|
| Before | num-ctx 8192, 5 docs | ~200 s |
| After (cold) | num-ctx 4096, 4 docs | **491 s** |
| After (warm, model resident) | num-ctx 4096, 4 docs | **566 s** |

The warm run being *slower* than the cold one rules out model loading as the
cause. So the next hypothesis was model size — but `llama3.2:1b` on the same
workload:

```
llama3.2:1b  prompt=1230 tok  load=25.8s  prefill=243.1s  gen=122.6s  TOTAL=392.7s
```

**243 seconds to prefill 1230 tokens on a 1-billion-parameter model — 5.1
tokens/sec.** Eight cores should manage several hundred. That is roughly 50×
slower than the hardware is capable of.

### The honest conclusion

**No application-level tuning fixes this.** Reducing the KV cache by 500 MB does
not help when swap is already 100% full — the system is deep in thrash, and small
reductions do not escape it. Even a 1B model is memory-bound here.

| Fix | Will it work? |
|---|---|
| **Free several GB of RAM** — stop the microservices stack, Chrome, etc. | **Yes. This is the only one that addresses the cause.** |
| Add physical RAM, or enlarge swap | Yes |
| Lower `num-ctx` / `max-documents` | Marginal — reduces footprint, verified, but does not escape thrash |
| Smaller model (`llama3.2:1b`) | No — still 5.1 tok/s prefill |
| Raise `keep_alive` | No — warm runs were no faster |
| Stream responses | No change to total time, but first token appears far sooner. **Best UX fix available without freeing RAM.** |

The settings were left at the tuned values because they are *correct* — a smaller
footprint and a resident model are strictly better. They simply cannot overcome a
machine with 2 GB free and no swap headroom.

### The practical workaround

`/api/search` and `/api/retrieve` use **only the embedding model**, which is
small enough to stay resident. They return in **well under a second** even in this
state — and they happen to show the most interesting part of the architecture.
Demo with those; treat `/api/chat` as the slow confirmation step.

### Diagnosing it yourself

```bash
curl -s http://localhost:11434/api/generate \
  -d '{"model":"llama3.2:3b","prompt":"hi","stream":false}' | python3 -m json.tool
```

Read the breakdown:

| Field dominates | Meaning |
|---|---|
| `load_duration` | Model being re-read from disk — raise `keep_alive`, free RAM |
| `prompt_eval_duration` | Prefill — shorten the prompt, lower `num-ctx`, free RAM |
| `eval_duration` | Genuine generation — only a faster model or real hardware helps |

### The practical workaround

**`/api/search` and `/api/retrieve` return in under a second** — they use only the
embedding model, which is tiny. They also happen to show the most interesting
part of the architecture. For demos, lead with those; treat `/api/chat` as the
slow confirmation step.

---

## Quick reference

| Symptom | First thing to check |
|---|---|
| Won't build | `JAVA_HOME` is 21, using `./mvnw` |
| Port in use | `server.port`, and `ports: ['5432']` in compose |
| Table missing | `initialize-schema: true` |
| First write fails | `dimensions: 768` matches the model |
| Concept never found | `/api/search` raw score, then `/api/bundle` links |
| Edits not reflected | `POST /api/bundle/reindex` |
| Model refuses | Conflicting "don't answer" instructions |
| Truncated context | `num-ctx` — Ollama defaults to 2048 |
| Invalid JSON from model | `OllamaChatOptions.format(schema)` |
| Empty concept bodies | `ConceptDraft.isUsable()` + blunter prompt |
| Isolated new concepts | Pass `existingTitles` into the extractor |
| Very slow answers | `free -m`, then Ollama's `load`/`prefill`/`eval` split |

---

Previous: [10 — Web layer](10-web-layer.md) · Back to [index](README.md)
