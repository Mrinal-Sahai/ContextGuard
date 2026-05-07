package io.contextguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.contextguard.exception.GitHubApiException;
import io.contextguard.model.User;
import io.contextguard.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    @Value("${github.oauth.client-id}")
    private String clientId;

    @Value("${github.oauth.client-secret}")
    private String clientSecret;

    @Value("${github.oauth.callback-url}")
    private String callbackUrl;

    private final RestTemplate restTemplate;
    private final UserRepository userRepository;

    public boolean isOAuthConfigured() {
        return clientId != null && !clientId.isBlank()
                && !clientId.equals("your_oauth_client_id_here");
    }

    /** URL the browser should be redirected to in order to start GitHub OAuth consent. */
    public String buildOAuthUrl(String state) {
        return "https://github.com/login/oauth/authorize"
                + "?client_id=" + clientId
                + "&redirect_uri=" + URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode("repo read:user read:org", StandardCharsets.UTF_8)
                + "&state=" + state;
    }

    /**
     * Exchange the one-time authorization code for a GitHub access token.
     * POST https://github.com/login/oauth/access_token
     */
    public String exchangeCodeForToken(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, String> body = Map.of(
                "client_id",     clientId,
                "client_secret", clientSecret,
                "code",          code,
                "redirect_uri",  callbackUrl
        );

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "https://github.com/login/oauth/access_token",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                JsonNode.class
        );

        JsonNode resp = response.getBody();
        if (resp == null || !resp.has("access_token")) {
            String error = resp != null ? textOrNull(resp, "error_description") : "null response";
            throw new GitHubApiException("GitHub token exchange failed: " + error);
        }
        return resp.get("access_token").asText();
    }

    /** Call GET /user with the access token to retrieve the authenticated user's profile. */
    public JsonNode fetchGitHubUser(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        headers.set("Accept", "application/vnd.github.v3+json");

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "https://api.github.com/user",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class
        );

        if (response.getBody() == null) {
            throw new GitHubApiException("Empty response from GitHub /user");
        }
        return response.getBody();
    }

    /**
     * Insert or update the user record.
     * If the GitHub ID already exists we update the access token and last_login.
     */
    public User upsertUser(JsonNode githubUser, String accessToken) {
        long   githubId = githubUser.get("id").asLong();
        String login    = githubUser.get("login").asText();
        String name     = textOrNull(githubUser, "name");
        String email    = textOrNull(githubUser, "email");
        String avatar   = textOrNull(githubUser, "avatar_url");

        return userRepository.findByGithubId(githubId)
                .map(existing -> {
                    existing.setAccessToken(accessToken);
                    existing.setName(name);
                    existing.setEmail(email);
                    existing.setAvatarUrl(avatar);
                    existing.setLastLogin(Instant.now());
                    log.info("[auth] Returning user: {}", login);
                    return userRepository.save(existing);
                })
                .orElseGet(() -> {
                    User user = User.builder()
                            .id(UUID.randomUUID())
                            .githubId(githubId)
                            .login(login)
                            .name(name)
                            .email(email)
                            .avatarUrl(avatar)
                            .accessToken(accessToken)
                            .createdAt(Instant.now())
                            .lastLogin(Instant.now())
                            .build();
                    log.info("[auth] New user registered: {}", login);
                    return userRepository.save(user);
                });
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && !n.isNull()) ? n.asText() : null;
    }
}
