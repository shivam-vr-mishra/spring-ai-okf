package com.okf.rag.chat;

import com.okf.rag.retrieval.OkfGraphRetriever;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two chat clients, deliberately. They differ in exactly one thing — how
 * documents are retrieved — so the POC can run the same question through both
 * and show what the OKF link graph adds.
 */
@Configuration
public class ChatConfig {

    private static final String SYSTEM_PROMPT = """
            You are the Northwind returns assistant. You answer questions about
            returns, refunds and warranties using the reference material you are given.
            Be concise and concrete: quote the actual numbers, deadlines and conditions.
            """;

    /**
     * Replaces {@code ContextualQueryAugmenter}'s default template.
     *
     * <p>The stock template carries its own "if the answer is not in the context,
     * tell the user you can't answer" rule. Stacked on top of a system prompt
     * saying much the same thing, a 3B model reads the combination as permission
     * to refuse and does exactly that — even with good context in front of it.
     * This version states the task once, positively.
     */
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

    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                // History competes with retrieved concepts for the context window.
                // At num-ctx 4096 with ~1600 tokens of concepts, 10 messages is the
                // most that leaves room to generate. Raise both together or neither.
                .maxMessages(10)
                .build();
    }

    /**
     * The OKF client: vector search lands on entry concepts, then authored links
     * are followed to pull in whatever the author said was related.
     */
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
                                        // Refuse rather than hallucinate when nothing is retrieved.
                                        .allowEmptyContext(false)
                                        .build())
                                .build())
                .build();
    }

    /**
     * The control group: ordinary similarity-search RAG with no graph traversal.
     * This is what most Spring AI tutorials stop at.
     */
    @Bean
    ChatClient plainRagChatClient(ChatClient.Builder builder, VectorStore vectorStore) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                // Same topK and threshold as the OKF client, so the only variable
                // between the two is whether links are followed.
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.builder()
                                .topK(3)
                                .similarityThreshold(0.62)
                                .build())
                        .build())
                .build();
    }
}
