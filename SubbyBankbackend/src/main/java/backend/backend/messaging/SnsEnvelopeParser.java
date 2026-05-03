package backend.backend.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Unwraps the SNS-to-SQS envelope when {@code RawMessageDelivery} is <i>false</i>.
 * The envelope looks like:
 * <pre>{@code
 * {
 *   "Type":      "Notification",
 *   "MessageId": "...",
 *   "TopicArn":  "...",
 *   "Message":   "<original event JSON as a string>",
 *   "MessageAttributes": { ... }
 * }
 * }</pre>
 *
 * <p>All queues in this project use {@code RawMessageDelivery=true} so consumers
 * receive the inner event body directly — this parser only exists for the rare
 * case where a downstream consumer must inspect SNS envelope metadata.
 */
@Component
public class SnsEnvelopeParser {

    private final ObjectMapper objectMapper;

    public SnsEnvelopeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * If {@code body} looks like a SNS envelope, return its inner {@code Message}
     * string; otherwise return {@code body} unchanged (raw delivery case).
     */
    public String unwrap(String body) {
        if (body == null || body.isBlank()) return body;
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode type = root.get("Type");
            JsonNode message = root.get("Message");
            if (type != null && "Notification".equals(type.asText())
                    && message != null && message.isTextual()) {
                return message.asText();
            }
        } catch (Exception ignored) {

        }
        return body;
    }
}
