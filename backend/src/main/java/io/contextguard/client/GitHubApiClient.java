package io.contextguard.client;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.contextguard.exception.GitHubApiException;
import io.contextguard.exception.PRNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;

/**
 * HTTP client for GitHub REST API v3.
 *
 * Documentation: https://docs.github.com/en/rest
 *
 * Authentication:
 * - Public repos: No token required (but rate-limited to 60 req/hour)
 * - Private repos: Requires GITHUB_TOKEN environment variable
 *
 * Rate Limits:
 * - Unauthenticated: 60 requests/hour
 * - Authenticated: 5000 requests/hour
 */
@Component
public class GitHubApiClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String token;

    public GitHubApiClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${github.api.base-url}") String baseUrl,
            @Value("${github.api.token:}") String token) {

        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.token = token;
    }

    /**
     * Fetch PR metadata.
     *
     * Endpoint: GET /repos/{owner}/{repo}/pulls/{pull_number}
     * Docs: https://docs.github.com/en/rest/pulls/pulls#get-a-pull-request
     */
    public JsonNode getPullRequest(String owner, String repo, Integer prNumber) {

        String url = String.format("%s/repos/%s/%s/pulls/%d",
                baseUrl, owner, repo, prNumber);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    createHttpEntity(),
                    String.class
            );

            return objectMapper.readTree(response.getBody());

        } catch (HttpClientErrorException.NotFound e) {
            throw new PRNotFoundException(
                    String.format("PR not found: %s/%s#%d", owner, repo, prNumber));
        } catch (Exception e) {
            throw new GitHubApiException("Failed to fetch PR metadata", e);
        }
    }

    /**
     * Fetch PR files with diffs.
     *
     * Endpoint: GET /repos/{owner}/{repo}/pulls/{pull_number}/files
     * Docs: https://docs.github.com/en/rest/pulls/pulls#list-pull-requests-files
     *
     * Note: GitHub paginates results (max 100 files per page).
     * For simplicity, we only fetch the first page.
     * Production implementation should handle pagination.
     */
    public List<JsonNode> getPullRequestFiles(String owner, String repo, Integer prNumber) {

        String url = String.format("%s/repos/%s/%s/pulls/%d/files?per_page=100",
                baseUrl, owner, repo, prNumber);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    createHttpEntity(),
                    String.class
            );

            JsonNode filesArray = objectMapper.readTree(response.getBody());

            // Convert JsonNode array to List<JsonNode>
            List<JsonNode> files = new java.util.ArrayList<>();
            filesArray.forEach(files::add);

            return files;

        } catch (Exception e) {
            throw new GitHubApiException("Failed to fetch PR files", e);
        }
    }

    /**
     * Create HTTP entity with authorization header.
     *
     * If GITHUB_TOKEN is set, adds "Authorization: Bearer {token}" header.
     * Also sets Accept header for GitHub API v3.
     */
    private HttpEntity<String> createHttpEntity() {

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github.v3+json");

        if (token != null && !token.isEmpty()) {
            headers.set("Authorization", "Bearer " + token);
        }

        return new HttpEntity<>(headers);
    }

    public String getFileContent(String owner, String repo, String path, String ref) {
        try {
            String url = String.format("https://api.github.com/repos/%s/%s/contents/%s?ref=%s",
                    owner, repo, path, ref);

            HttpHeaders headers = new HttpHeaders();
            if (token != null && !token.isBlank()) {
                headers.set(HttpHeaders.AUTHORIZATION, "token " + token);
            }
            headers.set(HttpHeaders.ACCEPT, "application/vnd.github.v3.raw+json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<java.util.Map> resp = restTemplate.exchange(url, HttpMethod.GET, entity, java.util.Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                Object contentObj = resp.getBody().get("content");
                Object encoding = resp.getBody().get("encoding");
                if (contentObj instanceof String && "base64".equals(encoding)) {
                    String base64 = (String) contentObj;
                    byte[] decoded = Base64.getDecoder().decode(base64.replaceAll("\\s+", ""));
                    return new String(decoded);
                }
            }
        } catch (Exception e) {
            // keep conservative: return null if anything goes wrong
            // In real system, log at debug level
        }
        return null;
    }
}
