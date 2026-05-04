package backend.backend.service.loan;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the EMI math against accidental regressions. The numbers are ground
 * truth — users will notice if EMIs shift by ₹1.
 */
class LoanFinalizationServiceTest {

    @Test
    void emi_at_12_percent_on_500k_over_6_months_matches_standard_formula() {


        double emi = LoanFinalizationService.computeEmi(
                500_000d, new BigDecimal("12.00"), 6);
        assertEquals(86274.18, emi, 0.05, "EMI for 5L @ 12% over 6 months");
    }

    @Test
    void emi_at_10_5_percent_on_100k_over_6_months() {
        double emi = LoanFinalizationService.computeEmi(
                100_000d, new BigDecimal("10.50"), 6);

        assertTrue(emi > 17_100 && emi < 17_210,
                "EMI should land near 17,156 for 1L @ 10.5% over 6 months, got " + emi);
    }

    @Test
    void zero_rate_divides_principal_evenly() {
        double emi = LoanFinalizationService.computeEmi(
                60_000d, BigDecimal.ZERO, 6);
        assertEquals(10_000.00, emi, 0.01);
    }

    @Test
    void emi_band_a_vs_band_d_rate_ordering() {
        double low = LoanFinalizationService.computeEmi(
                200_000d, new BigDecimal("10.50"), 6);
        double high = LoanFinalizationService.computeEmi(
                200_000d, new BigDecimal("17.00"), 6);
        assertTrue(high > low, "Band-D EMI must exceed band-A EMI on identical principal and tenure");
    }
}
