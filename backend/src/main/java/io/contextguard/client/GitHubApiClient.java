package io.contextguard.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.contextguard.exception.GitHubApiException;
import io.contextguard.exception.PRNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * HTTP client for GitHub REST API v3.
 *
 * Authentication priority (highest to lowest):
 *   1. Per-request override token (from the user's OAuth session)
 *   2. Server-level GITHUB_TOKEN env var
 *   3. Unauthenticated (60 req/hr limit — fine for demos, rate-limited in CI)
 */
@Component
@Slf4j
public class GitHubApiClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String serverToken;

    public GitHubApiClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${github.api.base-url}") String baseUrl,
            @Value("${github.api.token:}") String serverToken) {

        this.restTemplate  = restTemplate;
        this.objectMapper  = objectMapper;
        this.baseUrl       = baseUrl;
        this.serverToken   = serverToken;

        if (serverToken == null || serverToken.isBlank()) {
            log.warn("[github] No server-level GITHUB_TOKEN configured — unauthenticated calls (60 req/hr)");
        }
    }

    public JsonNode getPullRequest(String owner, String repo, Integer prNumber) {
        return getPullRequest(owner, repo, prNumber, null);
    }

    public JsonNode getPullRequest(String owner, String repo, Integer prNumber, String overrideToken) {
        String url = String.format("%s/repos/%s/%s/pulls/%d", baseUrl, owner, repo, prNumber);
        log.debug("[github] GET {}", url);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entityWith(overrideToken), String.class);
            return objectMapper.readTree(response.getBody());

        } catch (HttpClientErrorException.NotFound e) {
            throw new PRNotFoundException(String.format("PR not found: %s/%s#%d", owner, repo, prNumber));

        } catch (HttpClientErrorException e) {
            log.error("[github] GET {} → HTTP {} — body: {}", url,
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new GitHubApiException(
                    String.format("GitHub API error %d fetching PR metadata: %s",
                            e.getStatusCode().value(), extractGitHubMessage(e.getResponseBodyAsString())), e);

        } catch (Exception e) {
            log.error("[github] Unexpected error fetching PR metadata from {}: {}", url, e.getMessage(), e);
            throw new GitHubApiException("Failed to fetch PR metadata: " + e.getMessage(), e);
        }
    }

    public List<JsonNode> getPullRequestFiles(String owner, String repo, Integer prNumber) {
        return getPullRequestFiles(owner, repo, prNumber, null);
    }

    public List<JsonNode> getPullRequestFiles(String owner, String repo, Integer prNumber, String overrideToken) {
        String url = String.format("%s/repos/%s/%s/pulls/%d/files?per_page=100", baseUrl, owner, repo, prNumber);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entityWith(overrideToken), String.class);
            JsonNode filesArray = objectMapper.readTree(response.getBody());

            List<JsonNode> files = new java.util.ArrayList<>();
            filesArray.forEach(files::add);
            return files;

        } catch (HttpClientErrorException e) {
            log.error("[github] GET {} → HTTP {} — body: {}", url,
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new GitHubApiException(
                    String.format("GitHub API error %d fetching PR files: %s",
                            e.getStatusCode().value(), extractGitHubMessage(e.getResponseBodyAsString())), e);

        } catch (Exception e) {
            log.error("[github] Unexpected error fetching PR files from {}: {}", url, e.getMessage(), e);
            throw new GitHubApiException("Failed to fetch PR files: " + e.getMessage(), e);
        }
    }

    /**
     * Returns filenames changed on {@code baseBranch} since it diverged from {@code headSha}.
     * Using 3-dot compare (head...base): lists commits on base not reachable from head.
     * The files in those commits are the ones base changed after the PR was created.
     * Intersection of this set with PR files = potential conflict candidates.
     */
    public List<String> getFilesChangedOnBase(
            String owner, String repo, String headSha, String baseBranch, String overrideToken) {
        String encodedBase = URLEncoder.encode(baseBranch, StandardCharsets.UTF_8);
        String url = String.format("%s/repos/%s/%s/compare/%s...%s?per_page=100",
                baseUrl, owner, repo, headSha, encodedBase);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entityWith(overrideToken), String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            List<String> files = new java.util.ArrayList<>();
            JsonNode filesNode = body.get("files");
            if (filesNode != null && filesNode.isArray()) {
                filesNode.forEach(f -> {
                    JsonNode fn = f.get("filename");
                    if (fn != null) files.add(fn.asText());
                });
            }
            return files;
        } catch (Exception e) {
            log.warn("[github] compare {}/{}  {}...{} failed: {}", owner, repo, headSha, baseBranch, e.getMessage());
            return List.of();
        }
    }

    public String getFileContent(String fullRepoName, String path, String ref, String overrideToken) {
        try {
            String[] parts = fullRepoName.split("/");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid repo name: " + fullRepoName);
            }
            String owner    = parts[0];
            String repo     = parts[1];
            String safePath = Arrays.stream(path.split("/"))
                    .map(seg -> URLEncoder.encode(seg, StandardCharsets.UTF_8))
                    .collect(Collectors.joining("/"));
            String encodedRef = URLEncoder.encode(ref, StandardCharsets.UTF_8);

            String url = String.format(
                    "https://api.github.com/repos/%s/%s/contents/%s?ref=%s",
                    owner, repo, safePath, encodedRef);

            HttpHeaders headers = buildHeaders(overrideToken);
            headers.set(HttpHeaders.ACCEPT, "application/vnd.github.v3.raw");

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            if (resp.getStatusCode().is2xxSuccessful()) {
                return resp.getBody();
            }
            log.warn("[github] Non-2xx {} fetching {}: {}", resp.getStatusCode(), path, resp.getBody());

        } catch (HttpClientErrorException.NotFound e) {
            log.warn("[github] File not found at ref {}: {}", ref, path);
        } catch (HttpClientErrorException e) {
            log.warn("[github] API error fetching {}: {} — {}",
                    path, e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("[github] Unexpected error fetching {}: {}", path, e.getMessage(), e);
        }
        return null;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private HttpEntity<String> entityWith(String overrideToken) {
        return new HttpEntity<>(buildHeaders(overrideToken));
    }

    private HttpHeaders buildHeaders(String overrideToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github.v3+json");

        String token = (overrideToken != null && !overrideToken.isBlank())
                ? overrideToken : serverToken;

        if (token != null && !token.isBlank()) {
            headers.set("Authorization", "Bearer " + token);
        }
        return headers;
    }

    private String extractGitHubMessage(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.has("message")) return node.get("message").asText();
        } catch (Exception ignored) {
            // body wasn't JSON
        }
        return body != null && body.length() > 200 ? body.substring(0, 200) : body;
    }
}
