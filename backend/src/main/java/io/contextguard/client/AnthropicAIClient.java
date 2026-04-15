package io.contextguard.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contextguard.exception.AIServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * ANTHROPIC AI CLIENT — Claude + MCP Connector
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Calls the Anthropic Messages API (claude-sonnet-4-6) with the MCP Connector
 * beta feature. When a GitHub token is available, Claude can autonomously call
 * the GitHub MCP server during generation to fetch:
 *
 *   - list_code_scanning_alerts  → CodeQL security findings already computed in CI
 *   - get_pull_request_reviews   → existing reviewer concerns and approvals
 *   - get_pull_request_status    → CI pass/fail summary
 *
 * This is the same contextual enrichment that makes CodeRabbit reviews specific
 * rather than generic — the LLM reads real data instead of guessing.
 *
 * FALLBACK
 * ─────────
 * If no GitHub token is provided, or if the MCP Connector call fails, the client
 * falls back to a standard (non-MCP) Messages API call with the same prompt.
 * The Semgrep findings already injected into the prompt still improve quality.
 *
 * USE
 * ────
 * Set LLM_PROVIDER=ANTHROPIC and ANTHROPIC_API_KEY in your environment.
 * The GitHub token is read from the same GITHUB_TOKEN used elsewhere.
 */
@Slf4j
@Component("anthropicAIClient")
public class AnthropicAIClient implements AIClient {

    private static final String MESSAGES_URL     = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String MCP_BETA_HEADER   = "mcp-client-2025-11-20";
    private static final String GITHUB_MCP_URL    = "https://api.githubcopilot.com/mcp/";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final int    maxTokens;
    private final String githubToken;

    public AnthropicAIClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${ai.anthropic.api-key:}") String apiKey,
            @Value("${ai.anthropic.model:claude-sonnet-4-6}") String model,
            @Value("${ai.anthropic.max-tokens:6000}") int maxTokens,
            @Value("${github.api.token:}") String githubToken) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiKey       = apiKey;
        this.model        = model;
        this.maxTokens    = maxTokens;
        this.githubToken  = githubToken;
    }

    @Override
    public String generateSummary(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AIServiceException("ANTHROPIC_API_KEY is not set", null);
        }

        boolean useMcp = githubToken != null && !githubToken.isBlank();

        try {
            return useMcp ? callWithMcp(prompt) : callStandard(prompt);
        } catch (AIServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new AIServiceException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MCP Connector call — Claude can query GitHub during generation
    // ─────────────────────────────────────────────────────────────────────────

    private String callWithMcp(String prompt) {
        Map<String, Object> mcpServer = Map.of(
                "type",                "url",
                "url",                 GITHUB_MCP_URL,
                "name",                "github",
                "authorization_token", githubToken
        );

        Map<String, Object> mcpToolset = Map.of(
                "type",           "mcp",
                "server_name",    "github",
                "allowed_tools",  List.of(
                        "list_code_scanning_alerts",
                        "get_pull_request_reviews",
                        "get_pull_request_status"
                )
        );

        Map<String, Object> body = Map.of(
                "model",       model,
                "max_tokens",  maxTokens,
                "messages",    List.of(Map.of("role", "user", "content", prompt)),
                "mcp_servers", List.of(mcpServer),
                "tools",       List.of(mcpToolset)
        );

        HttpHeaders headers = buildHeaders(true);
        log.info("Anthropic MCP call — GitHub tools enabled");

        try {
            String response = post(headers, body);
            return extractText(response);
        } catch (Exception e) {
            log.warn("MCP call failed ({}), falling back to standard call", e.getMessage());
            return callStandard(prompt);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Standard call — no MCP tools, just the enriched prompt
    // ─────────────────────────────────────────────────────────────────────────

    private String callStandard(String prompt) {
        Map<String, Object> body = Map.of(
                "model",      model,
                "max_tokens", maxTokens,
                "messages",   List.of(Map.of("role", "user", "content", prompt))
        );

        HttpHeaders headers = buildHeaders(false);
        log.info("Anthropic standard call (no MCP)");
        return extractText(post(headers, body));
    }

    // ─────────────────────────────────────────────────────────────────────────

    private HttpHeaders buildHeaders(boolean withMcpBeta) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("x-api-key",         apiKey);
        h.set("anthropic-version", ANTHROPIC_VERSION);
        if (withMcpBeta) {
            h.set("anthropic-beta", MCP_BETA_HEADER);
        }
        return h;
    }

    private String post(HttpHeaders headers, Map<String, Object> body) {
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    MESSAGES_URL, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
            return resp.getBody();
        } catch (Exception e) {
            throw new AIServiceException("Anthropic HTTP call failed: " + e.getMessage(), e);
        }
    }

    private String extractText(String responseBody) {
        try {
            JsonNode root    = objectMapper.readTree(responseBody);
            JsonNode content = root.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    // text block
                    if ("text".equals(block.path("type").asText())) {
                        return block.path("text").asText();
                    }
                }
            }
            // tool_use blocks arrive when MCP tools are called — Claude still produces
            // a final text block after tool results; loop above handles it.
            throw new AIServiceException("No text block in Anthropic response: " + responseBody, null);
        } catch (AIServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new AIServiceException("Failed to parse Anthropic response", e);
        }
    }
}
