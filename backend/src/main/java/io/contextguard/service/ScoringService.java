
package io.contextguard.service;

import io.contextguard.model.Review;
import io.contextguard.model.Snapshot;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class ScoringService {

    /**
     * Calculate context score based on recency, commits, diff size, and tickets
     * Formula from PROJECT_INTENT
     */
    public int calculateContextScore(Review review, Snapshot snapshot) {
        long hoursSinceCreation = ChronoUnit.HOURS.between(
                review.getCreatedAt(),
                LocalDateTime.now()
        );

        // Recency score: max(0, 100 - hours * 2)
        int recencyScore = Math.max(0, 100 - (int)(hoursSinceCreation * 2));

        // Commit count score: min(commits * 5, 30)
        int commitCount = snapshot.getCommitList() != null ?
                                  snapshot.getCommitList().size() : 0;
        int commitScore = Math.min(commitCount * 5, 30);

        // Diff size score (mock - would parse actual diff): min(files * 2, 20)
        int diffScore = 10; // Placeholder
        // Ticket score: 15 if has tickets, 0 otherwise
        int ticketScore = (snapshot.getTicketIds() != null &&
                                   !snapshot.getTicketIds().isEmpty()) ? 15 : 0;

        int totalScore = recencyScore + commitScore + diffScore + ticketScore;

        // Normalize to 0-100
        return Math.min(100, totalScore);
    }

    /**
     * Check if review has decayed (score < threshold AND inactive > hours)
     */
    public boolean hasDecayed(Review review, int scoreThreshold, int inactivityHours) {
        if (!review.getStatus().equals("open")) {
            return false;
        }

        long hoursSinceActivity = ChronoUnit.HOURS.between(
                review.getLastActivityAt() != null ?
                        review.getLastActivityAt() : review.getCreatedAt(),
                LocalDateTime.now()
        );

        // Would need to fetch latest snapshot to check score
        // Simplified here: just check inactivity
        return hoursSinceActivity > inactivityHours;
    }
}

