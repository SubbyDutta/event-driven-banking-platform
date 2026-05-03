package backend.backend.controller;

import backend.backend.model.EventAuditLog;
import backend.backend.repository.EventAuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.ListQueuesResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Admin-only endpoints for inspecting, replaying, and discarding messages from
 * dead-letter queues. Every action is written to {@code event_audit_log} with
 * the acting admin's username.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    /api/admin/dlq}                                  list DLQs + depth</li>
 *   <li>{@code GET    /api/admin/dlq/{queueName}?limit=N}              peek messages</li>
 *   <li>{@code POST   /api/admin/dlq/{queueName}/replay?messageId=X}   replay one message</li>
 *   <li>{@code POST   /api/admin/dlq/{queueName}/replay-all?limit=N}   batch replay</li>
 *   <li>{@code DELETE /api/admin/dlq/{queueName}/{receiptHandle}}      discard</li>
 * </ul>
 *
 * <p>Note: SQS returns an opaque per-receive {@code ReceiptHandle}, not a stable
 * message id across receives. For replay / delete, pass the {@code messageId}
 * the peek endpoint returned — the controller re-receives with VisibilityTimeout=0
 * to resolve the current handle.
 */
@RestController
@RequestMapping("/api/admin/dlq")
@PreAuthorize("hasRole('ADMIN')")
public class DlqAdminController {

    private static final Logger log = LoggerFactory.getLogger(DlqAdminController.class);
    private static final long SQS_TIMEOUT_SECONDS = 10;

    private final SqsAsyncClient sqsClient;
    private final EventAuditLogRepository auditRepository;
    private final ObjectMapper objectMapper;

    public DlqAdminController(SqsAsyncClient sqsClient,
                              EventAuditLogRepository auditRepository,
                              ObjectMapper objectMapper) {
        this.sqsClient = sqsClient;
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listDlqs() throws Exception {
        ListQueuesResponse response = await(sqsClient.listQueues(b -> b.queueNamePrefix("")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String url : response.queueUrls()) {
            String name = url.substring(url.lastIndexOf('/') + 1);
            if (!name.endsWith("-dlq")) continue;
            Map<QueueAttributeName, String> attrs = await(sqsClient.getQueueAttributes(
                    GetQueueAttributesRequest.builder()
                            .queueUrl(url)
                            .attributeNames(
                                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
                            .build()
            )).attributes();
            Map<String, Object> row = new HashMap<>();
            row.put("queueName", name);
            row.put("queueUrl", url);
            row.put("messagesAvailable", attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
            row.put("messagesInFlight", attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{queueName}")
    public ResponseEntity<List<Map<String, Object>>> peek(
            @PathVariable String queueName,
            @RequestParam(defaultValue = "10") int limit,
            Authentication authentication) throws Exception {
        requireDlq(queueName);
        int capped = Math.min(Math.max(limit, 1), 10);
        String url = queueUrl(queueName);

        ReceiveMessageResponse response = await(sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                        .queueUrl(url)
                        .maxNumberOfMessages(capped)
                        .visibilityTimeout(0)
                        .messageAttributeNames("All")
                        .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                        .build()
        ));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Message m : response.messages()) {
            Map<String, Object> row = new HashMap<>();
            row.put("messageId", m.messageId());
            row.put("receiptHandle", m.receiptHandle());
            row.put("body", m.body());
            row.put("attributes", m.messageAttributes());
            row.put("approxReceiveCount",
                    m.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT));
            out.add(row);
        }
        audit(authentication, "dlq.peek", queueName, Map.of("count", out.size(), "limit", capped));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{queueName}/replay")
    public ResponseEntity<Map<String, Object>> replayOne(
            @PathVariable String queueName,
            @RequestParam String messageId,
            Authentication authentication) throws Exception {
        requireDlq(queueName);
        String sourceUrl = queueUrl(deriveSourceQueueName(queueName));
        String dlqUrl = queueUrl(queueName);

        Optional<Message> found = findMessage(dlqUrl, messageId);
        if (found.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "not_found", "messageId", messageId));
        }
        Message m = found.get();

        await(sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(sourceUrl)
                .messageBody(m.body())
                .messageAttributes(m.messageAttributes())
                .build()));
        await(sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(dlqUrl)
                .receiptHandle(m.receiptHandle())
                .build()));

        audit(authentication, "dlq.replay", queueName,
                Map.of("messageId", messageId, "sourceQueue", deriveSourceQueueName(queueName)));
        log.info("dlq.replay admin={} queue={} messageId={}",
                actor(authentication), queueName, messageId);
        return ResponseEntity.ok(Map.of("status", "replayed", "messageId", messageId));
    }

    @PostMapping("/{queueName}/replay-all")
    public ResponseEntity<Map<String, Object>> replayAll(
            @PathVariable String queueName,
            @RequestParam(defaultValue = "100") int limit,
            Authentication authentication) throws Exception {
        requireDlq(queueName);
        String sourceUrl = queueUrl(deriveSourceQueueName(queueName));
        String dlqUrl = queueUrl(queueName);

        int replayed = 0;
        int remaining = Math.min(Math.max(limit, 1), 1000);
        while (remaining > 0) {
            ReceiveMessageResponse response = await(sqsClient.receiveMessage(
                    ReceiveMessageRequest.builder()
                            .queueUrl(dlqUrl)
                            .maxNumberOfMessages(Math.min(10, remaining))
                            .visibilityTimeout(30)
                            .messageAttributeNames("All")
                            .build()
            ));
            if (response.messages().isEmpty()) break;
            for (Message m : response.messages()) {
                await(sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(sourceUrl)
                        .messageBody(m.body())
                        .messageAttributes(m.messageAttributes())
                        .build()));
                await(sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(dlqUrl)
                        .receiptHandle(m.receiptHandle())
                        .build()));
                replayed++;
                remaining--;
            }
        }

        audit(authentication, "dlq.replay_all", queueName, Map.of("replayed", replayed));
        log.info("dlq.replay_all admin={} queue={} replayed={}",
                actor(authentication), queueName, replayed);
        return ResponseEntity.ok(Map.of("status", "ok", "replayed", replayed));
    }

    @DeleteMapping("/{queueName}/{messageId}")
    public ResponseEntity<Map<String, Object>> discard(
            @PathVariable String queueName,
            @PathVariable String messageId,
            Authentication authentication) throws Exception {
        requireDlq(queueName);
        String dlqUrl = queueUrl(queueName);
        Optional<Message> found = findMessage(dlqUrl, messageId);
        if (found.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "not_found", "messageId", messageId));
        }
        await(sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(dlqUrl)
                .receiptHandle(found.get().receiptHandle())
                .build()));
        audit(authentication, "dlq.discard", queueName, Map.of("messageId", messageId));
        log.warn("dlq.discard admin={} queue={} messageId={}",
                actor(authentication), queueName, messageId);
        return ResponseEntity.ok(Map.of("status", "discarded", "messageId", messageId));
    }

    private Optional<Message> findMessage(String queueUrl, String messageId) throws Exception {

        for (int i = 0; i < 10; i++) {
            ReceiveMessageResponse response = await(sqsClient.receiveMessage(
                    ReceiveMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .maxNumberOfMessages(10)
                            .visibilityTimeout(30)
                            .messageAttributeNames("All")
                            .build()
            ));
            if (response.messages().isEmpty()) break;
            for (Message m : response.messages()) {
                if (messageId.equals(m.messageId())) return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    private String queueUrl(String queueName) throws Exception {
        return await(sqsClient.getQueueUrl(b -> b.queueName(queueName))).queueUrl();
    }

    private static String deriveSourceQueueName(String dlqName) {
        if (!dlqName.endsWith("-dlq")) {
            throw new IllegalArgumentException("queue is not a DLQ: " + dlqName);
        }
        return dlqName.substring(0, dlqName.length() - "-dlq".length());
    }

    private static void requireDlq(String queueName) {
        if (!queueName.endsWith("-dlq")) {
            throw new IllegalArgumentException("admin DLQ endpoints only operate on *-dlq queues");
        }
    }

    private <T> T await(java.util.concurrent.CompletableFuture<T> fut)
            throws InterruptedException, ExecutionException, TimeoutException {
        return fut.get(SQS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void audit(Authentication authentication, String action, String queueName, Map<String, Object> extra) {
        EventAuditLog row = new EventAuditLog();
        row.setEventType(action);
        row.setAggregateType("dlq");
        row.setAggregateId(queueName);
        row.setActor(actor(authentication));
        try {
            row.setDetails(objectMapper.writeValueAsString(extra));
        } catch (JsonProcessingException e) {
            row.setDetails("{}");
        }
        auditRepository.save(row);
    }

    private static String actor(Authentication authentication) {
        return authentication != null ? authentication.getName() : "anonymous";
    }
}
