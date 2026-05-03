
package backend.backend.Dtos;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class LoanSummaryDTO {
    private Long loanId;
    private double totalAmount;
    private double remainingBalance;
    private double monthlyEmi;
    private LocalDateTime nextDueDate;
    private int monthsRemaining;

}
