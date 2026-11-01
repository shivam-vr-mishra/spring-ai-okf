# 05 — Graph layer

[`OkfGraph`](../src/main/java/com/okf/rag/okf/OkfGraph.java) holds the parsed
bundle in memory as a navigable graph, and implements the traversal that makes
this project different from plain RAG.

## What makes this layer special

Everything else in a RAG system is probabilistic. This class is not. Traversal
follows links a human wrote down, so:

- the same entry concept always expands to the same neighbourhood
- adding a link changes retrieval immediately and predictably
- you can explain *why* a document was retrieved by pointing at a line of Markdown

## State

```java
@Component
public class OkfGraph {

    private final OkfBundleReader reader;

    /** Replaced wholesale on reload; readers never see a half-built graph. */
    private volatile Map<String, OkfConcept> concepts = Map.of();
```

**`volatile` plus whole-map replacement** is the concurrency strategy. Reindexing
can happen on a request thread (`POST /api/bundle/reindex`) while another thread
is retrieving. Rather than lock, `reload()` builds a completely new map and swaps
the reference in one atomic write.

`volatile` guarantees the write is visible to other threads immediately. A reader
sees either the entire old map or the entire new one — never a partially
populated one. Mutating the existing map in place would have required
synchronisation on every read.

Initialised to `Map.of()` so the field is never null even if `@PostConstruct`
fails.

```java
@PostConstruct
public void reload() {
    this.concepts = reader.read();
    logBrokenLinks();
}
```

`@PostConstruct` runs after dependency injection, so the graph is populated
before any other bean can query it. `reload()` is also public — `OkfIndexer`
calls it at the start of every reindex so disk and index stay in step.

## Accessors

```java
public Collection<OkfConcept> concepts()            { return concepts.values(); }
public Optional<OkfConcept> concept(String path)    { return Optional.ofNullable(concepts.get(path)); }
public int size()                                   { return concepts.size(); }

public List<String> linksFrom(String path) {
    OkfConcept concept = concepts.get(path);
    return concept == null ? List.of() : concept.links();
}
```

`linksFrom` returning an empty list for an unknown path (rather than null or an
exception) is what lets the traversal loop stay free of null checks.

---

## The traversal — the heart of the project

```java
public List<OkfConcept> traverse(Collection<String> seeds, int maxHops) {
    Set<String> visited = new LinkedHashSet<>(seeds);
    List<OkfConcept> reached = new ArrayList<>();
    Deque<String> frontier = new ArrayDeque<>(seeds);

    for (int hop = 0; hop < maxHops && !frontier.isEmpty(); hop++) {
        Deque<String> next = new ArrayDeque<>();
        while (!frontier.isEmpty()) {
            for (String link : linksFrom(frontier.poll())) {
                if (!visited.add(link)) {
                    continue;
                }
                OkfConcept neighbour = concepts.get(link);
                if (neighbour == null) {
                    continue;                      // §11: broken link is not an error
                }
                if (neighbour.isDeprecated()) {
                    log.debug("Traversal skipped deprecated concept {}", link);
                    continue;
                }
                reached.add(neighbour);
                next.add(link);
            }
        }
        frontier = next;
    }
    return reached;
}
```

### Setup

```java
Set<String> visited = new LinkedHashSet<>(seeds);
```

**`visited` is pre-seeded with the entry concepts.** This single decision does two
jobs:

1. The seeds are never re-added to `reached`, so the caller gets *only* newly
   discovered concepts. That is what makes `entries` and `reachedByLink`
   non-overlapping in the retriever, and what makes the demo legible.
2. It prevents immediately walking back to where we came from.

`LinkedHashSet` rather than `HashSet` for deterministic iteration — useful when
debugging, since the set is logged.

```java
Deque<String> frontier = new ArrayDeque<>(seeds);
```

`ArrayDeque` is the right queue here: faster than `LinkedList`, no capacity
limit, and `poll()`/`add()` give FIFO behaviour — which is what makes this
breadth-first rather than depth-first. That matters: BFS visits all 1-hop
neighbours before any 2-hop ones, so if `maxDocuments` truncates, you lose the
most distant concepts first.

### The hop loop

```java
for (int hop = 0; hop < maxHops && !frontier.isEmpty(); hop++) {
```

Two termination conditions:

- **`hop < maxHops`** — the depth bound. `maxHops: 0` disables traversal entirely,
  turning this into plain RAG. `maxHops: 1` gives immediate neighbours.
- **`!frontier.isEmpty()`** — early exit when a connected component is exhausted,
  so a sparse bundle doesn't spin through empty iterations.

```java
Deque<String> next = new ArrayDeque<>();
while (!frontier.isEmpty()) {
    for (String link : linksFrom(frontier.poll())) {
```

The classic level-by-level BFS shape: drain the current frontier entirely,
collecting discoveries into `next`, then swap. This keeps hop counting exact — a
single queue with mixed levels would make `maxHops` meaningless.

`frontier.poll()` removes and returns the head, so the `while` terminates.

### The three guards

```java
if (!visited.add(link)) {
    continue;
}
```

**`Set.add` returns false if the element was already present.** This one line is
both the deduplication *and* the cycle breaker, and it is checked before the map
lookup so it costs nothing extra.

Cycles are not hypothetical here. `refund-eligibility.md` links to
`return-shipping.md`, which links back. Without this guard, traversal would
oscillate until `maxHops` ran out, adding duplicates each time.

```java
OkfConcept neighbour = concepts.get(link);
if (neighbour == null) {
    continue;                      // §11: a broken cross-link is not an error
}
```

A link whose target does not exist — deleted file, typo, renamed concept. **OKF
§11 explicitly forbids rejecting a bundle over a broken cross-link**, so this
skips silently. (It is still reported by `brokenLinks()` for diagnostics; see
below.)

```java
if (neighbour.isDeprecated()) {
    log.debug("Traversal skipped deprecated concept {}", link);
    continue;
}
```

The second line of defence against withdrawn knowledge. Deprecated concepts are
already excluded at index time by `OkfIndexer`, so they cannot be *entry* points
— but a stable concept might still link to one. `/policies/restocking-fee.md` is
linked from `refund-eligibility.md` precisely to exercise this path.

Logged at debug rather than skipped silently, because "why didn't my concept show
up?" is a question you will eventually ask.

```java
reached.add(neighbour);
next.add(link);
```

Record the concept, and queue its path so the *next* hop can expand from it.

---

## Walked through with real data

Bundle links:

```
return-shipping.md     → refund-eligibility.md, warranty-claim.md, order-management.md
refund-eligibility.md  → return-shipping.md, warranty-claim.md
warranty-claim.md      → refund-eligibility.md, return-shipping.md, order-management.md
restocking-fee.md      → refund-eligibility.md          [DEPRECATED]
order-management.md    → warranty-claim.md, return-rate.md
return-rate.md         → order-management.md, refund-eligibility.md
```

Call: `traverse([/policies/refund-eligibility.md, /policies/return-shipping.md], 1)`

```
visited  = {refund-eligibility, return-shipping}          ← seeded
frontier = [refund-eligibility, return-shipping]
reached  = []

hop 0:
  poll refund-eligibility → links: return-shipping, warranty-claim
      return-shipping   visited.add → false   SKIP (is a seed)
      warranty-claim    visited.add → true    not null, not deprecated
                                              reached += warranty-claim
                                              next += warranty-claim
  poll return-shipping   → links: refund-eligibility, warranty-claim, order-management
      refund-eligibility visited.add → false  SKIP (is a seed)
      warranty-claim     visited.add → false  SKIP (already found this hop)
      order-management   visited.add → true   reached += order-management

  frontier = next = [warranty-claim, order-management]

hop 1: loop ends — maxHops reached

return [warranty-claim, order-management]
```

**`warranty-claim.md` is the whole point.** It scores 4th on similarity — below
the `topK` cut — because it is written in operational vocabulary (RMA, triage,
disposition) that no customer-phrased question resembles. Vector search will
never return it. Traversal reaches it every single time, because a human wrote
the link.

---

## Diagnostics

```java
public Map<String, List<String>> brokenLinks() {
    Map<String, List<String>> broken = new LinkedHashMap<>();
    for (OkfConcept concept : concepts.values()) {
        List<String> missing = concept.links().stream()
                .filter(link -> !concepts.containsKey(link))
                .toList();
        if (!missing.isEmpty()) {
            broken.put(concept.path(), missing);
        }
    }
    return broken;
}
```

The spec says *consume* permissively — it does not say *hide the problem*. A
dangling link means a concept silently stops being reachable, which is exactly
the kind of bug that produces "the bot used to know this".

```java
private void logBrokenLinks() {
    Map<String, List<String>> broken = brokenLinks();
    if (!broken.isEmpty()) {
        log.warn("OKF bundle has {} concept(s) with unresolved links: {}", broken.size(), broken);
    }
}
```

Warn on startup, never fail. Also exposed at `GET /api/bundle/diagnostics`.

```java
public List<String> staleConcepts() {
    Instant now = Instant.now();
    return concepts.values().stream()
            .filter(c -> c.isStale(now))
            .map(OkfConcept::path)
            .toList();
}
```

`Instant.now()` captured once outside the stream, so every concept is evaluated
against the same instant.

---

## Complexity and limits

| Aspect | Cost |
|---|---|
| Memory | Entire bundle held in heap. Fine for hundreds of concepts; a bundle of 100k files would need rethinking |
| Traversal time | O(V + E) within `maxHops`, bounded by the hop limit |
| Lookup | O(1) `HashMap` |

### Tuning `maxHops`

| Value | Behaviour |
|---|---|
| `0` | Traversal disabled — degenerates to plain RAG |
| `1` | Immediate neighbours. **Correct default.** |
| `2` | Neighbours of neighbours. On this 6-concept bundle, reaches essentially everything |
| `3+` | On a densely linked bundle, the entire graph lands in the prompt |

Because `maxDocuments` truncates from the end and BFS discovers near before far,
raising `maxHops` without raising `maxDocuments` adds work that gets thrown away.

### What this class does not do

- **No edge weighting.** All links are equal. A relevance-weighted walk
  (preferring links from higher-scoring entries) is an obvious extension.
- **No link direction semantics.** `A → B` and `B → A` are distinct edges, but
  nothing distinguishes "defines" from "see also".
- **No automatic reload on file change.** `reload()` is called at startup and on
  explicit reindex. A `WatchService` or a `@Scheduled` poll would close that gap.

---

Previous: [04 — Parsing layer](04-parsing-layer.md) · Next: [06 — Indexing layer](06-indexing-layer.md)
