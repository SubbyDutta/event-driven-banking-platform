package backend.backend.chatbot.handlers;

import backend.backend.chatbot.ChatIntent;
import backend.backend.chatbot.ChatResponse;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.security.CustomUserDetails;
import backend.backend.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DirectDataHandler {

    private final AccountService accountService;
    private final LoanApplicationRepository loanApplicationRepository;

    public ChatResponse handle(ChatIntent intent, CustomUserDetails user) {

        Long userId = user.getUser_id();
        String q = intent.getKeyword();

        if (q.contains("balance")) {
            return ChatResponse.direct(
                    "Your balance is ₹" + accountService.getBalance(userId)
            );
        }

        if (
                q.contains("last transaction") ||
                        q.contains("recent transaction") ||
                        q.contains("latest transaction")
        ) {
            return ChatResponse.direct(
                    accountService.getLastNTransactions(userId.intValue(), 3)
                            .stream()
                            .map(tx ->
                                    "Amount: ₹" + tx.getAmount() +
                                            ", From: " + tx.getSenderAccount() +
                                            ", To: " + tx.getReceiverAccount() +
                                            ", Time: " + tx.getTimestamp()
                            )
                            .collect(Collectors.joining("\n"))
            );
        }

        if (q.contains("loan status")) {
            return ChatResponse.direct(
                    String.valueOf(
                            loanApplicationRepository
                                    .findByUsername(user.getUsername())
                                    .get(0)
                    )
            );
        }

        return ChatResponse.blocked();
    }
}
