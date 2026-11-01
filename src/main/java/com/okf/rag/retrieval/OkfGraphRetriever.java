package com.okf.rag.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.okf.rag.okf.OkfConcept;
import com.okf.rag.okf.OkfGraph;
import com.okf.rag.okf.OkfProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

/**
 * Two-stage retrieval: probabilistic entry, deterministic expansion.
 *
 * <ol>
 * <li><b>Land.</b> Vector similarity picks the concepts that look like the
 * question. This is fuzzy and is allowed to be — it only has to get into the
 * right neighbourhood.</li>
 * <li><b>Walk.</b> From each landing point, follow the links the author wrote
 * down. This stage is exact, and it is what surfaces concepts that no embedding
 * would ever match — the definition of a term, or the procedure to follow next.</li>
 * </ol>
 *
 * <p>Stage 2 is the reason the bundle exists. Plain RAG stops after stage 1.
 */
@Component
public class OkfGraphRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(OkfGraphRetriever.class);

    private final VectorStore vectorStore;
    private final OkfGraph graph;
    private final OkfProperties properties;

    public OkfGraphRetriever(VectorStore vectorStore, OkfGraph graph, OkfProperties properties) {
        this.vectorStore = vectorStore;
        this.graph = graph;
        this.properties = properties;
    }

    @Override
    public List<Document> retrieve(Query query) {
        return trace(query.text()).documents();
    }

    /**
     * Same retrieval as {@link #retrieve}, but keeps the intermediate stages so the
     * UI can show which concepts were found by search and which were reached only
     * by following links.
     */
    public Trace trace(String question) {
        OkfProperties.Retrieval config = properties.retrieval();

        // -- Stage 1: land -------------------------------------------------
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(config.topK())
                .similarityThreshold(config.similarityThreshold())
                // Defence in depth: deprecated concepts are already excluded at
                // index time, so this filter should never have anything to do.
                .filterExpression("okf_status != 'deprecated'")
                .build());

        // One concept can produce several chunks; collapse to the best per concept.
        Map<String, Double> entryScores = new LinkedHashMap<>();
        for (Document hit : hits) {
            Object path = hit.getMetadata().get(OkfConcept.MD_PATH);
            if (path == null) {
                continue;
            }
            double score = hit.getScore() == null ? 0d : hit.getScore();
            entryScores.merge(path.toString(), score, Math::max);
        }

        // -- Stage 2: walk -------------------------------------------------
        List<OkfConcept> expanded = graph.traverse(entryScores.keySet(), config.maxHops());

        // -- Assemble ------------------------------------------------------
        // Entry concepts are emitted whole rather than as the matching chunk, so
        // the model sees complete, self-consistent knowledge units.
        List<Document> collected = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Double> entry : entryScores.entrySet()) {
            graph.concept(entry.getKey()).ifPresent(concept -> {
                collected.add(concept.asDocument());
                entries.add(new Entry(concept.path(), concept.title(), entry.getValue()));
            });
        }
        List<String> reachedByLink = new ArrayList<>();
        for (OkfConcept concept : expanded) {
            collected.add(concept.asDocument());
            reachedByLink.add(concept.path());
        }

        List<Document> documents = collected;
        if (documents.size() > config.maxDocuments()) {
            log.debug("Capping retrieval at {} documents (had {})", config.maxDocuments(), documents.size());
            documents = documents.subList(0, config.maxDocuments());
        }

        log.debug("Query '{}' -> {} entry concept(s) {}, {} reached by link {}",
                question, entries.size(), entryScores.keySet(), reachedByLink.size(), reachedByLink);

        return new Trace(question, entries, reachedByLink, List.copyOf(documents));
    }

    /** A concept found by vector similarity, with its score. */
    public record Entry(String path, String title, double score) {
    }

    /**
     * @param entries       concepts the embedding search landed on
     * @param reachedByLink concepts only reachable by following authored links —
     *                      the ones plain RAG would have missed
     */
    public record Trace(String question, List<Entry> entries, List<String> reachedByLink,
            List<Document> documents) {

        public List<String> citedPaths() {
            return documents.stream()
                    .map(d -> String.valueOf(d.getMetadata().get(OkfConcept.MD_PATH)))
                    .distinct()
                    .toList();
        }
    }
}
