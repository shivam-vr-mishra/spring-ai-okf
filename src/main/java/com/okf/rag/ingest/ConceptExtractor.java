package com.okf.rag.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;

/**
 * Turns raw document chunks into draft OKF concepts using the local model.
 *
 * <p>This is the "curate" step: it converts an opaque wall of PDF text into
 * discrete, typed, linked units of knowledge. The model is explicitly asked for
 * {@code relatedTitles} — those become the Markdown links that later drive graph
 * traversal, so this prompt is where the graph is actually born.
 *
 * <p>The JSON schema is handed to Ollama via its {@code format} option, which
 * constrains decoding at the token level. Prompt-only JSON instructions are not
 * enough for a 3B model: it will happily return {@code concepts: ["some text"]}
 * — an array of strings where objects were asked for.
 */
@Service
public class ConceptExtractor {

    private static final Logger log = LoggerFactory.getLogger(ConceptExtractor.class);

    private static final String PROMPT = """
            Extract the distinct concepts from this excerpt of a company document.

            A concept is one self-contained unit of knowledge: a single policy,
            procedure, system, metric, definition or role. Produce 1 to 3 concepts.
            Do not invent anything that is not in the excerpt.

            For each concept:
              title          - a short noun phrase
              type           - exactly one of: policy, procedure, system, metric,
                               definition, role
              description    - one sentence
              tags           - 2 to 5 short lowercase keywords
              body           - THE FULL TEXT OF THE RULE. Copy the relevant sentences
                               from the excerpt almost word for word. Keep every
                               number, deadline, amount and condition. This must be at
                               least two complete sentences and must NEVER be just the
                               title repeated.
              relatedTitles  - titles of OTHER concepts this one refers to or depends
                               on. You may name a concept you are producing here, or
                               any of the existing concepts listed below. If the text
                               mentions an existing concept by name, you MUST list it.
                               Use [] only if there are genuinely none. Never invent a
                               title that is not in your own list or the existing list.

            Existing concepts already in the knowledge base, available to link to:
            {existingTitles}

            Worked example. For the excerpt
            "Expenses over $500 need director approval. Approval must be recorded in
            the finance portal before the expense is incurred."
            a correct concept is:
              title: "Expense Approval Threshold"
              type: "policy"
              body: "Expenses over $500 need director approval. Approval must be
                     recorded in the finance portal before the expense is incurred."
            Note the body carries the actual rule and the $500 figure, not the title.

            Excerpt:
            ---
            {excerpt}
            ---
            """;

    /** Bounded so a large upload can't fan out into hundreds of model calls. */
    private static final int MAX_CHUNKS = 12;

    private final ChatClient chatClient;
    private final BeanOutputConverter<ConceptDraftList> converter;
    private final Map<String, Object> schema;

    public ConceptExtractor(ChatModel chatModel) {
        // A bare client: no RAG advisor, no chat memory. Extraction must look only
        // at the excerpt in front of it.
        this.chatClient = ChatClient.builder(chatModel).build();
        this.converter = new BeanOutputConverter<>(ConceptDraftList.class);
        this.schema = this.converter.getJsonSchemaMap();
    }

    /**
     * A fresh options builder per call.
     *
     * <p>Built with statements rather than a fluent chain: {@code format} and
     * friends are declared on the generic {@code AbstractBuilder} and return it
     * raw, which breaks the type inference {@code ChatClient.options(..)} needs.
     */
    private OllamaChatOptions.Builder optionsBuilder() {
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder();
        builder.format(this.schema);   // Ollama structured output — enforced, not requested
        builder.temperature(0.0);      // extraction is not a creative task
        // One 800-token chunk plus the prompt and the existing-title list fits
        // comfortably. Matches the chat path so both share one KV cache size.
        builder.numCtx(4096);
        return builder;
    }

    /**
     * @param existingTitles titles already in the bundle. Supplying these is what
     *                       lets a newly ingested concept link into the existing
     *                       graph instead of becoming an isolated island — without
     *                       them the model has no vocabulary to reference.
     */
    public List<ConceptDraft> extract(List<Document> chunks, List<String> existingTitles) {
        String titleList = existingTitles.isEmpty()
                ? "(none yet)"
                : existingTitles.stream().map(t -> "  - " + t).collect(java.util.stream.Collectors.joining("\n"));

        List<Document> considered = chunks;
        if (chunks.size() > MAX_CHUNKS) {
            log.warn("Upload produced {} chunks; only the first {} will be read for concepts. "
                    + "The remaining {} are NOT in the knowledge base.",
                    chunks.size(), MAX_CHUNKS, chunks.size() - MAX_CHUNKS);
            considered = chunks.subList(0, MAX_CHUNKS);
        }

        List<ConceptDraft> drafts = new ArrayList<>();
        for (int i = 0; i < considered.size(); i++) {
            Document chunk = considered.get(i);
            try {
                ConceptDraftList result = chatClient.prompt()
                        .user(u -> u.text(PROMPT)
                                .param("excerpt", chunk.getText())
                                .param("existingTitles", titleList))
                        .options(optionsBuilder())
                        .call()
                        .entity(this.converter);

                if (result == null || result.concepts() == null) {
                    log.warn("Chunk {}/{} produced no parseable concepts", i + 1, considered.size());
                    continue;
                }
                List<ConceptDraft> usable = result.concepts().stream().filter(ConceptDraft::isUsable).toList();
                drafts.addAll(usable);
                int rejected = result.concepts().size() - usable.size();
                if (rejected > 0) {
                    log.warn("Chunk {}/{}: discarded {} concept(s) whose body was empty or "
                            + "merely restated the title", i + 1, considered.size(), rejected);
                }
                log.debug("Chunk {}/{} yielded {} usable concept(s)", i + 1, considered.size(), usable.size());
            }
            catch (Exception e) {
                // Even with a constrained schema a small model can time out or emit
                // something unusable. One bad chunk shouldn't abort the whole upload.
                log.warn("Chunk {}/{} could not be converted to concepts: {}", i + 1, considered.size(),
                        e.toString());
            }
        }
        log.info("Extracted {} draft concept(s) from {} chunk(s)", drafts.size(), considered.size());
        return drafts;
    }

    /** Wrapper so the model returns an object rather than a bare array. */
    public record ConceptDraftList(List<ConceptDraft> concepts) {
    }
}
