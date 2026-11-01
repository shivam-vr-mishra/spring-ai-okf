package com.okf.rag.index;

import java.util.List;

import com.okf.rag.okf.OkfConcept;
import com.okf.rag.okf.OkfGraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Embeds OKF concepts into pgvector.
 *
 * <p>Only the curated concept files are embedded — never the raw upload. That is
 * the whole point of the OKF layer: by the time anything reaches the vector store
 * it has already been given a type, a status and a set of authored links, all of
 * which ride along as metadata and become filterable at query time.
 */
@Service
public class OkfIndexer {

    private static final Logger log = LoggerFactory.getLogger(OkfIndexer.class);

    private final OkfGraph graph;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final String tableName;

    public OkfIndexer(OkfGraph graph, VectorStore vectorStore, JdbcTemplate jdbcTemplate,
            @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
        this.graph = graph;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void indexOnStartup() {
        try {
            reindex();
        }
        catch (Exception e) {
            // Don't take the app down — the bundle and the debug endpoints are
            // still useful even if Ollama or Postgres isn't up yet.
            log.error("Initial OKF indexing failed. Fix the cause and POST /api/admin/reindex. Reason: {}",
                    e.toString());
        }
    }

    /**
     * Rebuilds the whole index from the bundle on disk.
     *
     * <p>POC simplification: the table is truncated and fully rewritten. The bundle
     * is small enough that this takes well under a second, and it guarantees that
     * concepts deleted from disk also disappear from the index. A production build
     * would diff by content hash and only re-embed what changed.
     */
    public IndexReport reindex() {
        graph.reload();

        jdbcTemplate.update("DELETE FROM " + tableName);

        TokenTextSplitter splitter = new TokenTextSplitter();
        int concepts = 0;
        int chunks = 0;

        for (OkfConcept concept : graph.concepts()) {
            if (concept.isDeprecated()) {
                // Deprecated knowledge stays on disk for audit, but must never be
                // retrievable — leaving it out of the index is the strongest guarantee.
                log.info("Not indexing {} — status is deprecated", concept.path());
                continue;
            }
            List<Document> split = splitter.apply(List.of(concept.asDocument()));
            vectorStore.add(split);
            concepts++;
            chunks += split.size();
            log.debug("Indexed {} as {} chunk(s)", concept.path(), split.size());
        }

        IndexReport report = new IndexReport(concepts, chunks, graph.size(), graph.brokenLinks().size());
        log.info("Indexed {} concepts into {} chunks ({} concepts in bundle, {} with broken links)",
                report.conceptsIndexed(), report.chunksWritten(), report.conceptsInBundle(),
                report.conceptsWithBrokenLinks());
        return report;
    }

    public record IndexReport(int conceptsIndexed, int chunksWritten, int conceptsInBundle,
            int conceptsWithBrokenLinks) {
    }
}
