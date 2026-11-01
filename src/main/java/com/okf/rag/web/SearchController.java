package com.okf.rag.web;

import java.util.List;

import com.okf.rag.okf.OkfConcept;
import com.okf.rag.retrieval.OkfGraphRetriever;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Semantic search with no LLM involved, so the embedding layer can be inspected
 * on its own.
 */
@RestController
@RequestMapping("/api")
public class SearchController {

    private final VectorStore vectorStore;
    private final OkfGraphRetriever retriever;

    public SearchController(VectorStore vectorStore, OkfGraphRetriever retriever) {
        this.vectorStore = vectorStore;
        this.retriever = retriever;
    }

    /** Stage 1 only: raw vector similarity. */
    @GetMapping("/search")
    public List<Hit> search(@RequestParam("q") String query,
            @RequestParam(value = "topK", defaultValue = "5") int topK) {

        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.0)
                .build());

        return results.stream()
                .map(d -> new Hit(
                        String.valueOf(d.getMetadata().get(OkfConcept.MD_PATH)),
                        String.valueOf(d.getMetadata().get(OkfConcept.MD_TITLE)),
                        String.valueOf(d.getMetadata().get(OkfConcept.MD_TYPE)),
                        String.valueOf(d.getMetadata().get(OkfConcept.MD_TRUST)),
                        d.getScore()))
                .toList();
    }

    /** Both stages, showing which concepts came from search and which from links. */
    @GetMapping("/retrieve")
    public RetrievalView retrieve(@RequestParam("q") String query) {
        OkfGraphRetriever.Trace trace = retriever.trace(query);
        return new RetrievalView(trace.question(), trace.entries(), trace.reachedByLink(), trace.citedPaths());
    }

    public record Hit(String path, String title, String type, String trust, Double score) {
    }

    /**
     * Projection of {@link OkfGraphRetriever.Trace} without the raw Document
     * objects, which are not meant to be serialised to JSON.
     */
    public record RetrievalView(String question, List<OkfGraphRetriever.Entry> foundBySearch,
            List<String> reachedByLink, List<String> contextSentToModel) {
    }
}
