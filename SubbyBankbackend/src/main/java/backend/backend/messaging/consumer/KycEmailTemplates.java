package backend.backend.messaging.consumer;

import backend.backend.model.KycStatus;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Minimal template renderer for KYC decision emails. We ship plain-text bodies
 * (EmailService uses SimpleMailMessage — no HTML rendering engine is set up in
 * this project). Templates live under
 * {@code src/main/resources/templates/kyc-*.txt} with {@code {{var}}} placeholders.
 *
 * <p>Prompt 5 introduces a generic notification pipeline; when that lands, this
 * helper is replaced by it. For now, a 40-line renderer beats a Thymeleaf dep.
 */
final class KycEmailTemplates {

    private KycEmailTemplates() {}

    record Rendered(String subject, String body) {}

    static Rendered render(KycStatus status, Map<String, String> vars) {
        return switch (status) {
            case KYC_APPROVED -> render("kyc-approved",
                    "Welcome — your account is now active", vars);
            case KYC_REJECTED -> render("kyc-rejected",
                    "We couldn't verify your KYC documents", vars);
            case KYC_MANUAL_REVIEW -> render("kyc-manual-review",
                    "Your KYC application is under review", vars);
            default -> null;
        };
    }

    private static Rendered render(String template, String subject, Map<String, String> vars) {
        String raw = loadTemplate(template);
        String rendered = substitute(raw, vars);
        return new Rendered(subject, rendered);
    }

    private static String loadTemplate(String name) {
        String path = "templates/" + name + ".txt";
        URL url = KycEmailTemplates.class.getClassLoader().getResource(path);
        if (url == null) {

            return "Your KYC status has been updated. Reason: {{reason}}";
        }
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read email template " + path, e);
        }
    }

    private static String substitute(String template, Map<String, String> vars) {
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}",
                    e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }
}
