package backend.backend.messaging.consumer.loan;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the risk-band → decision + interest-rate policy. Changing any
 * mapping here is a product-pricing change — deliberate, never accidental.
 */
class LoanRiskResultConsumerPolicyTest {

    @Test
    void interest_rate_ladder() {
        assertEquals(new BigDecimal("10.50"), LoanRiskResultConsumer.interestRateFor("A"));
        assertEquals(new BigDecimal("12.00"), LoanRiskResultConsumer.interestRateFor("B"));
        assertEquals(new BigDecimal("14.50"), LoanRiskResultConsumer.interestRateFor("C"));
        assertEquals(new BigDecimal("17.00"), LoanRiskResultConsumer.interestRateFor("D"));
        assertNull(LoanRiskResultConsumer.interestRateFor("E"),
                "Band E has no rate — it is a hard reject");
        assertNull(LoanRiskResultConsumer.interestRateFor(null));
    }

    @Test
    void band_e_is_always_rejected_even_if_ml_says_approve() {
        assertEquals("REJECTED", LoanRiskResultConsumer.resolveDecision("approve", "E"));
    }

    @Test
    void manual_review_wins_over_everything() {
        assertEquals("MANUAL_REVIEW", LoanRiskResultConsumer.resolveDecision("manual_review", "A"));
        assertEquals("MANUAL_REVIEW", LoanRiskResultConsumer.resolveDecision("manual_review", "E"));
    }

    @Test
    void approve_on_abcd_approves() {
        for (String band : new String[]{"A", "B", "C", "D"}) {
            assertEquals("APPROVED", LoanRiskResultConsumer.resolveDecision("approve", band),
                    "Band " + band + " should approve when ML says approve");
        }
    }

    @Test
    void reject_from_ml_always_rejects() {
        assertEquals("REJECTED", LoanRiskResultConsumer.resolveDecision("reject", "A"));
        assertEquals("REJECTED", LoanRiskResultConsumer.resolveDecision("reject", "C"));
    }

    @Test
    void unknown_inputs_default_to_manual_review() {
        assertEquals("MANUAL_REVIEW", LoanRiskResultConsumer.resolveDecision("maybe", "A"));
        assertEquals("MANUAL_REVIEW", LoanRiskResultConsumer.resolveDecision("approve", null));
        assertEquals("MANUAL_REVIEW", LoanRiskResultConsumer.resolveDecision(null, null));
    }
}
