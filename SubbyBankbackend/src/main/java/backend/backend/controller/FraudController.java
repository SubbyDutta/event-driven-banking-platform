package backend.backend.controller;

import backend.backend.requests_response.PagedResponse;
import backend.backend.Dtos.TransactionDto;
import backend.backend.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor

public class FraudController {

    private final TransactionService transactionService;

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/transactions")
    public PagedResponse<TransactionDto> getAllTransactions(
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "20") Integer size
    ) {
        return transactionService.getAllTransactionsPaged(page, size);
    }

}