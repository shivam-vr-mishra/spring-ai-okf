# 02 — The OKF format

## What OKF is

**Open Knowledge Format (OKF)** is an open, vendor-neutral specification published
by Google Cloud's Data Cloud team. Current version: **v0.2 (2026-07-24)**.

Spec: <https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md>

An OKF **bundle** is a directory of Markdown files. Each non-reserved `.md` file
is one **concept** — a single unit of knowledge — with YAML frontmatter at the
top and ordinary Markdown links to other concepts in the body.

That's it. No database, no server, no SDK, no runtime. Files and links.

## What OKF is *not*

It is **not** a vector database and it does **not** replace RAG. It is the
curated layer that sits *in front of* a vector store. The spec's own framing:
vector search answers *where to land* in a large corpus; OKF answers *where to go
next* using explicit relationships.

This project implements exactly that pairing.

## Bundle structure

```
okf-bundle/
├── index.md                    RESERVED — directory listing (§8)
├── log.md                      RESERVED — chronological history (§9)
├── policies/
│   ├── refund-eligibility.md
│   ├── return-shipping.md
│   └── restocking-fee.md
├── procedures/
│   └── warranty-claim.md
├── systems/
│   └── order-management.md
└── metrics/
    └── return-rate.md
```

`index.md` and `log.md` are **reserved filenames** — they are never concepts.
[`OkfBundleReader`](../src/main/java/com/okf/rag/okf/OkfBundleReader.java) enforces
this with a `Set.of("index.md", "log.md")` filter.

Subdirectories are free-form. The type-named folders here (`policies/`,
`procedures/`) are a convention this project adopts, not a spec requirement —
[`OkfBundleWriter`](../src/main/java/com/okf/rag/ingest/OkfBundleWriter.java)
mirrors it when writing new concepts.

## Frontmatter fields

### Required

| Field | Type | Notes |
|---|---|---|
| `type` | String | **The only always-required key.** Free-form; consumers use it for routing and filtering. No fixed taxonomy. |

A file whose frontmatter lacks a non-empty `type` is not a conformant concept.
`OkfBundleReader` logs and skips it.

### Recommended

| Field | Type | Used here for |
|---|---|---|
| `title` | String | Display name; the key for resolving `relatedTitles` during ingestion |
| `description` | String | One-sentence summary |
| `resource` | URI | Canonical URI of the underlying asset |
| `tags` | List | Cross-cutting keywords → `okf_tags` metadata |

### Lifecycle (§5.4–5.5)

| Field | Values | Effect in this project |
|---|---|---|
| `status` | `draft` \| `stable` \| `deprecated` (default `stable`) | `deprecated` concepts are **never indexed** and **never traversed** |
| `stale_after` | ISO-8601 UTC | Surfaced by `/api/bundle/diagnostics` |

### Provenance (§5.1–5.2)

```yaml
generated: { by: <actor>, at: <ISO8601-UTC> }
verified:
  - { by: <actor>, at: <ISO8601-UTC> }
sources:
  - id: <stable-key>
    resource: <URL|path>     # REQUIRED within a source entry
    title: <string>
    last_modified: <ISO8601-UTC>
```

### Actor convention (§7)

| Form | Meaning | Example |
|---|---|---|
| `<producer>/<version>` | agent or tool | `ollama/llama3.2:3b` |
| `human:<id>` | a person | `human:smishra` |
| `process:<id>` | automation | `process:finance-nightly` |

## Trust tiers — derived, not stored

The spec does not have a "trust" field. Trust is **computed** from `verified`:

| Condition | Tier |
|---|---|
| no `verified` key | `unverified` |
| `verified` present, machine actors only | `machine-confirmed` |
| any entry with a `human:` prefix | `human-reviewed` |

Implemented in [`TrustTier.from()`](../src/main/java/com/okf/rag/okf/TrustTier.java):

```java
boolean human = entries.stream()
        .filter(e -> e instanceof Map)
        .map(e -> ((Map<String, Object>) e).get("by"))
        .filter(Objects::nonNull)
        .map(Object::toString)
        .anyMatch(by -> by.startsWith("human:"));
return human ? HUMAN_REVIEWED : MACHINE_CONFIRMED;
```

This gives you a **review workflow for free**. Concepts the LLM writes during
ingestion get `generated: {by: "ollama/..."}` and no `verified` key, so they land
as `unverified`. A colleague reviews the file in a pull request, adds
`verified: [{by: "human:smishra", at: ...}]`, and it becomes `human-reviewed` —
visible on `/api/search` and available as a ranking signal.

## Cross-links (§6)

Three link forms are valid:

| Form | Example | Traversed here? |
|---|---|---|
| Bundle-relative (recommended) | `/policies/refund-eligibility.md` | yes |
| Relative | `./other.md`, `../procedures/x.md` | yes — normalised to bundle-relative |
| External absolute | `https://example.com/doc` | no — valid OKF, but not a concept |

Links are ordinary Markdown: `[Refund Eligibility](/policies/refund-eligibility.md)`.
There is no special syntax. That is a feature — the file renders correctly in
GitHub, in an editor, and in any Markdown viewer.

## Permissive consumption (§11)

This is the rule most likely to trip you up if you write your own parser. A
conformant consumer **MUST NOT** reject a bundle because of:

- missing optional fields
- unknown `type` values
- unknown frontmatter keys
- **broken cross-links**
- a missing `index.md`

So every parse failure in this project logs a warning and continues. Nothing
throws. See [Parsing layer](04-parsing-layer.md) for how that is implemented, and
[`OkfGraph.traverse`](../src/main/java/com/okf/rag/okf/OkfGraph.java) line 87 for
the broken-link skip.

## Version declaration

Only the bundle-root `index.md` may declare the version:

```yaml
okf_version: "0.2"
```

## Our seed bundle, and why each file exists

| File | `type` | `status` | Role in the demo |
|---|---|---|---|
| `policies/refund-eligibility.md` | policy | stable | **Defines "defect return"** — the term `return-shipping.md` depends on |
| `policies/return-shipping.md` | policy | stable | Says "customer pays *unless* defect return" — deliberately does not restate the definition |
| `procedures/warranty-claim.md` | procedure | stable | **The payload.** Written in operational vocabulary (RMA, triage, disposition) so it is semantically distant from any customer question. Reachable **only** by link. |
| `policies/restocking-fee.md` | policy | **deprecated** | **A trap.** Lexically very close to returns questions, ranks highly, and would give a wrong, more expensive answer. Excluded purely by its `status` field. |
| `systems/order-management.md` | system | stable | `generated: by ollama/...`, no `verified` → demonstrates the `unverified` tier |
| `metrics/return-rate.md` | metric | stable | A plausible distractor near the similarity threshold |

The design intent: `warranty-claim.md` must *never* win on similarity, so that
when it appears in an answer you know traversal put it there.

## A complete example

`okf-bundle/policies/return-shipping.md`:

```markdown
---
type: policy
title: Return Shipping Costs
description: Determines whether the customer or Northwind pays to ship a returned item back.
tags: [returns, shipping, costs, refunds]
status: stable
generated: { by: "human:smishra", at: "2026-11-01T09:00:00Z" }
verified:
  - { by: "human:smishra", at: "2026-11-01T09:05:00Z" }
sources:
  - id: returns-handbook-2026
    resource: /uploads/returns-handbook-2026.pdf
    title: Northwind Returns Handbook 2026
    last_modified: "2026-10-02T00:00:00Z"
---

# Return Shipping Costs

When a customer returns an item, the customer pays the return shipping cost
and the amount is deducted from the refund.

This does **not** apply when the return qualifies as a *defect return*. For a
defect return, Northwind issues a prepaid return label at no cost to the
customer...

## Related Concepts

- [Refund Eligibility](/policies/refund-eligibility.md) — defines what counts as a defect return
- [Warranty Claim Procedure](/procedures/warranty-claim.md) — the steps to follow once confirmed
- [Order Management System](/systems/order-management.md) — where the label and refund are recorded
```

Everything after `---` is the body. The `## Related Concepts` section is a plain
convention — the parser extracts links from **anywhere** in the body with a
regex, so links in running prose work identically.

## Why this beats chunking

| | Chunked PDF | OKF bundle |
|---|---|---|
| Relationships | inferred from vector proximity | written down by the author |
| Reviewable | no | yes — it's a git diff |
| Fixable | re-ingest the source | edit one file |
| Citable | "chunk 47" | `/policies/return-shipping.md` |
| Deprecation | delete and re-embed | one frontmatter field |
| Scales to millions of docs | yes | no — needs curation |

That last row is why OKF is a **layer**, not a replacement. The spec's own
guidance: use OKF for the ~20% of knowledge that is authoritative and stable, and
plain vector search over raw chunks for the long tail.

---

Previous: [01 — Architecture](01-architecture.md) · Next: [03 — Build & configuration](03-build-and-config.md)
