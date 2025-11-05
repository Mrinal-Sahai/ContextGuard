package io.contextguard.controller;

import io.contextguard.dto.*;
import io.contextguard.model.Review;
import io.contextguard.model.Snapshot;
import io.contextguard.repository.ReviewRepository;
import io.contextguard.repository.SnapshotRepository;
import io.contextguard.service.SnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewRepository reviewRepository;
    private final SnapshotRepository snapshotRepository;
    private final SnapshotService snapshotService;

    @GetMapping("/{platform}/{owner}/{repo}/{externalId}")
    public ResponseEntity<ReviewResponse> getReview(
            @PathVariable String platform,
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable String externalId) {

        Review review = reviewRepository
                                .findByPlatformAndOwnerAndRepositoryAndExternalId(
                                        platform, owner, repo, externalId)
                                .orElse(null);

        if (review == null) {
            return ResponseEntity.notFound().build();
        }

        Snapshot latestSnapshot = snapshotRepository
                                          .findLatestByReviewId(review.getId())
                                          .orElse(null);

        ReviewResponse response = ReviewResponse.builder()
                                          .id(review.getId())
                                          .platform(review.getPlatform())
                                          .owner(review.getOwner())
                                          .repository(review.getRepository())
                                          .externalId(review.getExternalId())
                                          .title(review.getTitle())
                                          .author(review.getAuthor())
                                          .status(review.getStatus())
                                          .createdAt(review.getCreatedAt())
                                          .lastActivityAt(review.getLastActivityAt())
                                          .metadata(review.getMetadata())
                                          .latestSnapshot(latestSnapshot != null ?
                                                                  buildSnapshotResponse(latestSnapshot) : null)
                                          .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{platform}/{owner}/{repo}/{externalId}/generate")
    public ResponseEntity<SnapshotResponse> generateSnapshot(
            @PathVariable String platform,
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable String externalId) {

        Review review = reviewRepository
                                .findByPlatformAndOwnerAndRepositoryAndExternalId(
                                        platform, owner, repo, externalId)
                                .orElse(null);

        if (review == null) {
            return ResponseEntity.notFound().build();
        }

        Snapshot latest = snapshotRepository
                                  .findLatestByReviewId(review.getId())
                                  .orElse(null);

        if (latest == null) {
            return ResponseEntity.notFound().build();
        }

        Snapshot processed = snapshotService.processSnapshot(latest);

        return ResponseEntity.ok(buildSnapshotResponse(processed));
    }

    private SnapshotResponse buildSnapshotResponse(Snapshot snapshot) {
        return SnapshotResponse.builder()
                       .id(snapshot.getId())
                       .summary(snapshot.getSummary())
                       .why(snapshot.getWhy())
                       .risks(snapshot.getRisks())
                       .reviewChecklist(snapshot.getReviewChecklist())
                       .contextScore(snapshot.getContextScore())
                       .createdBy(snapshot.getCreatedBy())
                       .createdAt(snapshot.getCreatedAt())
                       .build();
    }
}
