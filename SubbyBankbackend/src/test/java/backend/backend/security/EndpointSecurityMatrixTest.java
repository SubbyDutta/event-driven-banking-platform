package backend.backend.security;

import backend.backend.model.KycStatus;
import backend.backend.model.User;
import backend.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Sql(scripts = "classpath:h2-schema-fixup.sql", executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointSecurityMatrixTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired UserRepository userRepository;

    private String userToken;

    @BeforeAll
    void seedUsersAndTokens() {
        userRepository.findByUsername("matrix_user").ifPresent(u -> userRepository.deleteById(u.getId()));

        User u = newUser("matrix_user", "USER");
        userRepository.save(u);

        userToken = jwtUtil.generateToken("matrix_user", "USER");
    }

    private User newUser(String username, String role) {
        User u = new User();
        u.setUsername(username);
        u.setFirstname("Matrix");
        u.setLastname(role);
        u.setEmail(username + "@example.com");
        u.setMobile("9" + Long.toString(System.nanoTime()).substring(0, 9));
        u.setPassword("x");
        u.setRole(role);
        u.setKycStatus(KycStatus.NONE);
        u.setAccountActive(true);
        u.setPanNumber("ABCDE1234F");
        u.setAadhaarNumber("123412341234");
        u.setDob(LocalDate.of(1990, 1, 1));
        return u;
    }

    static List<Endpoint> publicEndpoints() {
        return List.of(
                ep("GET",  "/api/auth/health"),
                ep("POST", "/api/auth/signup",          "{}"),
                ep("POST", "/api/auth/login",           "{}"),
                ep("POST", "/api/auth/forgot-password", "{}"),
                ep("POST", "/api/auth/reset-password",  "{}"),
                ep("POST", "/api/payment/webhook",      "{}")
        );
    }

    static List<Endpoint> protectedEndpoints() {
        return List.of(
                ep("GET",    "/api/user/me/account"),
                ep("GET",    "/api/user/balance"),
                ep("GET",    "/api/user/creditscore"),
                ep("GET",    "/api/user/sample/transactions"),
                ep("POST",   "/api/user/create-account", "{}"),

                ep("POST",   "/api/kyc/apply",  "{}"),
                ep("GET",    "/api/kyc/status"),

                ep("POST",   "/api/loan/check", "{}"),
                ep("POST",   "/api/loan/apply/abc", "{}"),
                ep("GET",    "/api/loan/pending"),
                ep("GET",    "/api/loan/loans/myuserloan"),

                ep("POST",   "/api/loans/apply", "{}"),
                ep("POST",   "/api/loans/abc-id/accept", "{}"),
                ep("POST",   "/api/loans/abc-id/decline", "{}"),
                ep("GET",    "/api/loans/abc-id/status"),
                ep("GET",    "/api/loans/my"),

                ep("GET",    "/api/admin/users"),
                ep("GET",    "/api/admin/user/1"),
                ep("PUT",    "/api/admin/user/1", "{}"),
                ep("DELETE", "/api/admin/user/1"),
                ep("GET",    "/api/admin/alllogs"),
                ep("GET",    "/api/admin/logs/action"),
                ep("PATCH",  "/api/admin/block/1", "{}"),
                ep("GET",    "/api/admin/balance/1"),
                ep("GET",    "/api/admin/bankaccounts"),
                ep("PATCH",  "/api/admin/balance/", "{}"),
                ep("GET",    "/api/admin/accounts/1"),
                ep("DELETE", "/api/admin/accounts/1"),
                ep("POST",   "/api/admin/approve/1", "{}"),
                ep("GET",    "/api/admin/loans/search"),
                ep("GET",    "/api/admin/analytics/stats"),

                ep("GET",    "/api/admin/kyc/users"),
                ep("GET",    "/api/admin/kyc/users/1"),
                ep("POST",   "/api/admin/kyc/users/1/override", "{}"),

                ep("GET",    "/api/admin/loans"),
                ep("GET",    "/api/admin/loans/manual-review"),
                ep("GET",    "/api/admin/loans/abc-id"),
                ep("POST",   "/api/admin/loans/abc-id/override", "{}"),
                ep("GET",    "/api/admin/loans/abc-id/documents/payslip/download"),

                ep("GET",    "/api/admin/thresholds"),
                ep("PUT",    "/api/admin/thresholds/loan_dti_max", "{}"),

                ep("GET",    "/api/admin/dlq"),
                ep("GET",    "/api/admin/dlq/queueA"),
                ep("POST",   "/api/admin/dlq/queueA/replay", "{}"),
                ep("POST",   "/api/admin/dlq/queueA/replay-all", "{}"),
                ep("DELETE", "/api/admin/dlq/queueA/msg-1"),

                ep("POST",   "/api/payment/create-order", "{}"),
                ep("POST",   "/api/payment/verify",       "{}"),

                ep("POST",   "/api/transfer/transfer", "{}"),

                ep("POST",   "/api/repay/repay/1",    "{}"),
                ep("GET",    "/api/repay"),
                ep("GET",    "/api/repay/repayments"),
                ep("GET",    "/api/repay/summary/1"),
                ep("GET",    "/api/repay/user/approved"),

                ep("GET",    "/api/pool"),
                ep("POST",   "/api/pool/topup", "{}"),

                ep("POST",   "/api/chatbot", "{}"),

                ep("GET",    "/api/transactions/transactions")
        );
    }

    static List<Endpoint> adminOnlyEndpoints() {
        return List.of(
                ep("GET",    "/api/loan/pending"),

                ep("GET",    "/api/admin/users"),
                ep("GET",    "/api/admin/user/1"),
                ep("PUT",    "/api/admin/user/1", "{}"),
                ep("DELETE", "/api/admin/user/1"),
                ep("GET",    "/api/admin/alllogs"),
                ep("GET",    "/api/admin/logs/action"),
                ep("PATCH",  "/api/admin/block/1", "{}"),
                ep("GET",    "/api/admin/balance/1"),
                ep("GET",    "/api/admin/bankaccounts"),
                ep("PATCH",  "/api/admin/balance/", "{}"),
                ep("GET",    "/api/admin/accounts/1"),
                ep("DELETE", "/api/admin/accounts/1"),
                ep("POST",   "/api/admin/approve/1", "{}"),
                ep("GET",    "/api/admin/loans/search"),
                ep("GET",    "/api/admin/analytics/stats"),

                ep("GET",    "/api/admin/kyc/users"),
                ep("GET",    "/api/admin/kyc/users/1"),
                ep("POST",   "/api/admin/kyc/users/1/override", "{}"),

                ep("GET",    "/api/admin/loans"),
                ep("GET",    "/api/admin/loans/manual-review"),
                ep("GET",    "/api/admin/loans/abc-id"),
                ep("POST",   "/api/admin/loans/abc-id/override", "{}"),
                ep("GET",    "/api/admin/loans/abc-id/documents/payslip/download"),

                ep("GET",    "/api/admin/thresholds"),
                ep("PUT",    "/api/admin/thresholds/loan_dti_max", "{}"),

                ep("GET",    "/api/admin/dlq"),
                ep("GET",    "/api/admin/dlq/queueA"),
                ep("POST",   "/api/admin/dlq/queueA/replay", "{}"),
                ep("POST",   "/api/admin/dlq/queueA/replay-all", "{}"),
                ep("DELETE", "/api/admin/dlq/queueA/msg-1"),

                ep("GET",    "/api/repay"),

                ep("GET",    "/api/pool"),
                ep("POST",   "/api/pool/topup", "{}"),

                ep("GET",    "/api/transactions/transactions")
        );
    }

    @ParameterizedTest(name = "[{index}] permitAll {0}")
    @MethodSource("publicEndpoints")
    @DisplayName("permitAll endpoints are not Spring-403'd without a token")
    void publicEndpoint_doesNotReturn403(Endpoint e) throws Exception {
        MvcResult res = mockMvc.perform(buildRequest(e, null)).andReturn();
        int status = res.getResponse().getStatus();
        assertThat(status)
                .as("permitAll endpoint %s %s should not be Spring 403 (got %d body=%s)",
                        e.method, e.path, status, snippet(res))
                .isNotEqualTo(403);
    }

    @ParameterizedTest(name = "[{index}] no-token {0}")
    @MethodSource("protectedEndpoints")
    @DisplayName("Protected endpoints reject anonymous calls (401 or 403)")
    void protectedEndpoint_blocksAnonymous(Endpoint e) throws Exception {
        MvcResult res = mockMvc.perform(buildRequest(e, null)).andReturn();
        int status = res.getResponse().getStatus();
        assertThat(status)
                .as("protected endpoint %s %s should be blocked unauth (got %d body=%s)",
                        e.method, e.path, status, snippet(res))
                .isIn(401, 403);
    }

    @ParameterizedTest(name = "[{index}] user-token-on-admin {0}")
    @MethodSource("adminOnlyEndpoints")
    @DisplayName("ADMIN-only endpoints reject USER-role token (no successful execution)")
    void adminEndpoint_rejectsUserToken(Endpoint e) throws Exception {
        MvcResult res = mockMvc.perform(buildRequest(e, userToken)).andReturn();
        int status = res.getResponse().getStatus();
        assertThat(status)
                .as("ADMIN-only endpoint %s %s should reject USER token (got %d body=%s)",
                        e.method, e.path, status, snippet(res))
                .isNotIn(200, 201, 202, 204);
    }

    private MockHttpServletRequestBuilder buildRequest(Endpoint e, String bearer) {
        MockHttpServletRequestBuilder b = switch (e.method) {
            case "GET" -> get(e.path);
            case "POST" -> post(e.path);
            case "PUT" -> put(e.path);
            case "DELETE" -> delete(e.path);
            case "PATCH" -> patch(e.path);
            default -> throw new IllegalArgumentException("unsupported: " + e.method);
        };
        if (e.body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(e.body);
        }
        if (bearer != null) {
            b = b.header("Authorization", "Bearer " + bearer);
        }
        return b;
    }

    private static Endpoint ep(String method, String path) {
        return new Endpoint(method, path, null);
    }

    private static Endpoint ep(String method, String path, String body) {
        return new Endpoint(method, path, body);
    }

    private static String snippet(MvcResult r) {
        try {
            String s = r.getResponse().getContentAsString();
            if (s == null) return "<null>";
            return s.length() > 120 ? s.substring(0, 120) + "..." : s;
        } catch (Exception ex) {
            return "<unreadable>";
        }
    }

    record Endpoint(String method, String path, String body) {
        @Override public String toString() { return method + " " + path; }
    }
}
