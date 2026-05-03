package backend.backend.service.loan;

import backend.backend.model.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assembles the feature map passed to SubbyPythonLoan in
 * {@link backend.backend.events.LoanRiskRequested}. Walks the stored
 * {@code loan_report_json} for structured income/expense signals and falls
 * back to values on the User record when findoc didn't extract them.
 *
 * <p>Keys match {@code SubbyPythonLoan/src/messaging/schemas.py::LoanRiskFeatures}.
 * Unknown keys are harmless — the Python schema accepts extras — but missing
 * REQUIRED keys ({@code monthly_income}, {@code credit_score}) will NonRetriable-DLQ
 * the message at the ML side, so this extractor is defensive about providing
 * safe zero defaults rather than null for those two.
 */
@Component
public class LoanFeatureExtractor {

    public Map<String, Object> build(User user, JsonNode loanReport,
                                     Double fraudScore, Integer complianceWarnings) {
        Map<String, Object> f = new LinkedHashMap<>();

        Double monthlyIncome = firstNumber(loanReport,
                "monthlyIncome", "monthly_income", "netMonthlyIncome", "net_monthly_income",
                "averageMonthlyCredit", "avg_monthly_credit",
                "monthly_payslip_net", "monthly_payslip_gross", "declared_monthly_inr",
                "monthly_from_bank");
        Double creditScore = firstNumber(loanReport,
                "creditScore", "credit_score", "cibilScore", "cibil_score", "bureauScore");
        if (creditScore == null && user.getCreditScore() > 0) {
            creditScore = (double) user.getCreditScore();
        }
        f.put("monthly_income", monthlyIncome != null ? monthlyIncome : 0.0);
        f.put("credit_score", creditScore != null ? creditScore : 0.0);

        Double existingEmi = firstNumber(loanReport,
                "existingEmi", "existing_emi", "totalEmiObligation", "emi_total");
        if (existingEmi != null) f.put("existing_emi", existingEmi);

        Double declaredAnnual = firstNumber(loanReport,
                "declaredIncomeAnnual", "declared_income_annual", "annualIncome", "annual_income",
                "grossAnnualIncome",
                "annual_from_itr", "annual_from_payslip", "declared_annual_inr");
        if (declaredAnnual != null) f.put("declared_income_annual", declaredAnnual);

        Double bankAvg = firstNumber(loanReport,
                "bankAvgBalance", "bank_avg_balance", "averageMonthlyBalance", "avg_monthly_balance");
        if (bankAvg != null) f.put("bank_avg_balance", bankAvg);

        String employmentType = firstText(loanReport,
                "employmentType", "employment_type", "employerCategory");
        if (employmentType != null) f.put("employment_type", employmentType);

        Integer age = firstInt(loanReport, "age", "applicantAge");
        if (age == null && user.getDob() != null) {
            age = java.time.Period.between(user.getDob(), java.time.LocalDate.now()).getYears();
        }
        if (age != null) f.put("age", age);

        Double dti = firstNumber(loanReport, "dtiRatio", "dti_ratio", "debtToIncomeRatio");
        if (dti == null && monthlyIncome != null && monthlyIncome > 0 && existingEmi != null) {
            dti = existingEmi / monthlyIncome;
        }
        if (dti != null) f.put("dti_ratio", dti);

        if (fraudScore != null) f.put("fraud_score", Math.min(1.0, Math.max(0.0, fraudScore)));
        if (complianceWarnings != null) f.put("compliance_warnings_count", complianceWarnings);

        return f;
    }

    public static Double firstNumber(JsonNode root, String... names) {
        JsonNode hit = findFirst(root, names);
        if (hit == null) return null;
        if (hit.isNumber()) return hit.doubleValue();
        if (hit.isTextual()) {
            try { return Double.parseDouble(hit.asText().replaceAll("[,₹\\s]", "")); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    public static Integer firstInt(JsonNode root, String... names) {
        Double n = firstNumber(root, names);
        return n == null ? null : (int) n.doubleValue();
    }

    public static String firstText(JsonNode root, String... names) {
        JsonNode hit = findFirst(root, names);
        return (hit != null && hit.isTextual()) ? hit.asText() : null;
    }

    /**
     * Depth-limited DFS for any key matching one of {@code names} (case-insensitive).
     * Skips matches whose value is JSON null / missing — findoc reports
     * frequently include a key with an explicit null when extraction failed
     * (e.g. {@code income.monthly_from_bank: null} when bank-statement income
     * couldn't be derived). Returning the null node would short-circuit
     * {@link #firstNumber} and silently zero out monthly_income, dropping the
     * loan into PoD≈0.9 territory regardless of the real income.
     */
    private static JsonNode findFirst(JsonNode root, String... names) {
        if (root == null || root.isNull()) return null;
        java.util.Deque<JsonNode> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        int budget = 4096;
        while (!stack.isEmpty() && budget-- > 0) {
            JsonNode n = stack.pop();
            if (n.isObject()) {
                var it = n.fields();
                while (it.hasNext()) {
                    var e = it.next();
                    String k = e.getKey();
                    JsonNode v = e.getValue();
                    for (String want : names) {
                        if (k.equalsIgnoreCase(want) && v != null && !v.isNull() && !v.isMissingNode()) {
                            return v;
                        }
                    }
                    stack.push(v);
                }
            } else if (n.isArray()) {
                for (JsonNode child : n) stack.push(child);
            }
        }
        return null;
    }
}
