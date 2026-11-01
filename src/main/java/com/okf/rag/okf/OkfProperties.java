package com.okf.rag.okf;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the OKF bundle and the two-stage retrieval over it.
 */
@ConfigurationProperties("okf")
public record OkfProperties(

        /* Filesystem root of the OKF bundle. */
        @DefaultValue("./okf-bundle") String bundlePath,

        @DefaultValue Retrieval retrieval) {

    public record Retrieval(

            /* Stage 1 — how many entry concepts the vector search may return. */
            @DefaultValue("3") int topK,

            @DefaultValue("0.3") double similarityThreshold,

            /*
             * Stage 2 — how far to follow authored links out from each entry concept.
             * Deliberately small: a densely linked bundle will otherwise pull the entire
             * knowledge base into the prompt and blow the context window.
             */
            @DefaultValue("1") int maxHops,

            /* Hard ceiling on documents handed to the model, after traversal. */
            @DefaultValue("8") int maxDocuments) {
    }
}
