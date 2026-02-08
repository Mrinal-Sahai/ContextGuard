package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pull Request difficulty assessment.
 *
 * Measures cognitive load and time required for review.
 * Separate from risk (which measures danger/impact).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DifficultyAssessment {

    private double overallScore;  // 0.0 - 1.0
    private DifficultyLevel level;  // TRIVIAL / EASY / MODERATE / HARD / VERY_HARD
    private DifficultyBreakdown breakdown;
    private int estimatedReviewMinutes;
}
