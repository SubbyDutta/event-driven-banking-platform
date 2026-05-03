package backend.backend.chatbot;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ChatIntent {
    private IntentType type;
    private String keyword;
}
