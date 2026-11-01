# 09 — Ingestion layer

The write path: an uploaded document becomes OKF concept files on disk, linked
into the existing graph, and re-indexed.

| Class | Role |
|---|---|
| [`IngestionService`](../src/main/java/com/okf/rag/ingest/IngestionService.java) | The five-step pipeline |
| [`ConceptDraft`](../src/main/java/com/okf/rag/ingest/ConceptDraft.java) | Model output, before disk |
| [`ConceptExtractor`](../src/main/java/com/okf/rag/ingest/ConceptExtractor.java) | Chunks → drafts, via Ollama + JSON schema |
| [`OkfBundleWriter`](../src/main/java/com/okf/rag/ingest/OkfBundleWriter.java) | Drafts → Markdown files |

## The pipeline

```java
public IngestResult ingest(MultipartFile file) {
    String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();

    // 1. Read — Tika handles PDF, DOCX, PPTX, HTML and plain text.
    List<Document> raw = new TikaDocumentReader(toResource(file, filename)).get();

    // 2. Chunk — defaults are 800 tokens with a 350-character minimum.
    List<Document> chunks = new TokenTextSplitter().apply(raw);

    // 3. Curate — the step that creates the OKF layer.
    List<String> existingTitles = graph.concepts().stream()
            .map(OkfConcept::title)
            .filter(t -> t != null && !t.isBlank())
            .toList();
    List<ConceptDraft> drafts = extractor.extract(chunks, existingTitles);

    // 4. Persist as Markdown, resolving relatedTitles into real links.
    List<String> written = writer.write(drafts, filename);

    // 5. Re-read the bundle and rebuild embeddings.
    OkfIndexer.IndexReport report = indexer.reindex();

    return new IngestResult(filename, chunks.size(), drafts.size(), written, report);
}
```

**Note what is absent: the chunks are never embedded.** They exist only to feed
step 3. Only curated concepts reach the vector store — see
[Indexing layer](06-indexing-layer.md) for why that constraint matters.

### Step 3's `existingTitles` — a bug fix made visible

Passing the existing titles into the extractor is what lets a new concept **link
into** the graph rather than sit beside it.

Without it, the first working version produced three gift-return concepts with
**zero links to the existing bundle**, even though the source text said *"double
the standard 30 day window described in the refund eligibility policy"*. The
model had no vocabulary for referring to concepts it hadn't just written.

With it:

```
NEW /policies/gift-return-time-frame.md        links=['/policies/refund-eligibility.md']
NEW /policies/damaged-gift-return-handling.md  links=['/policies/return-shipping.md']
NEW /policies/gift-return-refund-type.md       links=['/policies/return-shipping.md']
```

The graph grew from an upload. That is the ingestion story working end to end.

### The `ByteArrayResource` wrapper

```java
private static ByteArrayResource toResource(MultipartFile file, String filename) {
    return new ByteArrayResource(file.getBytes()) {
        @Override
        public String getFilename() {
            return filename;     // Tika uses it as a media-type detection hint
        }
    };
}
```

`ByteArrayResource` returns `null` from `getFilename()` by default. Tika uses the
filename extension as a hint alongside content sniffing, and without it detection
on ambiguous files degrades. The anonymous subclass supplies it.

---

## `ConceptDraft`

```java
public record ConceptDraft(
        String title, String type, String description,
        List<String> tags, String body, List<String> relatedTitles) {
```

The model's output shape, before anything touches disk. Deliberately flat — a
nested schema is harder for a small model to satisfy.

### The quality guard

```java
private static final int MIN_BODY_LENGTH = 80;

public boolean isUsable() {
    if (title == null || title.isBlank() || type == null || type.isBlank()
            || body == null || body.isBlank()) {
        return false;
    }
    String trimmedBody = body.strip();
    if (trimmedBody.length() < MIN_BODY_LENGTH) {
        return false;
    }
    return !trimmedBody.equalsIgnoreCase(title.strip());
}
```

This exists because of an observed failure. With a valid JSON schema enforced,
llama3.2:3b still produced:

```markdown
# Gift Return Code

Gift Return Code
```

Structurally perfect, substantively empty. The 60-day window, the store-credit
rule, the email-address mechanism — all lost.

**Such a draft is worse than no draft**: it gets embedded, scores well on its
topic, and contributes nothing to answers. The guard rejects bodies under 80
characters and bodies that merely echo the title.

```java
public List<String> safeTags()          { return tags == null ? List.of() : tags; }
public List<String> safeRelatedTitles() { return relatedTitles == null ? List.of() : relatedTitles; }
```

Jackson leaves absent array fields as null. These avoid null checks everywhere
downstream.

---

## `ConceptExtractor`

### Schema-constrained output

```java
public ConceptExtractor(ChatModel chatModel) {
    this.chatClient = ChatClient.builder(chatModel).build();
    this.converter = new BeanOutputConverter<>(ConceptDraftList.class);
    this.schema = this.converter.getJsonSchemaMap();
}

private OllamaChatOptions.Builder optionsBuilder() {
    OllamaChatOptions.Builder builder = OllamaChatOptions.builder();
    builder.format(this.schema);   // Ollama structured output — enforced, not requested
    builder.temperature(0.0);
    builder.numCtx(8192);
    return builder;
}
```

**This is the single most important fix in the ingestion layer.**

`BeanOutputConverter` normally works by injecting a JSON schema into the *prompt*
and parsing whatever comes back. For a 3B model that is not enough. The first
attempt failed with:

```
Unrecognized token 'A': was expecting (JSON String, Number, Array, Object...)
 (through reference chain: ConceptDraftList["concepts"]->ArrayList[0])
```

The model returned `{"concepts": ["A gift return is..."]}` — an array of
**strings** where objects were required.

`builder.format(schema)` passes the schema to Ollama's `format` parameter, which
constrains **token-level decoding**. The model becomes structurally incapable of
emitting invalid JSON. `getJsonSchemaMap()` returns the schema as a `Map`, which
is what Ollama wants.

`temperature(0.0)` — extraction is not a creative task.

### Why the builder is constructed with statements

```java
OllamaChatOptions.Builder builder = OllamaChatOptions.builder();
builder.format(this.schema);
builder.temperature(0.0);
```

rather than a fluent chain. `format` and friends are declared on the generic
`AbstractBuilder<B>` and return it **raw**, losing the type parameter. A fluent
chain therefore fails to satisfy `ChatClient.options(B extends ChatOptions.Builder<?>)`:

```
required: B
found:    OllamaChatOptions
reason: inference variable B has incompatible bounds
```

Note also that `.options()` in Spring AI 2.0 takes a **builder**, not a built
options object — hence `optionsBuilder()` returning the builder itself, fresh per
call to avoid sharing mutable state across concurrent requests.

### The prompt

Four things it does, each earned:

| Instruction | Why |
|---|---|
| "Produce 1 to 3 concepts" | Unbounded, a small model fragments text into dozens of trivia |
| "body — THE FULL TEXT OF THE RULE… must NEVER be just the title repeated" | Directly targets the empty-body failure. Shouty because subtle phrasing did not work |
| "relatedTitles — …any of the existing concepts listed below. If the text mentions an existing concept by name, you MUST list it" | Creates links into the existing graph |
| A worked example with a `$500` figure | Few-shot. Demonstrating that the body carries the *rule* worked better than describing it |

### The chunk cap

```java
private static final int MAX_CHUNKS = 12;

if (chunks.size() > MAX_CHUNKS) {
    log.warn("Upload produced {} chunks; only the first {} will be read for concepts. "
            + "The remaining {} are NOT in the knowledge base.",
            chunks.size(), MAX_CHUNKS, chunks.size() - MAX_CHUNKS);
    considered = chunks.subList(0, MAX_CHUNKS);
}
```

One model call per chunk, so a 200-page PDF would mean 200 calls. The cap bounds
that.

**The warning is deliberately explicit about what was dropped.** A silent
truncation reads as "we processed your document" when a third of it is missing —
the kind of thing that erodes trust in the whole system.

### Per-chunk error isolation

```java
catch (Exception e) {
    log.warn("Chunk {}/{} could not be converted to concepts: {}", ...);
}
```

Inside the loop, so one bad chunk does not abort a 12-chunk upload.

```java
List<ConceptDraft> usable = result.concepts().stream().filter(ConceptDraft::isUsable).toList();
int rejected = result.concepts().size() - usable.size();
if (rejected > 0) {
    log.warn("Chunk {}/{}: discarded {} concept(s) whose body was empty or "
            + "merely restated the title", i + 1, considered.size(), rejected);
}
```

Rejections are counted and reported, so low extraction quality is visible rather
than appearing as "the model just didn't find much".

---

## `OkfBundleWriter`

### Actor identity

```java
public OkfBundleWriter(..., @Value("${spring.ai.ollama.chat.model:unknown}") String chatModel) {
    this.generatorActor = "ollama/" + chatModel;      // OKF §7: <producer>/<version>
}
```

Reads the configured model name so provenance is accurate automatically. Change
the model in YAML and new concepts record the new actor.

### The title index

```java
Map<String, String> titleIndex = new HashMap<>();
for (OkfConcept existing : graph.concepts()) {
    if (existing.title() != null) {
        titleIndex.put(normalise(existing.title()), existing.path());
    }
}
for (ConceptDraft draft : drafts) {
    if (draft.isUsable()) {
        titleIndex.put(normalise(draft.title()), pathFor(draft));
    }
}
```

Built from **both** existing concepts and the current batch, so a draft can link
to a sibling draft *or* to something already in the bundle.

```java
private static String normalise(String title) {
    return title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
}
```

`"Refund Eligibility"`, `"refund-eligibility"` and `"Refund  Eligibility!"` all
collapse to `refundeligibility`, so the model's casing and punctuation don't
matter.

### Rendering frontmatter

```java
out.append("status: draft\n");
out.append("generated: { by: ").append(yaml(generatorActor))
        .append(", at: ").append(yaml(now.toString())).append(" }\n");
```

**`status: draft` and no `verified` key** — machine-authored content lands in the
`unverified` trust tier by design. Promotion is a human adding
`verified: [{by: "human:..."}]` in a pull request.

```java
private static String yaml(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
}
```

**Always quote, always escape.** Model output is untrusted: a title containing a
colon, a `#`, or a newline would otherwise produce frontmatter that fails to
parse — and `OkfBundleReader` would silently skip the file it just wrote.
Backslashes are escaped before quotes, which is the correct order.

### Link resolution

```java
for (String related : draft.safeRelatedTitles()) {
    String target = titleIndex.get(normalise(related));
    if (target != null && !target.equals(self) && !links.contains(target)) {
        links.add("- [" + related + "](" + target + ")");
    }
}
if (!links.isEmpty()) {
    out.append("\n## Related Concepts\n\n");
    links.forEach(l -> out.append(l).append('\n'));
}
```

Three guards: unknown titles are dropped (`target != null` — the model cannot
invent a link), self-links excluded, duplicates excluded.

This is the moment the graph is created. Everything `OkfGraph.traverse` does
later depends on these lines emitting real, resolvable paths.

### Paths and slugs

```java
private static final Map<String, String> TYPE_DIRECTORIES = Map.of(
        "policy", "policies",   "procedure", "procedures",
        "system", "systems",    "metric",    "metrics",
        "definition", "definitions", "role",  "roles");

private static String pathFor(ConceptDraft draft) {
    String directory = TYPE_DIRECTORIES.getOrDefault(draft.type().toLowerCase(Locale.ROOT), "concepts");
    return "/" + directory + "/" + slug(draft.title()) + ".md";
}
```

Unknown types fall back to `concepts/` rather than failing — OKF has no fixed
type taxonomy, so an unexpected value must be tolerated.

```java
private static String slug(String value) {
    String slug = value.toLowerCase(Locale.ROOT)
            .replaceAll("\\.[a-z0-9]{1,5}$", "")   // drop a file extension
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
    return slug.isEmpty() ? "concept" : slug;
}
```

`Locale.ROOT` matters: under a Turkish locale, `"I".toLowerCase()` yields `ı`,
producing different filenames on different machines.

The extension strip is for `slug(sourceName)` — `"gift-returns.txt"` becomes the
source id `"gift-returns"`.

---

## What ingestion does well, and what it does not

**Works:** Tika reading, chunking, schema-constrained extraction, quality
filtering, OKF-conformant file output, link resolution into the existing graph,
automatic reindex.

**Limits:**

- **No deduplication.** Uploading a document that restates an existing policy
  creates a near-duplicate rather than updating the original. The first test run
  produced `/policies/refund-eligibility-policy.md` alongside the hand-written
  `/policies/refund-eligibility.md`.
- **Drafts need editing.** A 3B model loses nuance. Treat output as a first pass —
  which is precisely what `status: draft` encodes.
- **One model call per chunk**, sequential. Slow on CPU.
- **No transaction.** If `reindex()` fails, the files are already written. Re-run
  `POST /api/bundle/reindex`.
- **No `log.md` update.** The spec's reserved change-log file is not maintained
  automatically.

---

Previous: [08 — Chat layer](08-chat-layer.md) · Next: [10 — Web layer](10-web-layer.md)
