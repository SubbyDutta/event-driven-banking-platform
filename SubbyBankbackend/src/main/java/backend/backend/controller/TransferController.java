package backend.backend.controller;

import backend.backend.model.Transaction;
import backend.backend.requests_response.TransferRequest;
import backend.backend.security.CustomUserDetails;
import backend.backend.service.BankService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfer")
@RequiredArgsConstructor
public class TransferController {
    private final BankService bankService;

    @PostMapping("/transfer")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<?> transfer(@Valid @RequestBody TransferRequest request,
                                      @AuthenticationPrincipal CustomUserDetails userDetails) {
        String username = userDetails.getUsername();
        Long user_id=userDetails.getUser_id();

            Transaction tx = bankService.transfer(request.getKey(),username,user_id, request.getSenderAccount(), request.getReceiverAccount(), request.getAmount(),request.getPassword());
            return ResponseEntity.ok(tx);

    }
}
