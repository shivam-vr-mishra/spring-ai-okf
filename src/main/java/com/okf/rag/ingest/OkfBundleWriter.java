package com.okf.rag.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.okf.rag.okf.OkfBundleReader;
import com.okf.rag.okf.OkfConcept;
import com.okf.rag.okf.OkfGraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Serialises draft concepts into OKF v0.2 conformant Markdown files.
 *
 * <p>The output is ordinary Markdown in git — reviewable in a pull request and
 * editable by hand. That is the point: a wrong answer gets fixed by correcting a
 * file, not by re-running an ingestion pipeline.
 */
@Service
public class OkfBundleWriter {

    private static final Logger log = LoggerFactory.getLogger(OkfBundleWriter.class);

    /** Maps a concept type onto its directory, mirroring the hand-authored layout. */
    private static final Map<String, String> TYPE_DIRECTORIES = Map.of(
            "policy", "policies",
            "procedure", "procedures",
            "system", "systems",
            "metric", "metrics",
            "definition", "definitions",
            "role", "roles");

    private final OkfBundleReader reader;
    private final OkfGraph graph;
    private final String generatorActor;

    public OkfBundleWriter(OkfBundleReader reader, OkfGraph graph,
            @Value("${spring.ai.ollama.chat.model:unknown}") String chatModel) {
        this.reader = reader;
        this.graph = graph;
        // OKF §7 actor convention for agents: <producer>/<version>.
        this.generatorActor = "ollama/" + chatModel;
    }

    /**
     * Writes every usable draft to disk.
     *
     * @param sourceName original filename of the upload, recorded in {@code sources}
     * @return bundle-relative paths of the files written
     */
    public List<String> write(List<ConceptDraft> drafts, String sourceName) {
        Instant now = Instant.now();
        Path root = reader.bundleRoot();

        // Title -> path, covering concepts already in the bundle plus the drafts in
        // this batch, so relatedTitles can be resolved into real links.
        Map<String, String> titleIndex = new HashMap<>();
        for (OkfConcept existing : graph.concepts()) {
            if (existing.title() != null) {
                titleIndex.put(normalise(existing.title()), existing.path());
            }
        }
        for (ConceptDraft draft : drafts) {
            if (draft.isUsable()) {
                titleIndex.put(normalise(draft.title()), pathFor(draft));
            }
        }

        List<String> written = new ArrayList<>();
        for (ConceptDraft draft : drafts) {
            if (!draft.isUsable()) {
                continue;
            }
            String relative = pathFor(draft);
            Path file = root.resolve(relative.substring(1));
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, render(draft, titleIndex, sourceName, now), StandardCharsets.UTF_8);
                written.add(relative);
                log.info("Wrote draft concept {}", relative);
            }
            catch (IOException e) {
                throw new UncheckedIOException("Could not write concept " + relative, e);
            }
        }
        return written;
    }

    // ---------------------------------------------------------------------

    private String render(ConceptDraft draft, Map<String, String> titleIndex, String sourceName, Instant now) {
        StringBuilder out = new StringBuilder();
        out.append("---\n");
        out.append("type: ").append(yaml(draft.type().toLowerCase(Locale.ROOT))).append('\n');
        out.append("title: ").append(yaml(draft.title())).append('\n');
        if (draft.description() != null && !draft.description().isBlank()) {
            out.append("description: ").append(yaml(draft.description())).append('\n');
        }
        if (!draft.safeTags().isEmpty()) {
            out.append("tags: [").append(String.join(", ",
                    draft.safeTags().stream().map(OkfBundleWriter::yaml).toList())).append("]\n");
        }
        // Machine-authored and not yet reviewed, so: draft + no verified key.
        out.append("status: draft\n");
        out.append("generated: { by: ").append(yaml(generatorActor))
                .append(", at: ").append(yaml(now.toString())).append(" }\n");
        out.append("sources:\n");
        out.append("  - id: ").append(yaml(slug(sourceName))).append('\n');
        out.append("    resource: ").append(yaml("/uploads/" + sourceName)).append('\n');
        out.append("    title: ").append(yaml(sourceName)).append('\n');
        out.append("---\n\n");

        out.append("# ").append(draft.title()).append("\n\n");
        out.append(draft.body().strip()).append('\n');

        List<String> links = new ArrayList<>();
        String self = pathFor(draft);
        for (String related : draft.safeRelatedTitles()) {
            String target = titleIndex.get(normalise(related));
            if (target != null && !target.equals(self) && !links.contains(target)) {
                links.add("- [" + related + "](" + target + ")");
            }
        }
        if (!links.isEmpty()) {
            out.append("\n## Related Concepts\n\n");
            links.forEach(l -> out.append(l).append('\n'));
        }
        return out.toString();
    }

    private static String pathFor(ConceptDraft draft) {
        String directory = TYPE_DIRECTORIES.getOrDefault(draft.type().toLowerCase(Locale.ROOT), "concepts");
        return "/" + directory + "/" + slug(draft.title()) + ".md";
    }

    private static String slug(String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("\\.[a-z0-9]{1,5}$", "")   // drop a file extension
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "concept" : slug;
    }

    private static String normalise(String title) {
        return title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    /** Always quote, so colons and '#' in model output can't corrupt the frontmatter. */
    private static String yaml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}
