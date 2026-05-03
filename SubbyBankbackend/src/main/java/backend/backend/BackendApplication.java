package backend.backend;

import java.time.Duration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@ConfigurationPropertiesScan("backend.backend.configuration")
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

	/**
	 * Default RestTemplate for any caller that doesn't ask for a specific one.
	 * Conservative timeouts so an unconfigured caller can't hang a Tomcat
	 * worker thread forever — the original {@code new RestTemplate()} had
	 * infinite connect+read timeouts, which is the actual mechanism by which
	 * a slow downstream (FraudPython, Gemini, etc.) could exhaust the pool
	 * under load.
	 */
	@Bean
	@Primary
	public RestTemplate restTemplate(RestTemplateBuilder builder) {
		return builder
				.connectTimeout(Duration.ofSeconds(5))
				.readTimeout(Duration.ofSeconds(30))
				.build();
	}

	/**
	 * Strict-timeout RestTemplate dedicated to FraudPython /predict. The fraud
	 * call sits on the transfer hot path, so the timeout budget is small —
	 * we'd rather fall back via the circuit breaker than wait seconds for a
	 * slow scorer. Tune via {@code fraud.connect-timeout-ms} /
	 * {@code fraud.read-timeout-ms} in application.yml.
	 */
	@Bean("fraudRestTemplate")
	public RestTemplate fraudRestTemplate(RestTemplateBuilder builder) {
		return builder
				.connectTimeout(Duration.ofMillis(500))
				.readTimeout(Duration.ofMillis(1500))
				.build();
	}
}
