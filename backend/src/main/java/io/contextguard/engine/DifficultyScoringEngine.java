package io.contextguard.engine;

import io.contextguard.dto.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Production-grade PR difficulty scoring engine.
 *
 * Difficulty = weighted sum of:
 *  - Size (total LOC changed)
 *  - Spread (files changed)
 *  - Cognitive load (avg per-file complexity delta + method changes)
 *  - Context switching (file type / language variety)
 *  - Risk concentration (fraction of HIGH/CRITICAL files)
 *  - Critical impact (fraction of critical files)
 *
 * All components normalized to [0,1] using a saturating function.
 * Returns DifficultyAssessment containing score, categorical level, breakdown,
 * and an estimated review time (minutes).
 */
@Service
public class DifficultyScoringEngine {

    // Tunable weights (sum to 1.0)
    private static final double WEIGHT_SIZE = 0.20;
    private static final double WEIGHT_SPREAD = 0.15;
    private static final double WEIGHT_COGNITIVE = 0.30;
    private static final double WEIGHT_CONTEXT = 0.10;
    private static final double WEIGHT_CONCENTRATION = 0.15;
    private static final double WEIGHT_CRITICAL_IMPACT = 0.10;

    // Saturation pivots: choose reasonable pivots for typical repos (configurable later)
    private static final int PIVOT_SIZE = 1000;         // lines changed at which score ≈ 0.5-0.66
    private static final int PIVOT_SPREAD = 30;         // files changed pivot
    private static final int PIVOT_COMPLEXITY = 20;     // avg per-file complexity delta pivot
    private static final int PIVOT_METHOD_CHANGES = 8;  // method changes pivot
    private static final int PIVOT_FILE_TYPES = 4;      // number of distinct file types (languages)
    private static final int PIVOT_HIGH_RISK_FILES = 5; // high-risk files pivot
    private static final int PIVOT_CRITICAL_FILES = 3;  // critical files pivot

    /**
     * Main entry.
     */
    public DifficultyAssessment assessDifficulty(PRMetadata metadata, DiffMetrics metrics) {
        if (metrics == null || metrics.getFileChanges() == null || metrics.getFileChanges().isEmpty()) {
            DifficultyBreakdown emptyBreakdown = DifficultyBreakdown.builder()
                                                         .sizeContribution(0.0)
                                                         .spreadContribution(0.0)
                                                         .cognitiveContribution(0.0)
                                                         .contextContribution(0.0)
                                                         .concentrationContribution(0.0)
                                                         .criticalImpactContribution(0.0)
                                                         .build();

            return DifficultyAssessment.builder()
                           .overallScore(0.0)
                           .level(DifficultyLevel.TRIVIAL)
                           .breakdown(emptyBreakdown)
                           .estimatedReviewMinutes(0)
                           .build();
        }

        List<FileChangeSummary> files = metrics.getFileChanges();
        int totalFiles = files.size();
        int totalLinesChanged = metrics.getLinesAdded() + metrics.getLinesDeleted();

        // Aggregate per-file signals
        double sumPerFileComplexity = 0.0;
        int totalMethodChanges = 0;
        int highRiskCount = 0;
        int criticalCount = 0;

        for (FileChangeSummary f : files) {
            sumPerFileComplexity += Math.abs(Objects.requireNonNullElse(f.getComplexityDelta(), 0));
            totalMethodChanges += (f.getMethodChanges() != null) ? f.getMethodChanges().size() : 0;
            if (f.getRiskLevel() == RiskLevel.HIGH || f.getRiskLevel() == RiskLevel.CRITICAL) {
                highRiskCount++;
            }
            if (f.getCriticalDetectionResult() != null && f.getCriticalDetectionResult().isCritical()) {
                criticalCount++;
            }
        }

        double avgPerFileComplexity = sumPerFileComplexity / (double) totalFiles;
        int fileTypeCount = metrics.getFileTypeDistribution() == null ? 0 : metrics.getFileTypeDistribution().size();

        // Normalized (saturating) component scores in [0,1]
        double sizeScore = saturatingNormalize(totalLinesChanged, PIVOT_SIZE);
        double spreadScore = saturatingNormalize(totalFiles, PIVOT_SPREAD);

        // Cognitive = mix of avg complexity and method-change density
        double complexityComponent = saturatingNormalize((int) Math.round(avgPerFileComplexity), PIVOT_COMPLEXITY);
        double methodChangeComponent = saturatingNormalize(totalMethodChanges, PIVOT_METHOD_CHANGES);
        double cognitiveScore = (0.75 * complexityComponent) + (0.25 * methodChangeComponent); // weighting within cognitive

        // Context switching cost: number of distinct file types (languages) and whether >1
        double fileTypesScore = saturatingNormalize(fileTypeCount, PIVOT_FILE_TYPES);
        // bonus penalty when multiple languages present (more than 1)
        double multiLanguagePenalty = (fileTypeCount > 1) ? 0.1 : 0.0;
        double contextScore = Math.min(1.0, fileTypesScore + multiLanguagePenalty);

        // Risk concentration + critical impact
        double highRiskDensity = saturatingNormalize(highRiskCount, PIVOT_HIGH_RISK_FILES);
        double criticalDensity = saturatingNormalize(criticalCount, PIVOT_CRITICAL_FILES);

        // Aggregate weighted difficulty score
        double overallScore =
                (WEIGHT_SIZE * sizeScore) +
                        (WEIGHT_SPREAD * spreadScore) +
                        (WEIGHT_COGNITIVE * cognitiveScore) +
                        (WEIGHT_CONTEXT * contextScore) +
                        (WEIGHT_CONCENTRATION * highRiskDensity) +
                        (WEIGHT_CRITICAL_IMPACT * criticalDensity);

        // clamp and round
        overallScore = Math.max(0.0, Math.min(1.0, overallScore));
        double roundedScore = Math.round(overallScore * 1000.0) / 1000.0;

        DifficultyLevel level = categorizeDifficulty(roundedScore);

        // Breakdown (per-component weighted contributions)
        DifficultyBreakdown breakdown = DifficultyBreakdown.builder()
                                                .sizeContribution(WEIGHT_SIZE * sizeScore)
                                                .spreadContribution(WEIGHT_SPREAD * spreadScore)
                                                .cognitiveContribution(WEIGHT_COGNITIVE * cognitiveScore)
                                                .contextContribution(WEIGHT_CONTEXT * contextScore)
                                                .concentrationContribution(WEIGHT_CONCENTRATION * highRiskDensity)
                                                .criticalImpactContribution(WEIGHT_CRITICAL_IMPACT * criticalDensity)
                                                .build();

        int estimatedMinutes = estimateReviewTimeRounded(roundedScore,
                totalFiles,
                totalLinesChanged,
                avgPerFileComplexity,
                totalMethodChanges,
                highRiskCount,
                criticalCount);

        return DifficultyAssessment.builder()
                       .overallScore(roundedScore)
                       .level(level)
                       .breakdown(breakdown)
                       .estimatedReviewMinutes(estimatedMinutes)
                       .build();
    }

    /**
     * Saturating normalization function returning values in [0,1] with diminishing returns.
     * Using a simple rational function x/(pivot + x) gives smooth saturation.
     */
    private double saturatingNormalize(int value, int pivot) {
        if (pivot <= 0) return Math.min(1.0, value > 0 ? 1.0 : 0.0);
        return (double) value / (pivot + (double) value);
    }

    /**
     * Difficulty categories.
     */
    private DifficultyLevel categorizeDifficulty(double score) {
        if (score < 0.20) return DifficultyLevel.TRIVIAL;
        if (score < 0.40) return DifficultyLevel.EASY;
        if (score < 0.60) return DifficultyLevel.MODERATE;
        if (score < 0.80) return DifficultyLevel.HARD;
        return DifficultyLevel.VERY_HARD;
    }

    /**
     * Realistic review time estimator (minutes). This is deterministic and conservative.
     *
     * Formula rationale:
     * - Base per-file time (reading + context): 4 minutes per file
     * - LOC overhead: 0.75 minutes per 100 LOC changed
     * - Complexity overhead: avgPerFileComplexity * 3 minutes (each complexity point multiplies reasoning effort)
     * - Method changes: +0.7 minutes per method changed (focus point)
     * - High-risk file penalty: +12 minutes per HIGH/CRITICAL file (deep inspection / testing)
     * - Critical file penalty: additional +10 minutes per critical file (architectural review)
     * - Global multiplier: 1 + overallScore * 0.5 (increase time when overall difficulty is higher)
     *
     * Conservative rounding applied.
     */
    private int estimateReviewTimeRounded(double overallScore,
                                          int totalFiles,
                                          int totalLocChanged,
                                          double avgPerFileComplexity,
                                          int totalMethodChanges,
                                          int highRiskCount,
                                          int criticalCount) {

        double basePerFileMinutes = 4.0;
        double baseTime = basePerFileMinutes * totalFiles;

        double locMinutes = (totalLocChanged / 100.0) * 0.75; // 0.75 minutes per 100 LOC

        double complexityMinutes = avgPerFileComplexity * 3.0;

        double methodMinutes = totalMethodChanges * 0.7;

        double highRiskMinutes = highRiskCount * 12.0;
        double criticalMinutes = criticalCount * 10.0;

        double subtotal = baseTime + locMinutes + complexityMinutes + methodMinutes + highRiskMinutes + criticalMinutes;

        double multiplier = 1.0 + (overallScore * 0.5); // up to 1.5x extra at score==1.0

        double minutes = subtotal * multiplier;

        // Always at least 1 minute, round up conservatively
        return (int) Math.max(1, Math.round(minutes));
    }
}
