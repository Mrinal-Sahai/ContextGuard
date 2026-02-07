package io.contextguard.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration for HTTP clients.
 */
@Configuration
public class RestClientConfig {

    /**
     * RestTemplate bean for GitHub and AI API calls.
     *
     * Used by:
     * - GitHubApiClient
     * - AIApiClient
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
