package backend.backend.chatbot;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ChatResponse {

    private String type;
    private String message;

    public static ChatResponse blocked() {
        return new ChatResponse("BLOCKED", "This request is not permitted.");
    }

    public static ChatResponse error(String msg) {
        return new ChatResponse("ERROR", msg);
    }

    public static ChatResponse direct(String msg) {
        return new ChatResponse("DIRECT", msg);
    }

    public static ChatResponse rag(String msg) {
        return new ChatResponse("RAG", msg);
    }

    public static ChatResponse generative(String msg) {
        return new ChatResponse("GEN", msg);
    }
}
