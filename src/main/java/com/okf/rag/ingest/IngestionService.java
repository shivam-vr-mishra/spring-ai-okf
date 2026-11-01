package com.okf.rag.ingest;

import java.util.List;

import com.okf.rag.index.OkfIndexer;
import com.okf.rag.okf.OkfConcept;
import com.okf.rag.okf.OkfGraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * The full write path: upload → chunk → curate into OKF → re-index.
 *
 * <p>Note what is <em>not</em> here: the raw chunks are never written to the
 * vector store. They exist only long enough to be turned into concepts. Only the
 * curated OKF files get embedded, which is what guarantees every retrievable
 * item has a type, a status and a place in the link graph.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final ConceptExtractor extractor;
    private final OkfBundleWriter writer;
    private final OkfIndexer indexer;
    private final OkfGraph graph;

    public IngestionService(ConceptExtractor extractor, OkfBundleWriter writer, OkfIndexer indexer,
            OkfGraph graph) {
        this.extractor = extractor;
        this.writer = writer;
        this.indexer = indexer;
        this.graph = graph;
    }

    public IngestResult ingest(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        log.info("Ingesting {} ({} bytes)", filename, file.getSize());

        // 1. Read — Tika handles PDF, DOCX, PPTX, HTML and plain text.
        List<Document> raw = new TikaDocumentReader(toResource(file, filename)).get();

        // 2. Chunk — defaults are 800 tokens with a 350-character minimum.
        List<Document> chunks = new TokenTextSplitter().apply(raw);
        log.info("{} split into {} chunk(s)", filename, chunks.size());

        // 3. Curate — the step that creates the OKF layer. Existing titles go in so
        //    new concepts can link into the graph rather than sitting beside it.
        List<String> existingTitles = graph.concepts().stream()
                .map(OkfConcept::title)
                .filter(t -> t != null && !t.isBlank())
                .toList();
        List<ConceptDraft> drafts = extractor.extract(chunks, existingTitles);

        // 4. Persist as Markdown, resolving relatedTitles into real links.
        List<String> written = writer.write(drafts, filename);

        // 5. Re-read the bundle and rebuild embeddings.
        OkfIndexer.IndexReport report = indexer.reindex();

        return new IngestResult(filename, chunks.size(), drafts.size(), written, report);
    }

    private static ByteArrayResource toResource(MultipartFile file, String filename) {
        try {
            return new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    // Tika uses the filename as a hint when detecting the media type.
                    return filename;
                }
            };
        }
        catch (Exception e) {
            throw new IllegalArgumentException("Could not read uploaded file " + filename, e);
        }
    }

    public record IngestResult(String filename, int chunks, int conceptsExtracted,
            List<String> conceptsWritten, OkfIndexer.IndexReport index) {
    }
}
