package io.contextguard.engine;


import io.contextguard.dto.*;
import org.springframework.stereotype.Service;

/**
 * Scores PR review difficulty (separate from risk).
 *
 * Difficulty Formula:
 * Difficulty = (0.30 × Size) + (0.25 × Spread) + (0.30 × Cognitive) + (0.15 × Context)
 *
 * Where:
 * - Size = lines changed / 1000
 * - Spread = files changed / 30
 * - Cognitive = complexity delta / 50
 * - Context = file type count / 5 (more types = harder to context-switch)
 */
@Service
public class DifficultyScoringEngine {

    private static final double WEIGHT_SIZE = 0.30;
    private static final double WEIGHT_SPREAD = 0.25;
    private static final double WEIGHT_COGNITIVE = 0.30;
    private static final double WEIGHT_CONTEXT = 0.15;

    public DifficultyAssessment assessDifficulty(PRMetadata metadata, DiffMetrics metrics) {

        // Normalize factors
        double sizeScore = normalize(
                Math.abs(metrics.getLinesAdded() + metrics.getLinesDeleted()), 1000);
        double spreadScore = normalize(metrics.getTotalFilesChanged(), 30);
        double cognitiveScore = normalize(Math.abs(metrics.getComplexityDelta()), 50);
        double contextScore = normalize(metrics.getFileTypeDistribution().size(), 5);

        // Weighted sum
        double overallScore =
                (WEIGHT_SIZE * sizeScore) +
                        (WEIGHT_SPREAD * spreadScore) +
                        (WEIGHT_COGNITIVE * cognitiveScore) +
                        (WEIGHT_CONTEXT * contextScore);

        DifficultyLevel level = categorizeDifficulty(overallScore);
        int estimatedMinutes = estimateReviewTime(overallScore, metrics);

        DifficultyBreakdown breakdown = DifficultyBreakdown.builder()
                                                .sizeContribution(WEIGHT_SIZE * sizeScore)
                                                .spreadContribution(WEIGHT_SPREAD * spreadScore)
                                                .cognitiveContribution(WEIGHT_COGNITIVE * cognitiveScore)
                                                .contextContribution(WEIGHT_CONTEXT * contextScore)
                                                .build();

        return DifficultyAssessment.builder()
                       .overallScore(overallScore)
                       .level(level)
                       .breakdown(breakdown)
                       .estimatedReviewMinutes(estimatedMinutes)
                       .build();
    }

    private double normalize(int value, int threshold) {
        return Math.min(1.0, (double) value / threshold);
    }

    private DifficultyLevel categorizeDifficulty(double score) {
        if (score < 0.2) return DifficultyLevel.TRIVIAL;
        if (score < 0.4) return DifficultyLevel.EASY;
        if (score < 0.6) return DifficultyLevel.MODERATE;
        if (score < 0.8) return DifficultyLevel.HARD;
        return DifficultyLevel.VERY_HARD;
    }

    /**
     * Estimate review time based on difficulty score and metrics.
     *
     * Base time: 2 minutes per file + 30 seconds per 100 LOC
     * Multiplier based on complexity
     */
    private int estimateReviewTime(double difficultyScore, DiffMetrics metrics) {
        int baseTime = (metrics.getTotalFilesChanged() * 2) +
                               ((metrics.getLinesAdded() + metrics.getLinesDeleted()) / 100);

        double multiplier = 1.0 + difficultyScore;

        return (int) (baseTime * multiplier);
    }
}
