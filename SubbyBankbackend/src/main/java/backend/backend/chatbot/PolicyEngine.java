package backend.backend.chatbot;

import backend.backend.security.CustomUserDetails;
import org.springframework.stereotype.Component;

@Component
public class PolicyEngine {

    public void validate(ChatIntent intent, CustomUserDetails user) {

        if (intent.getType() == IntentType.DISALLOWED) {
            throw new RuntimeException("Query not allowed");
        }

        if (user == null) {
            throw new RuntimeException("Unauthorized");
        }
    }
}
