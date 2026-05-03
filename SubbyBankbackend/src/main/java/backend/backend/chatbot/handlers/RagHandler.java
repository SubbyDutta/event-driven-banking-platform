package backend.backend.chatbot.handlers;

import backend.backend.Dtos.UserResponseDto;
import backend.backend.Exception.ResourceNotFoundException;
import backend.backend.chatbot.ChatIntent;
import backend.backend.chatbot.ChatResponse;
import backend.backend.model.BankAccount;
import backend.backend.model.LoanApplication;
import backend.backend.model.User;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.security.CustomUserDetails;
import backend.backend.service.AccountService;
import backend.backend.service.GeminiService;
import backend.backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RagHandler {

    private final AccountService accountService;
    private final UserService userService;
    private final GeminiService geminiService;
    private final LoanApplicationRepository loanApplicationRepository;

    public ChatResponse handle(ChatIntent intent, CustomUserDetails user) {

        Long userId = user.getUser_id();
        String q = intent.getKeyword();
        String prompt = null;

        BankAccount bankAccount = accountService.accountDetails(user.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        UserResponseDto currentUser = userService.getUserById(userId);
        List<LoanApplication> loanApplications = loanApplicationRepository.findByUsername(user.getUsername());

        User accountOwner = bankAccount.getUser();
        String aadhaarMasked = maskTail(accountOwner != null ? accountOwner.getAadhaarNumber() : null, 8, "XXXXXXXX");
        String panMasked = maskTail(accountOwner != null ? accountOwner.getPanNumber() : null, 7, "XXXXXXX");

        String bankSummary =
                "Account Number: XXXXXXX" + bankAccount.getAccountNumber().substring(7) +
                        " Aadhaar: " + aadhaarMasked +
                        " PAN: " + panMasked +
                        " Type: " + bankAccount.getType() +
                        " Balance: " + bankAccount.getBalance();

        String userSummary =
                "Username: " + currentUser.username() +
                        " Name: " + currentUser.firstname() + " " + currentUser.lastname() +
                        " Credit Score: " + currentUser.creditScore() +
                        " Email: xxxx" + currentUser.email().substring(4) +
                        " Mobile: XXXXXXX" + currentUser.mobile().substring(7);

        if (
                q.contains("account details") ||
                        q.contains("bank account details") ||
                        q.contains("account summary") ||
                        q.contains("bank summary")
        ) {
            prompt = """
You are a banking assistant.
ONLY explain the provided data.
DO NOT add extra assumptions.

BANK ACCOUNT DATA:
%s

Explain this to the user in simple terms.
""".formatted(bankSummary);
        }

        else if (
                q.contains("loan details") ||
                        q.contains("loan summary") ||
                        q.contains("loan information")
        ) {
            prompt = """
You are a banking assistant.
ONLY explain the provided data.
DO NOT add extra assumptions.

LOAN DATA:
%s

Explain this to the user in simple terms.
""".formatted(loanApplications.get(0));
        }

        else if (
                q.contains("my profile") ||
                        q.contains("my details") ||
                        q.contains("user details") ||
                        q.contains("profile summary")
        ) {
            prompt = """
You are a banking assistant.
ONLY explain the provided data.
DO NOT add extra assumptions.

USER DATA:
%s

Explain this to the user in simple terms.
""".formatted(userSummary);
        }

        String explanation = geminiService.chatWithGemini(prompt);
        return ChatResponse.rag(explanation);
    }

    /** Mask all but the tail of a PII value for display; fallback if null/short. */
    private static String maskTail(String value, int tailChars, String fallback) {
        if (value == null || value.length() < tailChars) return fallback;
        return "XXXX" + value.substring(value.length() - tailChars);
    }
}
