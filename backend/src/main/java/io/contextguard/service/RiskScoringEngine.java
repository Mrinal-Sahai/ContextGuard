package io.contextguard.service;

import io.contextguard.dto.*;
import org.springframework.stereotype.Service;

/**
 * Deterministic risk scoring using weighted heuristics.
 *
 * WHY HEURISTICS, NOT ML:
 * - Explainable: Can show exact formula in viva
 * - Testable: Unit tests verify score correctness
 * - Defensible: No "black box" ML model
 *
 * Risk Formula (0.0 - 1.0):
 * Risk = (0.35 × Volume) + (0.30 × Complexity) + (0.25 × CriticalPath) + (0.10 × Churn)
 *
 * Where:
 * - Volume = normalized(netLinesChanged / 500)
 * - Complexity = normalized(complexityDelta / 50)
 * - CriticalPath = 1.0 if any critical file touched, else 0.0
 * - Churn = normalized(filesChanged / 20)
 */
@Service
public class RiskScoringEngine {

    private static final double WEIGHT_VOLUME = 0.35;
    private static final double WEIGHT_COMPLEXITY = 0.30;
    private static final double WEIGHT_CRITICAL_PATH = 0.25;
    private static final double WEIGHT_CHURN = 0.10;

    public RiskAssessment assessRisk(PRMetadata metadata, DiffMetrics metrics) {

        // Normalize each factor to [0.0, 1.0]
        double volumeScore = normalize(Math.abs(metrics.getNetLinesChanged()), 500);
        double complexityScore = normalize(Math.abs(metrics.getComplexityDelta()), 50);
        double criticalPathScore = metrics.getCriticalFiles().isEmpty() ? 0.0 : 1.0;
        double churnScore = normalize(metrics.getTotalFilesChanged(), 20);

        // Weighted sum
        double overallScore =
                (WEIGHT_VOLUME * volumeScore) +
                        (WEIGHT_COMPLEXITY * complexityScore) +
                        (WEIGHT_CRITICAL_PATH * criticalPathScore) +
                        (WEIGHT_CHURN * churnScore);

        // Categorize risk
        RiskLevel level = categorizeRisk(overallScore);

        // Build breakdown for UI transparency
        RiskBreakdown breakdown = RiskBreakdown.builder()
                                          .volumeContribution(WEIGHT_VOLUME * volumeScore)
                                          .complexityContribution(WEIGHT_COMPLEXITY * complexityScore)
                                          .criticalPathContribution(WEIGHT_CRITICAL_PATH * criticalPathScore)
                                          .churnContribution(WEIGHT_CHURN * churnScore)
                                          .build();

        return RiskAssessment.builder()
                       .overallScore(overallScore)
                       .level(level)
                       .breakdown(breakdown)
                       .criticalFilesDetected(metrics.getCriticalFiles())
                       .build();
    }

    /**
     * Normalize value to [0.0, 1.0] using sigmoid-like function.
     * Caps at 1.0 for values exceeding threshold.
     */
    private double normalize(int value, int threshold) {
        return Math.min(1.0, (double) value / threshold);
    }

    /**
     * Map risk score to categorical level.
     */
    private RiskLevel categorizeRisk(double score) {
        if (score < 0.3) return RiskLevel.LOW;
        if (score < 0.6) return RiskLevel.MEDIUM;
        if (score < 0.8) return RiskLevel.HIGH;
        return RiskLevel.CRITICAL;
    }
}

