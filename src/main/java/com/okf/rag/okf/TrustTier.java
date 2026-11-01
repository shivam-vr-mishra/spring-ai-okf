package com.okf.rag.okf;

import java.util.List;
import java.util.Map;

/**
 * Trust classification derived from the OKF {@code verified} frontmatter key.
 *
 * <p>Per OKF v0.2 §5.2 the tier is not stored directly — it is computed from who
 * confirmed the concept. Actors are written {@code human:<id>} for people,
 * {@code <producer>/<version>} for agents and {@code process:<id>} for automation,
 * and consumers key the classification off the {@code human:} prefix.
 */
public enum TrustTier {

    /** No {@code verified} key at all. */
    UNVERIFIED,

    /** Confirmed, but only by machine actors. */
    MACHINE_CONFIRMED,

    /** At least one {@code human:<id>} confirmation. */
    HUMAN_REVIEWED;

    private static final String HUMAN_PREFIX = "human:";

    /**
     * @param verified the raw {@code verified} frontmatter value, or {@code null}
     */
    @SuppressWarnings("unchecked")
    public static TrustTier from(Object verified) {
        if (!(verified instanceof List<?> entries) || entries.isEmpty()) {
            return UNVERIFIED;
        }
        boolean human = entries.stream()
                .filter(e -> e instanceof Map)
                .map(e -> ((Map<String, Object>) e).get("by"))
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .anyMatch(by -> by.startsWith(HUMAN_PREFIX));

        return human ? HUMAN_REVIEWED : MACHINE_CONFIRMED;
    }

    /** Lowercase hyphenated form, used as vector-store metadata. */
    public String tag() {
        return name().toLowerCase().replace('_', '-');
    }
}
