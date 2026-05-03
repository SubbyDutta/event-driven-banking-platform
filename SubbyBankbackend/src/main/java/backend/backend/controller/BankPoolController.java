package backend.backend.controller;

import backend.backend.service.BankPoolService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pool")
public class BankPoolController {

    private final BankPoolService bankPoolService;

    public BankPoolController(BankPoolService bankPoolService) {
        this.bankPoolService = bankPoolService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Double> getBankBalance() {
        return ResponseEntity.ok(bankPoolService.getPool().getBalance());
    }

    @PostMapping("/topup")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> addBalance(@RequestParam double amount) {
        bankPoolService.add(amount);
        return ResponseEntity.ok("Bank pool increased by ₹" + amount);
    }
}
