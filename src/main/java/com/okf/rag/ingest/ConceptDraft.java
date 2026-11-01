package com.okf.rag.ingest;

import java.util.List;

/**
 * A concept proposed by the model from uploaded text, before it is written to
 * disk as an OKF file.
 *
 * <p>Drafts are written with {@code status: draft} and no {@code verified} key,
 * so they land in the {@code unverified} trust tier. A human promotes one by
 * reviewing the file in git and adding a {@code verified: [{by: "human:..."}]}
 * entry — at which point it becomes {@code human-reviewed}.
 */
public record ConceptDraft(
        String title,
        String type,
        String description,
        List<String> tags,
        String body,
        List<String> relatedTitles) {

    /** A body shorter than this carries no actual rule, only a restated label. */
    private static final int MIN_BODY_LENGTH = 80;

    /**
     * Rejects drafts that are structurally valid but substantively empty.
     *
     * <p>A small model's most common failure here is not malformed JSON — the
     * schema prevents that — but echoing the title back as the body, producing a
     * concept file that says nothing. Such a draft is worse than no draft: it gets
     * embedded, scores well on the topic, and then contributes nothing to answers.
     */
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

    public List<String> safeTags() {
        return tags == null ? List.of() : tags;
    }

    public List<String> safeRelatedTitles() {
        return relatedTitles == null ? List.of() : relatedTitles;
    }
}
