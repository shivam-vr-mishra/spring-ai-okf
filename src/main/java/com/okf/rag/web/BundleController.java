package com.okf.rag.web;

import java.util.List;
import java.util.Map;

import com.okf.rag.index.OkfIndexer;
import com.okf.rag.okf.OkfConcept;
import com.okf.rag.okf.OkfGraph;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inspection endpoints for the bundle itself — useful for seeing that parsing,
 * trust-tier derivation and link resolution did what you expect.
 */
@RestController
@RequestMapping("/api/bundle")
public class BundleController {

    private final OkfGraph graph;
    private final OkfIndexer indexer;

    public BundleController(OkfGraph graph, OkfIndexer indexer) {
        this.graph = graph;
        this.indexer = indexer;
    }

    /** Every concept with its parsed frontmatter and resolved outgoing links. */
    @GetMapping
    public List<ConceptView> concepts() {
        return graph.concepts().stream()
                .map(c -> new ConceptView(c.path(), c.type(), c.title(), c.status(),
                        c.trust().tag(), c.generatedBy(), c.tags(), c.links()))
                .toList();
    }

    /** Health of the graph: dangling links and concepts past their freshness window. */
    @GetMapping("/diagnostics")
    public Map<String, Object> diagnostics() {
        return Map.of(
                "conceptCount", graph.size(),
                "brokenLinks", graph.brokenLinks(),
                "staleConcepts", graph.staleConcepts(),
                "deprecatedConcepts", graph.concepts().stream()
                        .filter(OkfConcept::isDeprecated)
                        .map(OkfConcept::path)
                        .toList());
    }

    /** Re-read the bundle from disk and rebuild the embeddings. */
    @PostMapping("/reindex")
    public OkfIndexer.IndexReport reindex() {
        return indexer.reindex();
    }

    public record ConceptView(String path, String type, String title, String status, String trust,
            String generatedBy, List<String> tags, List<String> links) {
    }
}
