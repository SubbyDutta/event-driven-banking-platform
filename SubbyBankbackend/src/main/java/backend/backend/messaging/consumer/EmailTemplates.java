package backend.backend.messaging.consumer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class EmailTemplates {

    private EmailTemplates() {}

    static String render(String templateName, Map<String, String> vars) {
        String raw = load("templates/" + templateName + ".txt");
        String out = raw;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}",
                    e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static String load(String classpath) {
        URL url = EmailTemplates.class.getClassLoader().getResource(classpath);
        if (url == null) {
            throw new IllegalStateException("Email template not found on classpath: " + classpath);
        }
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read email template " + classpath, e);
        }
    }
}
