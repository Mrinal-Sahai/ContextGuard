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


@Component("geminiClient")
public class GeminiApiClient implements AIClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    private static final String BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models";

    public GeminiApiClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${ai.gemini.api-key}") String apiKey,
            @Value("${ai.gemini.model}") String model,
            @Value("${ai.gemini.temperature}") double temperature,
            @Value("${ai.gemini.max-tokens}") int maxTokens
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
            String url = BASE_URL + "/" + model + ":generateContent";

            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(
                            Map.of(
                                    "parts", List.of(
                                            Map.of("text", prompt)
                                    )
                            )
                    ),
                    "generationConfig", Map.of(
                            "temperature", temperature,
                            "maxOutputTokens", maxTokens
                    )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-goog-api-key", apiKey);

            HttpEntity<Map<String, Object>> entity =
                    new HttpEntity<>(requestBody, headers);

            System.out.println("Gemini API call "+url);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());

            return root
                           .path("candidates")
                           .get(0)
                           .path("content")
                           .path("parts")
                           .get(0)
                           .path("text")
                           .asText();

        }
        catch (HttpClientErrorException.TooManyRequests e) {
            System.out.println("Gemini quota exceeded. Falling back or retrying later. "+e);
            throw new AIServiceException(
                    "Gemini quota exceeded. Falling back or retrying later.", e
            );
        }
        catch (Exception e) {
            System.out.println("Gemini API call failed "+e);

            throw new AIServiceException("Gemini API call failed", e);
        }
    }
}
