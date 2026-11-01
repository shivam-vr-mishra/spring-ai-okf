# 04 — Parsing layer

Turns Markdown files on disk into Java objects. Four classes:

| Class | Role |
|---|---|
| [`OkfProperties`](../src/main/java/com/okf/rag/okf/OkfProperties.java) | Binds `okf.*` from `application.yml` |
| [`TrustTier`](../src/main/java/com/okf/rag/okf/TrustTier.java) | Derives trust from the `verified` key |
| [`OkfConcept`](../src/main/java/com/okf/rag/okf/OkfConcept.java) | One concept, immutable |
| [`OkfBundleReader`](../src/main/java/com/okf/rag/okf/OkfBundleReader.java) | Disk → `OkfConcept`, permissively |

---

## `OkfProperties`

```java
@ConfigurationProperties("okf")
public record OkfProperties(
        @DefaultValue("./okf-bundle") String bundlePath,
        @DefaultValue Retrieval retrieval) {

    public record Retrieval(
            @DefaultValue("3")    int topK,
            @DefaultValue("0.3")  double similarityThreshold,
            @DefaultValue("1")    int maxHops,
            @DefaultValue("8")    int maxDocuments) {
    }
}
```

A **record** rather than a class with setters, so configuration is immutable
after startup. Spring binds records via their canonical constructor.

`@DefaultValue` on a nested record type (line 2) is the detail people miss —
without it, `retrieval` would be `null` when the `okf.retrieval` block is absent
from YAML, and every access would NPE. With it, Spring constructs a `Retrieval`
using each component's own defaults.

Activated by `@ConfigurationPropertiesScan` on `OkfRagApplication`. Without that
annotation the record is never registered and injection fails.

---

## `TrustTier`

```java
public enum TrustTier {
    UNVERIFIED,          // no `verified` key
    MACHINE_CONFIRMED,   // verified, but only by machines
    HUMAN_REVIEWED;      // at least one human:<id>
```

OKF v0.2 §5.2 does not store trust as a field — it is **derived** from who
confirmed the concept.

```java
@SuppressWarnings("unchecked")
public static TrustTier from(Object verified) {
    if (!(verified instanceof List<?> entries) || entries.isEmpty()) {
        return UNVERIFIED;
    }
    boolean human = entries.stream()
            .filter(e -> e instanceof Map)
            .map(e -> ((Map<String, Object>) e).get("by"))
            .filter(Objects::nonNull)
            .map(Object::toString)
            .anyMatch(by -> by.startsWith("human:"));

    return human ? HUMAN_REVIEWED : MACHINE_CONFIRMED;
}
```

Line by line:

- **`instanceof List<?> entries`** — pattern matching handles both "key absent"
  (`null`) and "key present but malformed" (a String where a list was expected)
  in one check. Permissive parsing, per §11.
- **`.filter(e -> e instanceof Map)`** — skips malformed entries rather than
  throwing. A list item that isn't a mapping is ignored.
- **`.anyMatch(by -> by.startsWith("human:"))`** — the entire trust model. §7
  says consumers key the classification off the `human:` prefix.

```java
public String tag() {
    return name().toLowerCase().replace('_', '-');
}
```

`HUMAN_REVIEWED` → `human-reviewed`. This string goes into vector-store metadata
so it can be filtered in SQL and shown in API responses.

### Why this matters practically

It gives you a review workflow with no extra machinery. Concepts written by the
LLM during ingestion carry `generated: {by: "ollama/llama3.2:3b"}` and no
`verified` key → `unverified`. A human reviews the file in a pull request, adds
`verified: [{by: "human:smishra", at: ...}]` → `human-reviewed`. You can then
rank or filter on it.

---

## `OkfConcept`

```java
public record OkfConcept(
        String path,          // "/policies/return-shipping.md" — THE JOIN KEY
        String type,          // required by the spec
        String title,
        String description,
        List<String> tags,
        String status,        // draft | stable | deprecated
        Instant staleAfter,   // nullable
        String generatedBy,   // nullable
        TrustTier trust,
        List<String> links,   // resolved bundle-relative paths
        String body) {
```

`path` is bundle-relative and always starts with `/`. It is the join key between
pgvector and the filesystem — every indexed row carries it, which is what lets
retrieval jump from "this chunk scored well" to "here is the concept and its
neighbours".

### Metadata keys

```java
public static final String MD_PATH   = "okf_path";
public static final String MD_TYPE   = "okf_type";
public static final String MD_TITLE  = "okf_title";
public static final String MD_TAGS   = "okf_tags";
public static final String MD_STATUS = "okf_status";
public static final String MD_TRUST  = "okf_trust";
```

Constants rather than string literals, because these keys appear in four places:
the indexer (writing), the retriever (reading), the filter expression (SQL), and
the controllers (projections). A typo in any one of them would fail silently —
`getMetadata().get("okf_pth")` returns `null`, not an error.

### Lifecycle helpers

```java
public boolean isDeprecated() {
    return STATUS_DEPRECATED.equalsIgnoreCase(status);
}

public boolean isStale(Instant now) {
    return staleAfter != null && !now.isBefore(staleAfter);
}
```

`equalsIgnoreCase` with the constant first — null-safe regardless of what the
frontmatter contained. `isStale` takes `now` as a parameter rather than calling
`Instant.now()` internally, which keeps the record pure and testable.

### `metadata()`

```java
public Map<String, Object> metadata() {
    return Map.of(
            MD_PATH,   path,
            MD_TYPE,   type,
            MD_TITLE,  title == null ? "" : title,
            MD_TAGS,   String.join(",", tags),
            MD_STATUS, status,
            MD_TRUST,  trust.tag());
}
```

Two deliberate decisions:

1. **`tags` is joined into a comma-delimited String**, not stored as a list.
   pgvector stores metadata as JSONB and filters it in SQL; scalars filter
   cleanly, arrays do not.
2. **`Map.of` rejects null values** with an NPE. Hence the `title == null ? ""`
   guard. `type` and `status` cannot be null — the reader guarantees `type` is
   non-blank and defaults `status` to `"stable"`.

### `asDocument()`

```java
public Document asDocument() {
    StringBuilder text = new StringBuilder();
    text.append("# ").append(title == null ? path : title).append('\n');
    text.append("(concept ").append(path).append(", type: ").append(type).append(")\n\n");
    text.append(body.strip());

    if (!links.isEmpty()) {
        text.append("\n\nLinked concepts: ").append(String.join(", ", links));
    }
    return new Document(text.toString(), metadata());
}
```

This is what the LLM actually reads. Three things are injected into the text
itself, not just metadata:

- **The heading** — gives the model a name for the concept.
- **`(concept /path/to/file.md, type: policy)`** — the model can only cite a path
  if the path is in its context. Metadata is *not* sent to the model; only text
  is. This line is why citations work at all.
- **`Linked concepts: ...`** — lets the model explain *why* two concepts relate,
  rather than just listing both.

> **Known demo caveat.** Because the link list is in the text, plain RAG can
> sometimes *name* a linked file it never actually read. In the comparison run,
> plain RAG cited `/procedures/warranty-claim.md` while being unable to state its
> contents — and fabricated "3–5 business days" instead of the real 5. Useful to
> know when interpreting the demo: the giveaway is the wrong *content*, not the
> missing citation.

---

## `OkfBundleReader`

The largest class in the parsing layer. Walks the bundle and parses each file.

### Reserved filenames

```java
private static final Set<String> RESERVED = Set.of("index.md", "log.md");
```

Per §8–§9 these are never concepts. Checked lowercase against the filename at
**any** directory depth — a subdirectory may have its own `index.md` listing.

### The link regex

```java
private static final Pattern LINK = Pattern.compile("\\[[^\\]]*]\\(([^)\\s]+)\\)");
```

Matches `[any text](target)` and captures the target.

- `\\[[^\\]]*]` — opening bracket, any non-`]` characters, closing bracket.
  Deliberately does **not** support nested brackets; OKF links are plain.
- `\\(([^)\\s]+)\\)` — parenthesised target with no spaces or `)`.

Links are found **anywhere in the body**, not only under a `## Related Concepts`
heading. The heading is a human convention; the parser doesn't require it.

### Walking the bundle

```java
public Map<String, OkfConcept> read() {
    Path root = bundleRoot();
    if (!Files.isDirectory(root)) {
        log.warn("OKF bundle directory not found at {} — starting with an empty graph", root);
        return Map.of();
    }

    Map<String, OkfConcept> concepts = new LinkedHashMap<>();
    try (Stream<Path> files = Files.walk(root)) {
        files.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                .filter(p -> !RESERVED.contains(p.getFileName().toString().toLowerCase()))
                .sorted()
                .forEach(file -> parse(root, file).ifPresent(c -> concepts.put(c.path(), c)));
    }
    catch (IOException e) {
        throw new UncheckedIOException("Unable to walk OKF bundle at " + root, e);
    }
    return concepts;
}
```

- **Missing directory warns and returns empty** rather than throwing. The app
  still starts; the debug endpoints still work.
- **`try (Stream<Path> files = ...)`** — `Files.walk` returns a stream holding an
  open file handle. Not closing it leaks descriptors.
- **`.sorted()`** — deterministic ordering so startup logs and `/api/bundle` are
  stable between runs.
- **`LinkedHashMap`** — preserves that order.
- **`.ifPresent(...)`** — a file that fails to parse is simply absent. The walk
  continues.

### Frontmatter boundaries

```java
private static int[] frontmatterBounds(String content) {
    String text = content.stripLeading();
    int offset = content.length() - text.length();
    if (!text.startsWith(DELIMITER)) {
        return null;
    }
    int yamlStart = offset + DELIMITER.length();
    Matcher closer = Pattern.compile("(?m)^---\\s*$").matcher(content);
    if (!closer.find(yamlStart)) {
        return null;
    }
    return new int[] { yamlStart, closer.start(), closer.end() };
}
```

Returns three offsets: where the YAML starts, where it ends, where the body
starts.

- **`stripLeading()` + `offset`** — tolerates a leading blank line or BOM while
  keeping offsets valid against the *original* string.
- **`(?m)^---\\s*$`** — `MULTILINE` so `^`/`$` match line boundaries. The pattern
  requires a line consisting *only* of `---`, so a horizontal rule mid-document
  doesn't terminate the block early.
- **`closer.find(yamlStart)`** — searches from after the opening delimiter, so
  the opener can't match itself.
- **Returns `null`, never throws.** The caller logs and skips.

### Safe YAML loading

```java
Yaml parser = new Yaml(new SafeConstructor(new LoaderOptions()));
Object loaded = parser.load(yaml);
if (loaded instanceof Map<?, ?> map) {
    return (Map<String, Object>) map;
}
log.warn("Frontmatter of {} is not a YAML mapping — treating as empty", path);
return Map.of();
```

**`SafeConstructor` is a security control, not a style choice.** Plain
`new Yaml()` can instantiate arbitrary Java classes named in the document
(`!!javax.script.ScriptEngineManager [...]`) — a known deserialisation attack.
Since concept files may be authored by an LLM or arrive from an upload, they are
untrusted input. `SafeConstructor` restricts parsing to standard scalars and
collections.

### Building the concept

```java
String type = str(fm.get("type"));
if (type == null || type.isBlank()) {
    log.warn("Skipping {} — frontmatter has no non-empty 'type' (§11)", relative);
    return Optional.empty();
}

String status = str(fm.get("status"));
return Optional.of(new OkfConcept(
        relative,
        type,
        str(fm.get("title")),
        str(fm.get("description")),
        stringList(fm.get("tags")),
        status == null || status.isBlank() ? "stable" : status,   // §5.4 default
        instant(fm.get("stale_after")),
        str(nested(fm.get("generated"), "by")),
        TrustTier.from(fm.get("verified")),
        extractLinks(relative, body),
        body));
```

`type` is the only hard requirement (§11). Everything else degrades gracefully.
`status` defaults to `"stable"` per §5.4 — important, because `isDeprecated()`
is consulted at index time and a null status would NPE.

The whole method is wrapped in `catch (Exception e)` that logs and returns empty,
so **no malformed file can ever break startup**.

### Link extraction and resolution

```java
private static List<String> extractLinks(String conceptPath, String body) {
    List<String> links = new ArrayList<>();
    Matcher matcher = LINK.matcher(body);
    while (matcher.find()) {
        String href = matcher.group(1);
        if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) {
            continue;                                  // external — valid OKF, not a concept
        }
        int fragment = href.indexOf('#');
        if (fragment >= 0) {
            href = href.substring(0, fragment);        // strip #anchor
        }
        if (!href.endsWith(".md")) {
            continue;                                  // not a concept file
        }
        String resolved = resolve(conceptPath, href);
        if (!resolved.equals(conceptPath) && !links.contains(resolved)) {
            links.add(resolved);                       // no self-links, no duplicates
        }
    }
    return Collections.unmodifiableList(links);
}
```

Four filters, in order: external URLs out, anchors stripped, non-Markdown out,
self-links and duplicates out. The self-link check matters — a concept that
links to itself would make traversal do pointless work.

```java
private static String resolve(String conceptPath, String href) {
    String base;
    if (href.startsWith("/")) {
        base = href;                                   // already bundle-relative
    }
    else {
        int slash = conceptPath.lastIndexOf('/');
        String parent = slash <= 0 ? "" : conceptPath.substring(0, slash);
        base = parent + "/" + href;                    // relative to this file
    }
    return "/" + Paths.get(base).normalize().toString()
            .replace('\\', '/')
            .replaceFirst("^/+", "");
}
```

Normalises all three §6 link forms to one canonical representation.
`Paths.get(...).normalize()` collapses `..` and `.` segments:

| Input (from `/policies/x.md`) | Output |
|---|---|
| `/procedures/warranty-claim.md` | `/procedures/warranty-claim.md` |
| `./refund-eligibility.md` | `/policies/refund-eligibility.md` |
| `../procedures/warranty-claim.md` | `/procedures/warranty-claim.md` |

The `replaceFirst("^/+", "")` then re-prefixing with `/` guarantees exactly one
leading slash regardless of what `normalize()` produced — without it,
`/` + `/procedures/x.md` would yield `//procedures/x.md` and the graph lookup
would miss.

### Lenient coercion

```java
private static List<String> stringList(Object value) {
    if (value instanceof List<?> list) {
        return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }
    if (value instanceof String s && !s.isBlank()) {
        return Stream.of(s.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
    }
    return List.of();
}
```

Accepts both `tags: [a, b]` and `tags: a, b`. Returns empty rather than null.

```java
private static Instant instant(Object value) {
    if (value instanceof Date date) {
        return date.toInstant();
    }
    if (value instanceof String s && !s.isBlank()) {
        try { return Instant.parse(s.trim()); }
        catch (Exception e) { return null; }
    }
    return null;
}
```

**SnakeYAML's implicit resolver turns an unquoted ISO timestamp into a
`java.util.Date`, but a quoted one stays a `String`.** Both forms appear in real
bundles, so both are handled. A malformed timestamp yields null rather than
throwing.

---

## The design principle running through this layer

**Nothing throws on bad input.** Every failure path logs a warning and degrades:
a missing directory → empty graph; an unparseable file → skipped; a malformed
`verified` list → `UNVERIFIED`; a bad timestamp → null.

That is not defensive-programming reflex — it is OKF §11, which requires that
consumers must not reject a bundle for missing fields, unknown keys, unknown
types or broken links. The only `throw` in the whole class is for an I/O failure
walking the directory, which is a genuine environmental error rather than a
content problem.

---

Previous: [03 — Build & configuration](03-build-and-config.md) · Next: [05 — Graph layer](05-graph-layer.md)
