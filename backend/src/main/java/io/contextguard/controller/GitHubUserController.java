package io.contextguard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.contextguard.model.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/github")
@RequiredArgsConstructor
@Slf4j
public class GitHubUserController {

    private final RestTemplate restTemplate;

    /**
     * Returns open pull requests where the authenticated user is a requested reviewer.
     *
     * GitHub Search API: GET /search/issues?q=is:pr+review-requested:@me+is:open
     * Docs: https://docs.github.com/en/rest/search/search#search-issues-and-pull-requests
     */
    @GetMapping("/review-requests")
    public ResponseEntity<List<Map<String, Object>>> getReviewRequests(HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + user.getAccessToken());
            headers.set("Accept", "application/vnd.github.v3+json");

            String url = "https://api.github.com/search/issues"
                    + "?q=is:pr+review-requested:@me+is:open"
                    + "&sort=updated&order=desc&per_page=30";

            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

            JsonNode body = response.getBody();
            if (body == null || !body.has("items")) {
                return ResponseEntity.ok(List.of());
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (JsonNode item : body.get("items")) {
                JsonNode repoUrlNode = item.get("repository_url");
                String repoUrl = (repoUrlNode != null && !repoUrlNode.isNull()) ? repoUrlNode.asText() : "";
                String[] repoParts = repoUrl.replace("https://api.github.com/repos/", "").split("/");
                String owner  = repoParts.length > 0 ? repoParts[0] : "";
                String repo   = repoParts.length > 1 ? repoParts[1] : "";
                int    number = item.path("number").asInt();

                results.add(Map.of(
                        "number",      number,
                        "title",       item.path("title").asText(),
                        "prUrl",       item.path("html_url").asText(),
                        "author",      item.path("user").path("login").asText(),
                        "authorAvatar",item.path("user").path("avatar_url").asText(),
                        "owner",       owner,
                        "repo",        repo,
                        "updatedAt",   item.path("updated_at").asText(),
                        "state",       item.path("state").asText(),
                        "draft",       item.path("draft").asBoolean(false)
                ))
            }

            log.info("[github] {} review-requested PRs for {}", results.size(), user.getLogin());
            return ResponseEntity.ok(results);

        } catch (Exception e) {
            log.error("[github] Failed to fetch review requests for {}: {}", user.getLogin(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
