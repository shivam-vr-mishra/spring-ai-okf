package com.okf.rag.okf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads an OKF bundle off disk into {@link OkfConcept} objects.
 *
 * <p>Parsing is deliberately permissive. OKF v0.2 §11 requires that consumers
 * <em>must not</em> reject a bundle because of missing optional fields, unknown
 * {@code type} values, unknown frontmatter keys, broken cross-links or a missing
 * {@code index.md}. So anything unparseable is logged and skipped; nothing here
 * throws on malformed input.
 */
@Component
public class OkfBundleReader {

    private static final Logger log = LoggerFactory.getLogger(OkfBundleReader.class);

    /** Reserved filenames per §8–§9 — never concept documents. */
    private static final Set<String> RESERVED = Set.of("index.md", "log.md");

    private static final String DELIMITER = "---";

    /** Matches a Markdown inline link and captures its target. */
    private static final Pattern LINK = Pattern.compile("\\[[^\\]]*]\\(([^)\\s]+)\\)");

    private final OkfProperties properties;

    public OkfBundleReader(OkfProperties properties) {
        this.properties = properties;
    }

    public Path bundleRoot() {
        return Paths.get(properties.bundlePath()).toAbsolutePath().normalize();
    }

    /**
     * Scans the bundle and returns every conformant concept, keyed by its
     * bundle-relative path. Insertion order is sorted so startup logging and the
     * debug endpoints are stable between runs.
     */
    public Map<String, OkfConcept> read() {
        Path root = bundleRoot();
        if (!Files.isDirectory(root)) {
            log.warn("OKF bundle directory not found at {} — starting with an empty graph", root);
            return Map.of();
        }

        Map<String, OkfConcept> concepts = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !RESERVED.contains(p.getFileName().toString().toLowerCase()))
                    .sorted()
                    .forEach(file -> parse(root, file).ifPresent(c -> concepts.put(c.path(), c)));
        }
        catch (IOException e) {
            throw new UncheckedIOException("Unable to walk OKF bundle at " + root, e);
        }

        log.info("Loaded {} OKF concepts from {}", concepts.size(), root);
        return concepts;
    }

    // ---------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------

    private java.util.Optional<OkfConcept> parse(Path root, Path file) {
        String relative = "/" + root.relativize(file).toString().replace('\\', '/');
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int[] bounds = frontmatterBounds(content);
            if (bounds == null) {
                log.warn("Skipping {} — no YAML frontmatter block (§11 requires one)", relative);
                return java.util.Optional.empty();
            }

            Map<String, Object> fm = loadYaml(content.substring(bounds[0], bounds[1]), relative);
            String body = content.substring(bounds[2]);

            String type = str(fm.get("type"));
            if (type == null || type.isBlank()) {
                log.warn("Skipping {} — frontmatter has no non-empty 'type' (§11)", relative);
                return java.util.Optional.empty();
            }

            String status = str(fm.get("status"));
            return java.util.Optional.of(new OkfConcept(
                    relative,
                    type,
                    str(fm.get("title")),
                    str(fm.get("description")),
                    stringList(fm.get("tags")),
                    status == null || status.isBlank() ? "stable" : status, // §5.4 default
                    instant(fm.get("stale_after")),
                    str(nested(fm.get("generated"), "by")),
                    TrustTier.from(fm.get("verified")),
                    extractLinks(relative, body),
                    body));
        }
        catch (Exception e) {
            log.warn("Skipping {} — could not be parsed: {}", relative, e.toString());
            return java.util.Optional.empty();
        }
    }

    /**
     * Locates the leading {@code ---} delimited YAML block.
     *
     * @return {@code [yamlStart, yamlEnd, bodyStart]}, or {@code null} if absent
     */
    private static int[] frontmatterBounds(String content) {
        String text = content.stripLeading();
        int offset = content.length() - text.length();
        if (!text.startsWith(DELIMITER)) {
            return null;
        }
        int yamlStart = offset + DELIMITER.length();
        // Find a line consisting solely of "---", which closes the block. The body
        // may contain its own horizontal rules; only the first match is the closer.
        Matcher closer = Pattern.compile("(?m)^---\\s*$").matcher(content);
        if (!closer.find(yamlStart)) {
            return null;
        }
        return new int[] { yamlStart, closer.start(), closer.end() };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String yaml, String path) {
        // SafeConstructor restricts the parse to standard scalars and collections,
        // so a bundle authored by an agent cannot instantiate arbitrary classes.
        Yaml parser = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object loaded = parser.load(yaml);
        if (loaded instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        log.warn("Frontmatter of {} is not a YAML mapping — treating as empty", path);
        return Map.of();
    }

    /**
     * Pulls every link to another concept out of the body and resolves it to a
     * bundle-relative path. External URLs and non-Markdown targets are ignored —
     * they are valid OKF but are not traversable concepts.
     */
    private static List<String> extractLinks(String conceptPath, String body) {
        List<String> links = new ArrayList<>();
        Matcher matcher = LINK.matcher(body);
        while (matcher.find()) {
            String href = matcher.group(1);
            if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) {
                continue;
            }
            int fragment = href.indexOf('#');
            if (fragment >= 0) {
                href = href.substring(0, fragment);
            }
            if (!href.endsWith(".md")) {
                continue;
            }
            String resolved = resolve(conceptPath, href);
            if (!resolved.equals(conceptPath) && !links.contains(resolved)) {
                links.add(resolved);
            }
        }
        return Collections.unmodifiableList(links);
    }

    /**
     * Resolves a link target against the linking concept. OKF §6 allows
     * bundle-relative ({@code /a/b.md}) and relative ({@code ./b.md},
     * {@code ../c/b.md}) forms; both normalise to a bundle-relative path here.
     */
    private static String resolve(String conceptPath, String href) {
        String base;
        if (href.startsWith("/")) {
            base = href;
        }
        else {
            int slash = conceptPath.lastIndexOf('/');
            String parent = slash <= 0 ? "" : conceptPath.substring(0, slash);
            base = parent + "/" + href;
        }
        return "/" + Paths.get(base).normalize().toString()
                .replace('\\', '/')
                .replaceFirst("^/+", "");
    }

    // ---------------------------------------------------------------------
    // Lenient coercion of frontmatter values
    // ---------------------------------------------------------------------

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Object value, String key) {
        return value instanceof Map<?, ?> map ? ((Map<String, Object>) map).get(key) : null;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        }
        if (value instanceof String s && !s.isBlank()) {
            // Tolerate `tags: a, b` as well as a proper YAML sequence.
            return Stream.of(s.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
        }
        return List.of();
    }

    /** SnakeYAML resolves bare ISO timestamps to {@link Date}, quoted ones to String. */
    private static Instant instant(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Instant.parse(s.trim());
            }
            catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
