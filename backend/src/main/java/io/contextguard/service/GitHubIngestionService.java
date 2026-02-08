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
 * RESPONSIBILITY: Pure data ingestion, no analysis.
 *
 * GitHub API v3 REST endpoints used:
 * - GET /repos/{owner}/{repo}/pulls/{number}
 * - GET /repos/{owner}/{repo}/pulls/{number}/files
 */
@Service
public class GitHubIngestionService {

    private final GitHubApiClient apiClient;

    public GitHubIngestionService(GitHubApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * Fetch PR metadata (title, author, timestamps, base/head branches).
     */
    public PRMetadata fetchPRMetadata(PRIdentifier prId) {

        var prData = apiClient.getPullRequest(
                prId.getOwner(), prId.getRepo(), prId.getPrNumber());

        return PRMetadata.builder()
                       .title(prData.get("title").asText())
                       .author(prData.get("user").get("login").asText())
                       .createdAt(prData.get("created_at").asText())
                       .updatedAt(prData.get("updated_at").asText())
                       .baseBranch(prData.get("base").get("ref").asText())
                       .headBranch(prData.get("head").get("ref").asText())
                       .prUrl(prData.get("html_url").asText())
                       .body(prData.get("body").asText())
                       .build();
    }

    /**
     * Fetch file-level changes with diffs.
     *
     * Returns raw diff content for each file.
     * Analysis happens in DiffMetadataAnalyzer, not here.
     */
    public List<GitHubFile> fetchDiffFiles(PRIdentifier prId) {

        // Call GitHub API: GET /repos/{owner}/{repo}/pulls/{number}/files
        List<JsonNode> files = apiClient.getPullRequestFiles(
                prId.getOwner(), prId.getRepo(), prId.getPrNumber());

        return files.stream()
                       .map(file -> new GitHubFile(
                               file.get("filename").asText(),
                               file.get("status").asText(), // added/modified/deleted
                               file.get("additions").asInt(),
                               file.get("deletions").asInt(),
                               file.get("patch").asText() // Unified diff format
                       ))
                       .toList();
    }
}