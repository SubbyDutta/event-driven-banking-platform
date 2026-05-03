package backend.backend.configuration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "frontend")
public class FrontEndProperties {
    private String url;
    public String geturl() { return url; }
    public void seturl(String url) { this.url = url; }
}
