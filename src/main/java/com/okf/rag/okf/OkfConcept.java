package com.okf.rag.okf;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;

/**
 * One unit of knowledge: a single Markdown file in the bundle.
 *
 * <p>{@code path} is bundle-relative and always starts with {@code /} — it is the
 * join key between the vector store and the filesystem graph. A row in pgvector
 * carries this value in its metadata, which is what lets retrieval jump from
 * "this chunk scored well" back to "here is the concept, and here is what its
 * author linked it to".
 */
public record OkfConcept(
        String path,
        String type,
        String title,
        String description,
        List<String> tags,
        String status,
        Instant staleAfter,
        String generatedBy,
        TrustTier trust,
        List<String> links,
        String body) {

    public static final String STATUS_DEPRECATED = "deprecated";

    /* Metadata keys mirrored into the vector store. */
    public static final String MD_PATH = "okf_path";
    public static final String MD_TYPE = "okf_type";
    public static final String MD_TITLE = "okf_title";
    public static final String MD_TAGS = "okf_tags";
    public static final String MD_STATUS = "okf_status";
    public static final String MD_TRUST = "okf_trust";

    public boolean isDeprecated() {
        return STATUS_DEPRECATED.equalsIgnoreCase(status);
    }

    /** True once the author-declared freshness window has elapsed. */
    public boolean isStale(Instant now) {
        return staleAfter != null && !now.isBefore(staleAfter);
    }

    /**
     * Metadata copied onto every chunk of this concept. Values are kept to simple
     * scalars so pgvector can filter on them in SQL — {@code tags} is therefore
     * joined into a comma-delimited string rather than stored as a list.
     */
    public Map<String, Object> metadata() {
        return Map.of(
                MD_PATH, path,
                MD_TYPE, type,
                MD_TITLE, title == null ? "" : title,
                MD_TAGS, String.join(",", tags),
                MD_STATUS, status,
                MD_TRUST, trust.tag());
    }

    /**
     * Renders the concept for the model. The heading and the link list are kept in
     * the text on purpose: the model can then explain <em>why</em> two concepts
     * relate, not merely that both were retrieved.
     */
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
}
