package io.contextguard.dto.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class GitHubWebhookPayload {
    private String action;

    @JsonProperty("pull_request")
    private PullRequest pullRequest;

    private Repository repository;


    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class PullRequest {
        private Long number;
        private String title;
        private String body;
        private String state;

        private User user;
        private Head head;

        @JsonProperty("created_at")
        private String createdAt;

        @JsonProperty("html_url")
        private String htmlUrl;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class User {
        private String login;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Head {
        private String sha;
        private Repository repo;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Repository {
        private String name;
        private Owner owner;

        @JsonProperty("full_name")
        private String fullName;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Owner {
        private String login;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Commit {
        private String sha;
        private CommitDetail commit;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class CommitDetail {
        private String message;
    }
}
