package io.contextguard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.contextguard.config.JwtUtil;
import io.contextguard.model.User;
import io.contextguard.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    /**
     * Step 1 — redirect the browser to GitHub's OAuth consent page.
     * The frontend just navigates to this URL.
     */
    @GetMapping("/github")
    public ResponseEntity<?> initiateOAuth() {
        if (!authService.isOAuthConfigured()) {
            log.error("[auth] GitHub OAuth not configured — GITHUB_OAUTH_CLIENT_ID is missing");
            return ResponseEntity.status(503).body(
                    java.util.Map.of("error", "GitHub OAuth is not configured on this server. " +
                            "Set GITHUB_OAUTH_CLIENT_ID and GITHUB_OAUTH_CLIENT_SECRET in .env and restart."));
        }
        String state    = UUID.randomUUID().toString();
        String oauthUrl = authService.buildOAuthUrl(state);
        return ResponseEntity.status(302).location(URI.create(oauthUrl)).build();
    }

    /**
     * Step 2 — GitHub redirects back here with a one-time code.
     * We exchange it for a token, upsert the user, issue a JWT, and send the
     * browser to the frontend dashboard with the JWT in the query-string.
     */
    @GetMapping("/github/callback")
    public ResponseEntity<Void> oauthCallback(@RequestParam String code,
                                               @RequestParam(required = false) String state) {
        try {
            String accessToken  = authService.exchangeCodeForToken(code);
            JsonNode githubUser = authService.fetchGitHubUser(accessToken);
            User user           = authService.upsertUser(githubUser, accessToken);
            String jwt          = jwtUtil.generateToken(user);

            String redirect = frontendUrl + "/auth/callback?token=" + jwt;
            return ResponseEntity.status(302).location(URI.create(redirect)).build();

        } catch (Exception e) {
            log.error("[auth] OAuth callback error: {}", e.getMessage(), e);
            String redirect = frontendUrl + "/auth/callback?error=auth_failed";
            return ResponseEntity.status(302).location(URI.create(redirect)).build();
        }
    }

    /** Return the profile of the currently authenticated user. */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(Map.of(
                "id",        user.getId().toString(),
                "login",     user.getLogin(),
                "name",      user.getName() != null ? user.getName() : user.getLogin(),
                "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : "",
                "email",     user.getEmail() != null ? user.getEmail() : ""
        ));
    }

    /** Logout is stateless — the client just discards its JWT. */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout() {
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }
}
