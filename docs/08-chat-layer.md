# 08 — Chat layer

Two classes: [`ChatConfig`](../src/main/java/com/okf/rag/chat/ChatConfig.java)
wires the clients, [`ChatService`](../src/main/java/com/okf/rag/chat/ChatService.java)
orchestrates calls and extracts citations.

## Two clients, one variable

```java
@Bean ChatClient okfChatClient(...)       // search + link traversal
@Bean ChatClient plainRagChatClient(...)  // search only
```

They differ in **exactly one thing**: how documents are retrieved. Same model,
same system prompt, same `topK`, same threshold. That is what makes `/api/compare`
a controlled experiment rather than a demo.

---

## The system prompt

```java
private static final String SYSTEM_PROMPT = """
        You are the Northwind returns assistant. You answer questions about
        returns, refunds and warranties using the reference material you are given.
        Be concise and concrete: quote the actual numbers, deadlines and conditions.
        """;
```

Short on purpose, and this is the result of a real bug.

**The first version was longer** and included instructions like *"Answer ONLY from
the OKF concepts supplied as context… If the context does not contain the answer,
say so plainly."* Combined with `ContextualQueryAugmenter`'s own built-in rule
saying much the same thing, llama3.2:3b read the stack as permission to refuse
and answered:

> "You don't know. The query is not related to any of the concepts in the
> provided context."

— **while holding five perfectly good concepts.** Retrieval was flawless; the
prompt broke generation.

The lesson generalises: with a small model, *"tell me what to do"* beats *"tell
me what not to do"*, and the same instruction stated twice in two places is worse
than once.

---

## The context template

```java
private static final PromptTemplate OKF_CONTEXT_TEMPLATE = PromptTemplate.builder()
        .template("""
                Reference material — each block is one concept from the knowledge base,
                labelled with its file path:

                ---------------------
                {context}
                ---------------------

                Answer this question using the reference material above: {query}

                Cite the file path of every concept you use, like (/policies/example.md).
                If one concept defines a term that another one relies on, say so —
                that connection is usually the heart of the answer.
                """)
        .build();
```

This **replaces** `ContextualQueryAugmenter`'s default template.

### Required placeholders

`{context}` and `{query}` are not optional — `ContextualQueryAugmenter` validates
their presence via `PromptAssert` and throws at startup if either is missing.
`{context}` receives the formatted documents; `{query}` the user's question.

### Why each line exists

| Line | Purpose |
|---|---|
| "labelled with its file path" | Primes the model to notice the `(concept /path..., type: ...)` line that `asDocument()` injects |
| "Answer this question **using** the reference material" | Positive framing. The default template's "if the answer is not in the context, inform the user you can't answer" is what caused the refusal |
| "Cite the file path of every concept you use" | Citations. The model can only cite paths that appear in its context — which is why `asDocument()` puts the path in the *text*, not just metadata |
| "If one concept defines a term another relies on, say so" | Directly targets the OKF value proposition. `return-shipping.md` says "unless it is a defect return"; `refund-eligibility.md` defines that term. This line asks the model to surface that link |

---

## Chat memory

```java
@Bean
ChatMemory chatMemory() {
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(20)
            .build();
}
```

`MessageWindowChatMemory` keeps a sliding window of the last N messages,
discarding older ones. `InMemoryChatMemoryRepository` is a `ConcurrentHashMap` —
history is lost on restart, which is fine for a POC. Swap for
`JdbcChatMemoryRepository` to persist.

20 messages ≈ 10 exchanges. Worth keeping modest: history competes with retrieved
documents for context window.

Spring AI auto-configures a `ChatMemory` bean too, but autoconfiguration is
`@ConditionalOnMissingBean`, so this explicit bean wins.

---

## The OKF client

```java
@Bean
ChatClient okfChatClient(ChatClient.Builder builder, OkfGraphRetriever retriever, ChatMemory chatMemory) {
    return builder
            .defaultSystem(SYSTEM_PROMPT)
            .defaultAdvisors(
                    MessageChatMemoryAdvisor.builder(chatMemory).build(),
                    RetrievalAugmentationAdvisor.builder()
                            .documentRetriever(retriever)
                            .queryAugmenter(ContextualQueryAugmenter.builder()
                                    .promptTemplate(OKF_CONTEXT_TEMPLATE)
                                    .allowEmptyContext(false)
                                    .build())
                            .build())
            .build();
}
```

### The advisor chain

Advisors are an interceptor chain around the model call. Order matters:

```
request → MessageChatMemoryAdvisor      prepend conversation history
        → RetrievalAugmentationAdvisor  retrieve documents, augment the prompt
        → OllamaChatModel               generate
        ← RetrievalAugmentationAdvisor  attach documents to response context
        ← MessageChatMemoryAdvisor      store the exchange
```

Memory runs first so history is in place before augmentation.

### `documentRetriever(retriever)`

The single integration point. `OkfGraphRetriever` is injected as a plain Spring
bean and handed to Spring AI as a `DocumentRetriever`. Everything in
[Retrieval layer](07-retrieval-layer.md) hangs off this one line.

### `allowEmptyContext(false)`

When retrieval returns nothing, the augmenter uses a dedicated empty-context
template that instructs the model to decline rather than answer from parametric
knowledge. For a policy assistant this is correct: *"I don't have that"* beats a
confident invention.

Set it `true` if you want the model to fall back on general knowledge.

---

## The control group

```java
@Bean
ChatClient plainRagChatClient(ChatClient.Builder builder, VectorStore vectorStore) {
    return builder
            .defaultSystem(SYSTEM_PROMPT)
            .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                    .searchRequest(SearchRequest.builder()
                            .topK(3)
                            .similarityThreshold(0.62)
                            .build())
                    .build())
            .build();
}
```

`QuestionAnswerAdvisor` is Spring AI's one-step RAG: embed the question, search,
append results, generate. **This is where most Spring AI tutorials stop.**

`topK` and `similarityThreshold` are hardcoded to match `okf.retrieval.*`
exactly. If those diverge, the comparison stops being meaningful — a reasonable
refactor is to inject `OkfProperties` here so they cannot drift.

No chat memory on this client: the comparison is single-turn.

---

## `ChatService`

```java
public ChatService(@Qualifier("okfChatClient") ChatClient okfChatClient,
        @Qualifier("plainRagChatClient") ChatClient plainRagChatClient,
        OkfGraphRetriever retriever) {
```

`@Qualifier` is mandatory — two beans of the same type exist, so injection is
ambiguous without it.

### Asking with OKF

```java
public Answer askOkf(String conversationId, String question) {
    ChatClientResponse response = okfChatClient.prompt()
            .user(question)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .call()
            .chatClientResponse();

    return new Answer(text(response), citedPaths(response));
}
```

- **`.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))`** —
  per-request parameter telling the memory advisor which conversation this
  belongs to. The constant is `"chat_memory_conversation_id"`; use the constant,
  not the literal.
- **`.chatClientResponse()`** rather than `.content()` — `content()` returns just
  the string. `ChatClientResponse` carries the **context map**, which is where
  retrieved documents live. That is the whole reason for this call.

### Citations without retrieving twice

```java
@SuppressWarnings("unchecked")
private static List<String> citedPaths(ChatClientResponse response) {
    Object documents = response.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
    if (!(documents instanceof List<?> list)) {
        return List.of();
    }
    return ((List<Document>) list).stream()
            .map(d -> d.getMetadata().get(OkfConcept.MD_PATH))
            .filter(Objects::nonNull)
            .map(Object::toString)
            .distinct()
            .toList();
}
```

**This is the neat part.** `RetrievalAugmentationAdvisor` stashes the documents it
retrieved into the response context under `DOCUMENT_CONTEXT`. Reading them back
means citations reflect **exactly what the model was shown** — not a second
search that might return something slightly different.

The naive alternative (call `retriever.trace()` again to build citations) would
double the embedding calls and could produce citations that don't match the
answer.

The `instanceof List<?>` guard handles the key being absent — which it is on the
plain-RAG path, since `QuestionAnswerAdvisor` uses a different context key.
That is why `askPlain` returns `List.of()` for citations.

### Defensive text extraction

```java
private static String text(ChatClientResponse response) {
    if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
        return "(no response)";
    }
    return response.chatResponse().getResult().getOutput().getText();
}
```

Both `chatResponse()` and `getResult()` can be null — on a filtered response, or
when the empty-context path short-circuits generation entirely. Returning a
marker string beats an NPE in a controller.

### The comparison

```java
public Comparison compare(String question) {
    OkfGraphRetriever.Trace trace = retriever.trace(question);
    Answer plain = askPlain(question);
    Answer okf = askOkf("compare-" + Integer.toHexString(question.hashCode()), question);

    return new Comparison(question, trace.entries(), trace.reachedByLink(), plain, okf);
}
```

Three operations:

1. `retriever.trace()` — the retrieval breakdown, **no LLM**, sub-second
2. `askPlain` — one LLM call
3. `askOkf` — one LLM call

Two LLM calls means roughly double the wait. On CPU-only inference that is
several minutes; see [Troubleshooting](11-troubleshooting.md).

**The conversation ID is derived from the question hash** so repeated comparisons
of the same question share a thread while different questions stay isolated. It
also keeps comparison traffic out of the `"ui"` and `"default"` threads.

> Note that `trace()` here runs retrieval a *third* time (the advisor inside
> `askOkf` runs its own). Acceptable because embedding is cheap relative to
> generation, but it is redundant work — a future version could thread the trace
> through rather than recompute it.

---

## Tuning for answer quality

| Symptom | Likely cause | Fix |
|---|---|---|
| Model refuses despite good context | Conflicting "don't answer" instructions | Simplify the system prompt; use a custom augmenter template |
| Answers ignore later documents | `num-ctx` too small — Ollama defaults to 2048 | Raise `spring.ai.ollama.chat.options.num-ctx` |
| Answers are vague | Too many documents for a small model | Lower `max-documents`, raise `similarity-threshold` |
| No citations | Path not in document *text* | Confirm `asDocument()` emits the `(concept /path...)` line |
| Invented numbers | Relevant concept not retrieved | Check `/api/retrieve` — is it in `reachedByLink`? If not, add the link |

---

Previous: [07 — Retrieval layer](07-retrieval-layer.md) · Next: [09 — Ingestion layer](09-ingestion-layer.md)
