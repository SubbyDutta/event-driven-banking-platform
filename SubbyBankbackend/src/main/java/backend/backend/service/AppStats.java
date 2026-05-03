package backend.backend.service;

import backend.backend.model.Stats;

import backend.backend.repository.BankAccountRepository;
import backend.backend.repository.TransactionRepository;
import backend.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppStats {
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final BankAccountRepository bankAccountRepository;
     public Stats getStats()
     {
         Stats s=new Stats();
         s.setTotalAccounts(bankAccountRepository.count());
         s.setTotalTransactions(transactionRepository.count());
         s.setTotalUsers(userRepository.count());
         return s;
     }

}
