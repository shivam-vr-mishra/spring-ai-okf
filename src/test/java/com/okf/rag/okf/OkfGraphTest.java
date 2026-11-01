package com.okf.rag.okf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Traversal behaviour — the half of retrieval that is deterministic.
 *
 * <p>The bundle below mirrors the shape of the real one: two policies that link
 * to each other (a cycle), a procedure reachable only by link, and a deprecated
 * concept that must never be returned.
 */
class OkfGraphTest {

    @TempDir
    Path bundle;

    private OkfGraph graph;

    @BeforeEach
    void buildBundle() throws IOException {
        write("policies/shipping.md", """
                ---
                type: policy
                title: Return Shipping
                ---
                Customer pays unless it is a defect return.

                - [Refund Eligibility](/policies/refund.md)
                - [Warranty Claim](/procedures/warranty.md)
                """);

        write("policies/refund.md", """
                ---
                type: policy
                title: Refund Eligibility
                ---
                Defines a defect return.

                - [Return Shipping](/policies/shipping.md)
                - [Restocking Fee](/policies/restocking.md)
                """);

        // Only reachable from shipping.md — never by similarity in the real system.
        write("procedures/warranty.md", """
                ---
                type: procedure
                title: Warranty Claim
                ---
                Raise an RMA. Inspection within 5 business days.

                - [Order Management](/systems/oms.md)
                """);

        write("systems/oms.md", """
                ---
                type: system
                title: Order Management
                ---
                System of record.
                """);

        write("policies/restocking.md", """
                ---
                type: policy
                title: Restocking Fee
                status: deprecated
                stale_after: "2020-01-01T00:00:00Z"
                ---
                Withdrawn 15% fee.
                """);

        write("policies/dangling.md", """
                ---
                type: policy
                title: Dangling
                ---
                - [Nowhere](/policies/does-not-exist.md)
                """);

        OkfProperties properties = new OkfProperties(bundle.toString(),
                new OkfProperties.Retrieval(3, 0.6, 1, 8));
        graph = new OkfGraph(new OkfBundleReader(properties));
        graph.reload();
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = bundle.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("one hop reaches the linked concept and excludes the seeds themselves")
    void oneHopExcludesSeeds() {
        List<String> reached = graph.traverse(List.of("/policies/shipping.md"), 1).stream()
                .map(OkfConcept::path).toList();

        assertThat(reached)
                .containsExactlyInAnyOrder("/policies/refund.md", "/procedures/warranty.md")
                .doesNotContain("/policies/shipping.md");
    }

    @Test
    @DisplayName("never returns a deprecated concept even when something links to it")
    void skipsDeprecatedNeighbours() {
        List<String> reached = graph.traverse(List.of("/policies/refund.md"), 1).stream()
                .map(OkfConcept::path).toList();

        assertThat(reached)
                .contains("/policies/shipping.md")
                .doesNotContain("/policies/restocking.md");
    }

    @Test
    @DisplayName("mutual links do not cause infinite traversal or duplicates")
    void handlesCycles() {
        List<OkfConcept> reached = graph.traverse(List.of("/policies/shipping.md"), 5);

        assertThat(reached).extracting(OkfConcept::path).doesNotHaveDuplicates();
        assertThat(reached).extracting(OkfConcept::path).doesNotContain("/policies/shipping.md");
    }

    @Test
    @DisplayName("maxHops bounds the walk; 0 disables traversal entirely")
    void respectsHopLimit() {
        // oms.md is two hops away: shipping -> warranty -> oms
        List<String> oneHop = graph.traverse(List.of("/policies/shipping.md"), 1).stream()
                .map(OkfConcept::path).toList();
        List<String> twoHops = graph.traverse(List.of("/policies/shipping.md"), 2).stream()
                .map(OkfConcept::path).toList();

        assertThat(oneHop).doesNotContain("/systems/oms.md");
        assertThat(twoHops).contains("/systems/oms.md");
        assertThat(graph.traverse(List.of("/policies/shipping.md"), 0)).isEmpty();
    }

    @Test
    @DisplayName("a broken cross-link is skipped, not an error (§11)")
    void brokenLinkDoesNotThrow() {
        assertThat(graph.traverse(List.of("/policies/dangling.md"), 1)).isEmpty();
        assertThat(graph.brokenLinks())
                .containsEntry("/policies/dangling.md", List.of("/policies/does-not-exist.md"));
    }

    @Test
    @DisplayName("an unknown seed path yields no neighbours rather than failing")
    void unknownSeedIsTolerated() {
        assertThat(graph.traverse(List.of("/nope.md"), 1)).isEmpty();
    }

    @Test
    @DisplayName("reports concepts past their stale_after window")
    void reportsStaleConcepts() {
        assertThat(graph.staleConcepts()).containsExactly("/policies/restocking.md");
    }
}
