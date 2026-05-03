package backend.backend.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.net.URI;
import java.time.Duration;

/**
 * Central AWS SDK v2 client configuration. Replaces Spring Cloud AWS auto-configured
 * beans with explicit instances that carry tuned timeouts, connection pools, and
 * optional endpoint overrides for LocalStack.
 *
 * <p>Profile behavior:
 * <ul>
 *   <li><b>local</b>: {@code spring.cloud.aws.*.endpoint} is set, so clients are wired
 *       with an {@link URI#create(String) endpointOverride} pointing at LocalStack.</li>
 *   <li><b>aws</b>: no endpoint overrides configured, so clients resolve real AWS
 *       endpoints by region and use the default credentials provider chain.</li>
 * </ul>
 */
@Configuration
public class AwsConfig {

    @Value("${spring.cloud.aws.region.static:ap-south-1}")
    private String region;

    @Value("${spring.cloud.aws.sns.endpoint:}")
    private String snsEndpoint;

    @Value("${spring.cloud.aws.sqs.endpoint:}")
    private String sqsEndpoint;

    @Value("${spring.cloud.aws.s3.endpoint:}")
    private String s3Endpoint;

    @Value("${spring.cloud.aws.s3.path-style-access-enabled:false}")
    private boolean s3PathStyle;

    @Value("${spring.cloud.aws.credentials.access-key:}")
    private String accessKey;

    @Value("${spring.cloud.aws.credentials.secret-key:}")
    private String secretKey;

    private AwsCredentialsProvider credentialsProvider() {
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }

    private ClientOverrideConfiguration overrideConfig() {

        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(60))
                .apiCallAttemptTimeout(Duration.ofSeconds(25))
                .build();
    }

    @Bean(destroyMethod = "close")
    @Primary
    public SnsAsyncClient snsAsyncClient() {
        var builder = SnsAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider())
                .overrideConfiguration(overrideConfig())
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(50)
                        .connectionTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(15)));
        if (!snsEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(snsEndpoint));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    @Primary
    public SqsAsyncClient sqsAsyncClient() {
        var builder = SqsAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider())
                .overrideConfiguration(overrideConfig())
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(50)
                        .connectionTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(25)));
        if (!sqsEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(sqsEndpoint));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    @Primary
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider())
                .overrideConfiguration(overrideConfig())
                .httpClientBuilder(ApacheHttpClient.builder()
                        .maxConnections(50)
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(30)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3PathStyle)
                        .build());
        if (!s3Endpoint.isBlank()) {
            builder.endpointOverride(URI.create(s3Endpoint));
        }
        return builder.build();
    }
}
