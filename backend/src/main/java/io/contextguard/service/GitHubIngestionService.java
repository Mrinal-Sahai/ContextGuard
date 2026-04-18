package io.contextguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.contextguard.client.GitHubApiClient;
import io.contextguard.dto.GitHubFile;
import io.contextguard.dto.PRIdentifier;
import io.contextguard.dto.PRMetadata;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fetches PR data from GitHub API.
 *
 * Token resolution order per call:
 *   1. overrideToken (user's OAuth token or per-request PAT from request body)
 *   2. Server-level GITHUB_TOKEN env var (fallback in GitHubApiClient)
 *   3. Unauthenticated
 */
@Service
public class GitHubIngestionService {

    private final GitHubApiClient apiClient;

    public GitHubIngestionService(GitHubApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public PRMetadata fetchPRMetadata(PRIdentifier prId) {
        return fetchPRMetadata(prId, null);
    }

    public PRMetadata fetchPRMetadata(PRIdentifier prId, String overrideToken) {
        var prData = apiClient.getPullRequest(
                prId.getOwner(), prId.getRepo(), prId.getPrNumber(), overrideToken);

        return PRMetadata.builder()
                .title(prData.get("title").asText())
                .author(prData.get("user").get("login").asText())
                .createdAt(prData.get("created_at").asText())
                .updatedAt(prData.get("updated_at").asText())
                .baseBranch(prData.get("base").get("ref").asText())
                .baseSha(prData.get("base").get("sha").asText())
                .baseRepo(prData.get("base").get("repo").get("full_name").asText())
                .headBranch(prData.get("head").get("ref").asText())
                .headSha(prData.get("head").get("sha").asText())
                .headRepo(prData.get("head").get("repo").get("full_name").asText())
                .prUrl(prData.get("html_url").asText())
                .body(prData.get("body").asText())
                .build();
    }

    public List<GitHubFile> fetchDiffFiles(PRIdentifier prId) {
        return fetchDiffFiles(prId, null);
    }

    public List<GitHubFile> fetchDiffFiles(PRIdentifier prId, String overrideToken) {
        List<JsonNode> files = apiClient.getPullRequestFiles(
                prId.getOwner(), prId.getRepo(), prId.getPrNumber(), overrideToken);

        return files.stream()
                .map(file -> new GitHubFile(
                        file.get("filename").asText(),
                        file.get("status").asText(),
                        file.get("additions") == null ? 0 : file.get("additions").asInt(),
                        file.get("deletions") == null ? 0 : file.get("deletions").asInt(),
                        file.get("patch") == null ? "" : file.get("patch").asText()
                ))
                .toList();
    }
}
