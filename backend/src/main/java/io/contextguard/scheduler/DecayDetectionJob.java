package io.contextguard.scheduler;

import io.contextguard.model.Review;
import io.contextguard.model.Snapshot;
import io.contextguard.repository.ReviewRepository;
import io.contextguard.repository.SnapshotRepository;
import io.contextguard.service.NotificationService;
import io.contextguard.service.ScoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;


@Component
@Slf4j
@RequiredArgsConstructor
public class DecayDetectionJob {

    private final ReviewRepository reviewRepository;
    private final SnapshotRepository snapshotRepository;
    private final ScoringService scoringService;
    private final NotificationService notificationService;

    @Value("${contextguard.scoring.decay-threshold:40}")
    private int decayThreshold;

    @Value("${contextguard.scoring.inactivity-hours:24}")
    private int inactivityHours;

    @Scheduled(cron = "0 0 * * * *") // Every hour
    public void detectStaleReviews() {
        log.info("Running decay detection job");

        LocalDateTime threshold = LocalDateTime.now().minusHours(inactivityHours);
        List<Review> staleReviews = reviewRepository.findStaleReviews(threshold);

        for (Review review : staleReviews) {
            processStaleReview(review);
        }

        log.info("Decay detection job completed. Processed {} reviews", staleReviews.size());
    }

    private void processStaleReview(Review review) {
        Snapshot latestSnapshot = snapshotRepository
                                          .findLatestByReviewId(review.getId())
                                          .orElse(null);

        if (latestSnapshot == null) {
            return;
        }

        int contextScore = scoringService.calculateContextScore(review, latestSnapshot);

        if (contextScore < decayThreshold) {
            log.info("Decay detected for review: {}/{}/{}/{} (score: {})",
                    review.getPlatform(), review.getOwner(),
                    review.getRepository(), review.getExternalId(), contextScore);

            notificationService.sendDecayAlert(review, contextScore);
        }
    }
}
