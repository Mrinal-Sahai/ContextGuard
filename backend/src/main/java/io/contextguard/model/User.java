package io.contextguard.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    private UUID id;

    @Column(name = "github_id", unique = true, nullable = false)
    private Long githubId;

    @Column(nullable = false)
    private String login;

    private String name;
    private String email;

    @Column(name = "avatar_url")
    private String avatarUrl;

    /** GitHub OAuth access token — used for API calls on behalf of this user. */
    @Column(name = "access_token", nullable = false)
    private String accessToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login", nullable = false)
    private Instant lastLogin;
}
