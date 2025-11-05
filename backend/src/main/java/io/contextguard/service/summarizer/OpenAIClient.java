
package io.contextguard.service.summarizer;

import io.contextguard.dto.SummaryData;
import io.contextguard.model.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

@Service("openaiClient")
public class OpenAIClient implements LLMClient {

    @Value("${contextguard.llm.openai.api-key:}")
    private String apiKey;

    @Value("${contextguard.llm.openai.model:gpt-4o-mini}")
    private String model;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OpenAIClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                                 .baseUrl("https://api.openai.com/v1")
                                 .build();
    }

    @Override
    public SummaryData generateSummary(Snapshot snapshot, String sanitizedContent) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("OpenAI API key not configured");
        }
        String prompt = buildPrompt(snapshot, sanitizedContent);
        try {
            String response = webClient.post()
                                      .uri("/chat/completions")
                                      .header("Authorization", "Bearer " + apiKey)
                                      .header("Content-Type", "application/json")
                                      .bodyValue(buildRequestBody(prompt))
                                      .retrieve()
                                      .bodyToMono(String.class)
                                      .block();

            return parseResponse(response);

        } catch (Exception e) {
            throw new RuntimeException("Failed to call OpenAI API", e);
        }
    }

    private String buildPrompt(Snapshot snapshot, String sanitizedContent) {
        return """
            You are a code review assistant. Analyze this PR and provide:
            1. A 1-2 sentence summary
            2. Why this change is being made
            3. Up to 3 key risks
            4. A 3-item reviewer checklist
            PR Content:
  %s
            Respond in JSON format:
            {
              "summary": "...",
              "why": "...",
              "risks": ["...", "..."],
              "review_checklist": ["...", "...", "..."]
            }
            """.formatted(sanitizedContent);
    }

    private Map<String, Object> buildRequestBody(String prompt) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", Arrays.asList(
            Map.of("role", "user", "content", prompt)
        ));
        body.put("temperature", 0.7);
        body.put("max_tokens", 500);
        return body;
    }

    private SummaryData parseResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText();
            return objectMapper.readValue(content, SummaryData.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OpenAI response", e);
        }
    }
}


