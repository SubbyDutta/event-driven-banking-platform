package backend.backend.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "loan")
public class LoanMlProperties {
    private String url;
    public String geturl() { return url; }
    public void seturl(String url) { this.url = url; }
}