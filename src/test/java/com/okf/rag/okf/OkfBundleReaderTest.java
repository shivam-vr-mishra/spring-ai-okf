package com.okf.rag.okf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser behaviour, including the permissive-consumption rules of OKF v0.2 §11.
 *
 * <p>No Spring context, no database, no model — the parsing layer is pure.
 */
class OkfBundleReaderTest {

    @TempDir
    Path bundle;

    private Map<String, OkfConcept> read() {
        OkfProperties properties = new OkfProperties(bundle.toString(),
                new OkfProperties.Retrieval(3, 0.6, 1, 8));
        return new OkfBundleReader(properties).read();
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = bundle.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("parses frontmatter, body and bundle-relative path")
    void parsesConcept() throws IOException {
        write("policies/refund.md", """
                ---
                type: policy
                title: Refund Eligibility
                description: When a refund applies.
                tags: [returns, refunds]
                status: stable
                ---

                A refund may be requested within 30 days.
                """);

        OkfConcept concept = read().get("/policies/refund.md");

        assertThat(concept).isNotNull();
        assertThat(concept.type()).isEqualTo("policy");
        assertThat(concept.title()).isEqualTo("Refund Eligibility");
        assertThat(concept.tags()).containsExactly("returns", "refunds");
        assertThat(concept.status()).isEqualTo("stable");
        assertThat(concept.body()).contains("within 30 days");
    }

    @Test
    @DisplayName("status defaults to stable when absent (§5.4)")
    void statusDefaultsToStable() throws IOException {
        write("a.md", "---\ntype: note\n---\nbody text");

        assertThat(read().get("/a.md").status()).isEqualTo("stable");
    }

    @Test
    @DisplayName("skips index.md and log.md — reserved filenames (§8-§9)")
    void skipsReservedFilenames() throws IOException {
        write("index.md", "---\ntype: index\n---\nlisting");
        write("log.md", "---\ntype: log\n---\nhistory");
        write("real.md", "---\ntype: policy\n---\ncontent");

        assertThat(read()).containsOnlyKeys("/real.md");
    }

    @Test
    @DisplayName("skips files with no frontmatter or no type, without throwing (§11)")
    void skipsNonConformantFilesPermissively() throws IOException {
        write("no-frontmatter.md", "# Just a heading\n\nSome text.");
        write("no-type.md", "---\ntitle: Missing its type\n---\nbody");
        write("blank-type.md", "---\ntype: \"\"\n---\nbody");
        write("good.md", "---\ntype: policy\n---\nbody");

        assertThat(read()).containsOnlyKeys("/good.md");
    }

    @Test
    @DisplayName("malformed YAML is skipped rather than failing the whole bundle (§11)")
    void malformedYamlDoesNotBreakTheBundle() throws IOException {
        write("broken.md", "---\ntype: [unclosed\n  nonsense: : :\n---\nbody");
        write("good.md", "---\ntype: policy\n---\nbody");

        assertThat(read()).containsKey("/good.md");
    }

    @Test
    @DisplayName("resolves bundle-relative, ./ and ../ links to canonical paths (§6)")
    void resolvesAllLinkForms() throws IOException {
        write("policies/source.md", """
                ---
                type: policy
                ---
                See [absolute](/procedures/warranty.md),
                [sibling](./refund.md) and
                [parent-relative](../metrics/rate.md).
                """);

        assertThat(read().get("/policies/source.md").links())
                .containsExactly("/procedures/warranty.md", "/policies/refund.md", "/metrics/rate.md");
    }

    @Test
    @DisplayName("ignores external URLs, non-markdown targets, self-links and duplicates")
    void filtersNonConceptLinks() throws IOException {
        write("a.md", """
                ---
                type: policy
                ---
                [external](https://example.com/page)
                [pdf](/uploads/handbook.pdf)
                [self](/a.md)
                [target](/b.md)
                [target again](/b.md)
                [anchored](/b.md#section)
                """);

        assertThat(read().get("/a.md").links()).containsExactly("/b.md");
    }

    @Test
    @DisplayName("derives trust tiers from the verified key (§5.2, §7)")
    void derivesTrustTiers() throws IOException {
        write("none.md", "---\ntype: policy\n---\nbody");
        write("machine.md", """
                ---
                type: policy
                verified:
                  - { by: "ollama/llama3.2:3b", at: "2026-01-01T00:00:00Z" }
                ---
                body
                """);
        write("human.md", """
                ---
                type: policy
                verified:
                  - { by: "ollama/llama3.2:3b", at: "2026-01-01T00:00:00Z" }
                  - { by: "human:smishra", at: "2026-01-02T00:00:00Z" }
                ---
                body
                """);

        Map<String, OkfConcept> concepts = read();
        assertThat(concepts.get("/none.md").trust()).isEqualTo(TrustTier.UNVERIFIED);
        assertThat(concepts.get("/machine.md").trust()).isEqualTo(TrustTier.MACHINE_CONFIRMED);
        assertThat(concepts.get("/human.md").trust()).isEqualTo(TrustTier.HUMAN_REVIEWED);
    }

    @Test
    @DisplayName("a --- rule in the body does not truncate the body")
    void horizontalRuleInBodyIsNotAFrontmatterDelimiter() throws IOException {
        write("a.md", """
                ---
                type: policy
                ---

                First paragraph.

                ---

                Second paragraph after a horizontal rule.
                """);

        assertThat(read().get("/a.md").body()).contains("Second paragraph");
    }

    @Test
    @DisplayName("missing bundle directory yields an empty graph rather than an error")
    void missingBundleDirectoryIsTolerated() {
        OkfProperties properties = new OkfProperties(bundle.resolve("does-not-exist").toString(),
                new OkfProperties.Retrieval(3, 0.6, 1, 8));

        assertThat(new OkfBundleReader(properties).read()).isEmpty();
    }
}
