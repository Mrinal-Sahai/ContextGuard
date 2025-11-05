package io.contextguard.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.contextguard.dto.webhook.*;
import io.contextguard.model.Review;
import io.contextguard.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/webhook")
@Slf4j
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    @Value("${contextguard.webhook.github.secret}")
    private String githubSecret;

    @Value("${contextguard.webhook.beanstalk.secret}")
    private String beanstalkSecret;

    @PostMapping("/github")
    public ResponseEntity<?> handleGitHubWebhook(
            @RequestBody String rawPayload,
            @RequestHeader("X-Hub-Signature-256") String signature) {

        // Validate signature
        if (!webhookService.validateGitHubSignature(rawPayload, signature, githubSecret)) {
            log.warn("Invalid GitHub webhook signature");
            return ResponseEntity.status(401).body("Invalid signature");
        }
        try {
            GitHubWebhookPayload payload = parseGitHubPayload(rawPayload);
            Review review = webhookService.processGitHubWebhook(payload);
            if (review == null) {
                return ResponseEntity.ok("Event ignored");
            }
            log.info("Processed GitHub webhook for PR: {}/{}/{}",
                    review.getOwner(), review.getRepository(), review.getExternalId());
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "review_id", review.getId()
            ));

        } catch (Exception e) {
            log.error("Failed to process GitHub webhook", e);
            return ResponseEntity.status(500).body("Processing failed");
        }
    }

    @PostMapping("/beanstalk")
    public ResponseEntity<?> handleBeanstalkWebhook(
            @RequestBody String rawPayload,
            @RequestHeader("X-Beanstalk-Signature") String signature) {
        try {
            BeanstalkWebhookPayload payload = parseBeanstalkPayload(rawPayload);
            Review review = webhookService.processBeanstalkWebhook(payload);

            if (review == null) {
                return ResponseEntity.ok("Event ignored");
            }

            return ResponseEntity.ok(Map.of("status", "success"));

        } catch (Exception e) {
            log.error("Failed to process Beanstalk webhook", e);
            return ResponseEntity.status(500).body("Processing failed");
        }
    }

    private GitHubWebhookPayload parseGitHubPayload(String raw) throws JsonProcessingException {
        // Use Jackson ObjectMapper
        return new com.fasterxml.jackson.databind.ObjectMapper()
                       .readValue(raw, GitHubWebhookPayload.class);
    }

    private BeanstalkWebhookPayload parseBeanstalkPayload(String raw) throws JsonProcessingException {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                       .readValue(raw, BeanstalkWebhookPayload.class);
    }
}

