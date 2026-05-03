package backend.backend.controller;

import backend.backend.security.CustomUserDetails;
import backend.backend.chatbot.ChatIntent;
import backend.backend.chatbot.ChatResponse;
import backend.backend.chatbot.IntentDetector;
import backend.backend.chatbot.PolicyEngine;
import backend.backend.chatbot.handlers.DirectDataHandler;
import backend.backend.chatbot.handlers.GenerativeHandler;
import backend.backend.chatbot.handlers.RagHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chatbot")
@RequiredArgsConstructor
public class  ChatbotController {

    private final IntentDetector intentDetector;
    private final PolicyEngine policyEngine;
    private final DirectDataHandler directDataHandler;
    private final RagHandler ragHandler;
    private final GenerativeHandler generativeHandler;

    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<ChatResponse> chat(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestBody Map<String, String> body
    ) {
        String query = body.get("query");

        if (query == null || query.trim().isEmpty() || query.length() > 500) {
            return ResponseEntity.badRequest()
                    .body(ChatResponse.error("Invalid query"));
        }

        ChatIntent intent = intentDetector.detect(query);

        policyEngine.validate(intent, user);

        ChatResponse response = switch (intent.getType()) {

            case DIRECT_DATA ->
                    directDataHandler.handle(intent, user);

            case ANALYTICAL ->
                    ragHandler.handle(intent, user);

            case PROCEDURAL ->
                    generativeHandler.handle(intent);

            default ->
                    ChatResponse.blocked();
        };

        return ResponseEntity.ok(response);
    }
}
