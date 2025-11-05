package io.contextguard.dto.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class BeanstalkWebhookPayload {
    private String event;
    private Review review;
    private Repository repository;

    @Data
    public static class Review {
        private String id;
        private String title;
        private String description;
        private String status;

        @JsonProperty("created_at")
        private String createdAt;

        private Author author;

        @JsonProperty("changeset_url")
        private String changesetUrl;
    }

    @Data
    public static class Author {
        private String name;
        private String email;
    }

    @Data
    public static class Repository {
        private String name;
        private Account account;
    }
    @Data
    public static class Account {
        private String name;
    }
}
