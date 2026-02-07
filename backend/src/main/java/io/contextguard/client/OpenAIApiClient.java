package io.contextguard.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contextguard.exception.AIServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component("openAIClient")
public class OpenAIApiClient implements AIClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    private static final String BASE_URL = "https://api.openai.com/v1/responses";

    public OpenAIApiClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${ai.openai.api-key}") String apiKey,
            @Value("${ai.openai.model}") String model,
            @Value("${ai.openai.temperature}") double temperature,
            @Value("${ai.openai.max-tokens}") int maxTokens
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    @Override
    public String generateSummary(String prompt) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "input", prompt,
                    "max_output_tokens", maxTokens,
                    "temperature", temperature
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> entity =
                    new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    BASE_URL,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());

            return root
                           .path("output")
                           .get(0)
                           .path("content")
                           .get(0)
                           .path("text")
                           .asText();

        }
        catch (HttpClientErrorException.TooManyRequests e) {
            throw new AIServiceException(
                    "OpenAI quota exceeded. Falling back or retrying later.", e
            );
        }
        catch (Exception e) {
            throw new AIServiceException("OpenAI API call failed", e);
        }
    }
}

