package com.okf.rag.okf;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The OKF bundle held in memory as a navigable graph.
 *
 * <p>This is the half of retrieval that is <em>deterministic</em>. The vector
 * store answers "which concept looks like the question"; this class answers
 * "what did the author say is connected to it". Nothing here is probabilistic —
 * traversal follows links a human wrote down, so the same entry concept always
 * expands to the same neighbourhood.
 */
@Component
public class OkfGraph {

    private static final Logger log = LoggerFactory.getLogger(OkfGraph.class);

    private final OkfBundleReader reader;

    /** Replaced wholesale on reload; readers never see a half-built graph. */
    private volatile Map<String, OkfConcept> concepts = Map.of();

    public OkfGraph(OkfBundleReader reader) {
        this.reader = reader;
    }

    @PostConstruct
    public void reload() {
        this.concepts = reader.read();
        logBrokenLinks();
    }

    public Collection<OkfConcept> concepts() {
        return concepts.values();
    }

    public Optional<OkfConcept> concept(String path) {
        return Optional.ofNullable(concepts.get(path));
    }

    public int size() {
        return concepts.size();
    }

    public List<String> linksFrom(String path) {
        OkfConcept concept = concepts.get(path);
        return concept == null ? List.of() : concept.links();
    }

    /**
     * Breadth-first walk outward from a set of entry concepts.
     *
     * @param seeds   bundle-relative paths the vector search landed on
     * @param maxHops how many link hops to follow; 0 disables traversal entirely
     * @return concepts reached by traversal, in discovery order, <em>excluding</em>
     *         the seeds themselves. Deprecated concepts are never returned.
     */
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
                        // §11: a broken cross-link is not an error. Skip and carry on.
                        continue;
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

    /** Link targets that do not resolve to a concept, keyed by the linking file. */
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

    /** Concepts past their author-declared freshness window. */
    public List<String> staleConcepts() {
        Instant now = Instant.now();
        return concepts.values().stream()
                .filter(c -> c.isStale(now))
                .map(OkfConcept::path)
                .toList();
    }

    private void logBrokenLinks() {
        Map<String, List<String>> broken = brokenLinks();
        if (!broken.isEmpty()) {
            // Surfaced as a warning rather than a failure — the spec mandates
            // permissive consumption, but a dangling link is still worth fixing.
            log.warn("OKF bundle has {} concept(s) with unresolved links: {}", broken.size(), broken);
        }
    }
}
