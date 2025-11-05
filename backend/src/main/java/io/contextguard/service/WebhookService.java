package io.contextguard.service;

import io.contextguard.dto.webhook.*;
import io.contextguard.model.Review;
import io.contextguard.model.Snapshot;
import io.contextguard.repository.ReviewRepository;
import io.contextguard.repository.SnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class WebhookService {

    private final ReviewRepository reviewRepository;
    private final SnapshotRepository snapshotRepository;
    private final MinIOService minioService;

    @Transactional
    public Review processGitHubWebhook(GitHubWebhookPayload payload) {
        if (!"opened".equals(payload.getAction())) {
            log.info("Ignoring GitHub webhook action: {}", payload.getAction());
            return null;
        }

        Review review = reviewRepository.findByPlatformAndOwnerAndRepositoryAndExternalId(
                "github",
                payload.getRepository().getOwner().getLogin(),
                payload.getRepository().getName(),
                String.valueOf(payload.getPullRequest().getNumber())
        ).orElseGet(() -> Review.builder()
                                  .platform("github")
                                  .owner(payload.getRepository().getOwner().getLogin())
                                  .repository(payload.getRepository().getName())
                                  .externalId(String.valueOf(payload.getPullRequest().getNumber()))
                                  .build()
        );

        review.setTitle(payload.getPullRequest().getTitle());
        review.setAuthor(payload.getPullRequest().getUser().getLogin());
        review.setStatus("open");
        review.setLastActivityAt(java.time.LocalDateTime.now());
        review.setMetadata(Map.of(
                "html_url", payload.getPullRequest().getHtmlUrl(),
                "head_sha", payload.getPullRequest().getHead().getSha()
        ));

        review = reviewRepository.save(review);

        // Create snapshot
        createSnapshot(review, payload);

        return review;
    }

    @Transactional
    public Review processBeanstalkWebhook(BeanstalkWebhookPayload payload) {
        if (!"review.created".equals(payload.getEvent())) {
            log.info("Ignoring Beanstalk webhook event: {}", payload.getEvent());
            return null;
        }
        Review review = reviewRepository.findByPlatformAndOwnerAndRepositoryAndExternalId(
                "beanstalk",
                payload.getRepository().getAccount().getName(),
                payload.getRepository().getName(),
                payload.getReview().getId()
        ).orElseGet(() -> Review.builder()
                                  .platform("beanstalk")
                                  .owner(payload.getRepository().getAccount().getName())
                                  .repository(payload.getRepository().getName())
                                  .externalId(payload.getReview().getId())
                                  .build()
        );

        review.setTitle(payload.getReview().getTitle());
        review.setAuthor(payload.getReview().getAuthor().getName());
        review.setStatus("open");
        review.setLastActivityAt(java.time.LocalDateTime.now());
        review.setMetadata(Map.of(
                "changeset_url", payload.getReview().getChangesetUrl()
        ));
        review = reviewRepository.save(review);

        createSnapshot(review, payload);

        return review;
    }

    private void createSnapshot(Review review, GitHubWebhookPayload payload) {
        Snapshot snapshot = Snapshot.builder()
                                    .review(review)
                                    .prBody(payload.getPullRequest().getBody())
                                    .createdBy("webhook")
                                    .build();

        List<Map<String, String>> commits = List.of(
                Map.of("sha", payload.getPullRequest().getHead().getSha(),
                        "message", "Initial commit for PR")
        );
        snapshot.setCommitList(commits);
        snapshot.setTicketIds(extractTicketIds(payload.getPullRequest().getBody()));
        String diffKey = String.format("diffs/%s/%s/%s/%s.diff",
                review.getPlatform(), review.getOwner(),
                review.getRepository(), review.getExternalId());
        minioService.uploadDiff(diffKey, "Mock diff content");
        snapshot.setDiffUrl(diffKey);

        snapshotRepository.save(snapshot);
    }

    private void createSnapshot(Review review, BeanstalkWebhookPayload payload) {
        Snapshot snapshot = Snapshot.builder()
                                    .review(review)
                                    .prBody(payload.getReview().getDescription())
                                    .createdBy("webhook")
                                    .commitList(List.of(Map.of("message", payload.getReview().getTitle())))
                                    .ticketIds(extractTicketIds(payload.getReview().getDescription()))
                                    .build();

        String diffKey = String.format("diffs/%s/%s/%s/%s.diff",
                review.getPlatform(), review.getOwner(),
                review.getRepository(), review.getExternalId());
        minioService.uploadDiff(diffKey, "Mock diff content");
        snapshot.setDiffUrl(diffKey);

        snapshotRepository.save(snapshot);
    }

    private List<String> extractTicketIds(String text) {
        if (text == null) return List.of();

        List<String> tickets = new ArrayList<>();
        String[] patterns = {"JIRA-\\d+", "#\\d+", "[A-Z]+-\\d+"};

        for (String pattern : patterns) {
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile(pattern).matcher(text);
            while (matcher.find()) {
                tickets.add(matcher.group());
            }
        }

        return tickets;
    }

    public boolean validateGitHubSignature(String payload, String signature, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
            mac.init(secretKey);

            byte[] hash = mac.doFinal(payload.getBytes());
            String computed = "sha256=" + bytesToHex(hash);

            return computed.equals(signature);
        } catch (Exception e) {
            log.error("Failed to validate GitHub signature", e);
            return false;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
