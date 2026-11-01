# 03 — Build & configuration

## Version constraints you cannot ignore

**Spring AI 2.0.x requires Spring Boot 4.0/4.1 and Spring Framework 7. It will
not load in a Spring Boot 3.x context.** It also moves to Jackson 3 — you will
see `tools.jackson.*` packages in stack traces, not `com.fasterxml.jackson.*`.

| | Version | Note |
|---|---|---|
| Java | 21 | Spring AI 2.0 needs 17+, 21 recommended |
| Spring Boot | 4.1.0 | |
| Spring AI | 2.0.1 | pinned by `${spring-ai.version}` |
| Maven | 3.10.0 | **via `./mvnw`** |

### The Maven wrapper is not optional here

The system Maven on this machine is 3.6.3 running on **Java 17**:

```
$ mvn -version
Apache Maven 3.6.3
Java version: 17.0.20.1 ...
```

That is too old for Spring Boot 4's plugin, and it would compile against the
wrong JDK. Always use the wrapper with `JAVA_HOME` pointed at 21:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw spring-boot:run
```

The wrapper downloads Maven 3.10.0 on first run — see
`.mvn/wrapper/maven-wrapper.properties`.

---

## `pom.xml`

### Dependency management

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>${spring-ai.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

The BOM pins every `org.springframework.ai` artifact to one consistent version,
so none of the dependencies below carry an explicit `<version>`.

### What each dependency is for

| Artifact | Purpose |
|---|---|
| `spring-boot-starter-webmvc` | REST controllers. Note the Boot 4 name — not `spring-boot-starter-web` |
| `spring-ai-starter-model-ollama` | `OllamaChatModel`, `OllamaEmbeddingModel`, auto-config, `OllamaChatOptions` |
| `spring-ai-starter-vector-store-pgvector` | `PgVectorStore` + schema initialisation. Pulls in JDBC, which is where `JdbcTemplate` comes from |
| `spring-ai-vector-store-advisor` | `QuestionAnswerAdvisor` — the plain-RAG control group |
| `spring-ai-rag` | `RetrievalAugmentationAdvisor`, **`DocumentRetriever`** (the SPI we implement), `ContextualQueryAugmenter`, query transformers |
| `spring-ai-tika-document-reader` | `TikaDocumentReader` — PDF, DOCX, PPTX, HTML, plain text |
| `org.yaml:snakeyaml` | Parses OKF frontmatter. Version managed by Boot |
| `spring-boot-docker-compose` | Starts `compose.yaml` on boot, wires the DataSource |

`spring-ai-rag` is the one people miss. Without it there is no
`DocumentRetriever` interface and the whole retrieval layer has nowhere to plug in.

---

## `compose.yaml`

```yaml
services:
  pgvector:
    image: 'pgvector/pgvector:pg16'
    environment:
      - 'POSTGRES_DB=okf'
      - 'POSTGRES_USER=okf'
      - 'POSTGRES_PASSWORD=okf'
    labels:
      - "org.springframework.boot.service-connection=postgres"
    ports:
      - '5432'
    healthcheck:
      test: ['CMD-SHELL', 'pg_isready -U okf -d okf']
      interval: 5s
      timeout: 5s
      retries: 10
```

### The service-connection label

```yaml
labels:
  - "org.springframework.boot.service-connection=postgres"
```

This is what lets `application.yml` contain **no JDBC URL, username or
password**. Spring Boot inspects the running container, reads the mapped port and
the `POSTGRES_*` environment variables, and constructs the `DataSource` itself.

### Why the port is `'5432'` and not `'5432:5432'`

`- '5432'` publishes the container port to a **random free host port**. A fixed
`5432:5432` mapping would collide with any other Postgres already running — which
is exactly what happened on this machine (a pre-existing `rag-pgvector`
container owns 5432). The app does not care, because the service-connection label
makes Boot discover whatever port Docker chose.

To find it:

```bash
docker compose port pgvector 5432     # e.g. 0.0.0.0:32768
```

### No Ollama service

`start.spring.io` generates an `ollama` container alongside pgvector. It has been
removed deliberately: Ollama runs **natively on the host** here with the models
already pulled. A second containerised copy would fight for port 11434 and
re-download several GB into a volume.

---

## `application.yml` — every setting explained

### Server

```yaml
server:
  port: 8085
```

Not 8080 — this machine already has something listening there. Nothing about the
app requires 8085; it is simply a free port.

### Docker Compose lifecycle

```yaml
spring:
  docker-compose:
    lifecycle-management: start_and_stop
```

`start_and_stop` means Boot starts the container on application start and stops
it on shutdown. Use `start_only` if you are restarting frequently and want the
database to persist between runs.

### Ollama

```yaml
    ollama:
      base-url: http://localhost:11434
      init:
        pull-model-strategy: when_missing
        timeout: 10m
      chat:
        model: llama3.2:3b
        options:
          temperature: 0.1
          num-ctx: 8192
      embedding:
        model: nomic-embed-text
```

| Property | Why this value |
|---|---|
| `init.pull-model-strategy: when_missing` | Pull a model only if absent. Alternatives: `always` (re-pulls every boot), `never` (default) |
| `chat.model` | `llama3.2:3b` — small, already present. Quality limits are discussed in [Troubleshooting](11-troubleshooting.md) |
| `options.temperature: 0.1` | Near-deterministic. The model should quote the policy, not improvise |
| `options.num-ctx: 8192` | **Critical.** Ollama defaults to a **2048-token** context window. With five full concepts in the prompt, the default silently truncates the context — the model simply never sees the later documents and you get mysterious wrong answers. See the note below. |
| `embedding.model` | `nomic-embed-text` — 768 dimensions |

> **`num-ctx` has a cost.** The KV cache is allocated for the full context size
> regardless of actual prompt length. On a RAM-constrained machine, a large
> `num-ctx` causes swapping and makes prefill dramatically slower. 8192 is
> generous here; 4096 is usually plenty for 4–5 concepts and much lighter.
> Measured impact is in [Troubleshooting](11-troubleshooting.md).

### pgvector

```yaml
    vectorstore:
      pgvector:
        initialize-schema: true
        dimensions: 768
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        table-name: vector_store
```

| Property | Meaning |
|---|---|
| `initialize-schema: true` | Creates the `vector`, `hstore` and `uuid-ossp` extensions and the table. **Defaults to `false`** — leave it off and you get a "relation does not exist" error |
| `dimensions: 768` | **Must match the embedding model.** `nomic-embed-text` emits 768. A mismatch fails loudly on the first write, which is the fastest sanity check available |
| `index-type: HNSW` | Default. Good recall/speed. Alternatives: `IVFFlat`, `NONE` |
| `distance-type: COSINE_DISTANCE` | Default. Scores arrive as similarity in `[0,1]`, higher is better |
| `table-name` | Referenced by `OkfIndexer` for the truncate-and-rebuild |

### OKF settings

```yaml
okf:
  bundle-path: ./okf-bundle
  retrieval:
    top-k: 3
    similarity-threshold: 0.62
    max-hops: 1
    max-documents: 8
```

Bound to [`OkfProperties`](../src/main/java/com/okf/rag/okf/OkfProperties.java)
via `@ConfigurationProperties("okf")`, enabled by `@ConfigurationPropertiesScan`
on the main class.

| Property | Effect | Tuning advice |
|---|---|---|
| `top-k` | Max entry concepts from vector search | Keep low (2–4). Traversal expands from here, so a wide stage 1 compounds into noise |
| `similarity-threshold` | Minimum score to qualify as an entry | **The most important dial.** See below |
| `max-hops` | Link-following depth | 1 is right for most bundles. 2 on a densely linked bundle will pull in nearly everything |
| `max-documents` | Hard cap after traversal | Guards the context window. Truncates from the end, so linked concepts drop before entries |

#### Why `similarity-threshold` is 0.62

`nomic-embed-text` scores this corpus in a narrow band — roughly 0.55 to 0.72.
At the original value of 0.3, near-irrelevant concepts qualified as entry points,
and traversal then pulled in *their* neighbours too. Observed scores for the
question *"My laptop arrived damaged, do I have to pay to ship it back?"*:

```
/policies/refund-eligibility.md   0.664
/policies/return-shipping.md      0.648
/systems/order-management.md      0.571   ← noise at threshold 0.3
/procedures/warranty-claim.md     0.561   ← the actual answer, ranked 4th
/metrics/return-rate.md           0.559
```

**Calibrate this against your own corpus.** Hit `/api/search?q=...` with a few
real questions, look at where the meaningful results stop, and set the threshold
just below that. There is no universal value — it depends on the embedding model
and how similar your documents are to each other.

### Logging

```yaml
logging:
  level:
    com.okf.rag: DEBUG
```

Turns on the retrieval trace line, which is the single most useful log in the
project:

```
Query 'My laptop arrived damaged...' -> 3 entry concept(s)
[/policies/refund-eligibility.md, /policies/return-shipping.md, /metrics/return-rate.md],
2 reached by link [/procedures/warranty-claim.md, /systems/order-management.md]
```

---

## Running it

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw spring-boot:run
```

Boot will: start the pgvector container → create the schema → read `okf-bundle/`
→ embed every non-deprecated concept → serve on 8085.

Expected startup log:

```
Loaded 6 OKF concepts from /home/.../okf-bundle
Not indexing /policies/restocking-fee.md — status is deprecated
Indexed 5 concepts into 5 chunks (6 concepts in bundle, 0 with broken links)
Started OkfRagApplication in 7.337 seconds
```

---

Previous: [02 — The OKF format](02-okf-format.md) · Next: [04 — Parsing layer](04-parsing-layer.md)
