package com.okf.rag.chat;

import java.util.List;

import com.okf.rag.okf.OkfConcept;
import com.okf.rag.retrieval.OkfGraphRetriever;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private final ChatClient okfChatClient;
    private final ChatClient plainRagChatClient;
    private final OkfGraphRetriever retriever;

    public ChatService(@Qualifier("okfChatClient") ChatClient okfChatClient,
            @Qualifier("plainRagChatClient") ChatClient plainRagChatClient,
            OkfGraphRetriever retriever) {
        this.okfChatClient = okfChatClient;
        this.plainRagChatClient = plainRagChatClient;
        this.retriever = retriever;
    }

    /** Conversational answer grounded in the OKF graph. */
    public Answer askOkf(String conversationId, String question) {
        ChatClientResponse response = okfChatClient.prompt()
                .user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .chatClientResponse();

        return new Answer(text(response), citedPaths(response));
    }

    /** Control group: plain similarity-search RAG, no traversal. */
    public Answer askPlain(String question) {
        ChatClientResponse response = plainRagChatClient.prompt()
                .user(question)
                .call()
                .chatClientResponse();

        return new Answer(text(response), List.of());
    }

    /**
     * Runs the same question both ways and returns the retrieval trace alongside.
     * This is the demo: {@code reachedByLink} lists the concepts that only the
     * OKF graph could reach, and the two answers show what difference that made.
     */
    public Comparison compare(String question) {
        OkfGraphRetriever.Trace trace = retriever.trace(question);
        Answer plain = askPlain(question);
        Answer okf = askOkf("compare-" + Integer.toHexString(question.hashCode()), question);

        return new Comparison(question, trace.entries(), trace.reachedByLink(), plain, okf);
    }

    // ---------------------------------------------------------------------

    private static String text(ChatClientResponse response) {
        if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
            return "(no response)";
        }
        return response.chatResponse().getResult().getOutput().getText();
    }

    /**
     * Pulls the documents the advisor actually retrieved out of the response
     * context, so citations reflect the real retrieval rather than a second,
     * possibly different, search.
     */
    @SuppressWarnings("unchecked")
    private static List<String> citedPaths(ChatClientResponse response) {
        Object documents = response.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        if (!(documents instanceof List<?> list)) {
            return List.of();
        }
        return ((List<Document>) list).stream()
                .map(d -> d.getMetadata().get(OkfConcept.MD_PATH))
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .distinct()
                .toList();
    }

    public record Answer(String answer, List<String> citedConcepts) {
    }

    public record Comparison(String question, List<OkfGraphRetriever.Entry> foundBySearch,
            List<String> reachedByLink, Answer plainRag, Answer okfGraph) {
    }
}
