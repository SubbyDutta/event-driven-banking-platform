package backend.backend.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix ="adharpankey")
public class SecretKeyProperties {
    private String key;
    private String fixed;

    public String getFixed() {
        return fixed;
    }

    public void setFixed(String fixed) {
        this.fixed = fixed;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
}
