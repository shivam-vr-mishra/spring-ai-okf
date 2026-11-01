package com.okf.rag.web;

import com.okf.rag.chat.ChatService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** Ask the OKF-grounded chatbot. Pass a stable conversationId for follow-ups. */
    @PostMapping("/chat")
    public ChatService.Answer chat(@RequestBody ChatRequest request) {
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? "default"
                : request.conversationId();
        return chatService.askOkf(conversationId, request.question());
    }

    /** Same question, plain RAG vs OKF graph retrieval, side by side. */
    @PostMapping("/compare")
    public ChatService.Comparison compare(@RequestBody ChatRequest request) {
        return chatService.compare(request.question());
    }

    public record ChatRequest(String question, String conversationId) {
    }
}
