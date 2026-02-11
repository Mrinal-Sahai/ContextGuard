package io.contextguard.service;

import io.contextguard.dto.*;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Production-grade hierarchical PR risk engine.
 *
 * DESIGN PRINCIPLES:
 * - File risk is the foundational unit
 * - PR risk is derived statistically from file distribution
 * - No double counting of raw metrics
 * - Fully deterministic and auditable
 *
 * PR Risk Model:
 *
 * PR_Risk =
 *     0.40 × avgFileRisk +
 *     0.25 × maxFileRisk +
 *     0.20 × highRiskDensity +
 *     0.15 × criticalFileDensity
 *
 * All components normalized to [0,1]
 */
@Service
public class RiskScoringEngine {

    private static final double WEIGHT_AVG = 0.40;
    private static final double WEIGHT_MAX = 0.25;
    private static final double WEIGHT_HIGH_DENSITY = 0.20;
    private static final double WEIGHT_CRITICAL_DENSITY = 0.15;

    public RiskAssessment assessRisk(PRMetadata metadata, DiffMetrics metrics) {

        if (metrics.getFileChanges() == null || metrics.getFileChanges().isEmpty()) {
            return RiskAssessment.builder()
                           .overallScore(0.0)
                           .level(RiskLevel.LOW)
                           .breakdown(new RiskBreakdown())
                           .criticalFilesDetected(metrics.getCriticalFiles())
                           .build();
        }

        List<FileChangeSummary> files = metrics.getFileChanges();
        int totalFiles = files.size();

        // Convert file-level risk to numeric
        double sumRisk = 0.0;
        double maxRisk = 0.0;
        int highRiskCount = 0;
        int criticalCount = 0;

        for (FileChangeSummary file : files) {

            double fileRisk = mapRiskLevelToScore(file.getRiskLevel());
            sumRisk += fileRisk;

            if (fileRisk > maxRisk) {
                maxRisk = fileRisk;
            }

            if (file.getRiskLevel() == RiskLevel.HIGH
                        || file.getRiskLevel() == RiskLevel.CRITICAL) {
                highRiskCount++;
            }

            if (file.getCriticalDetectionResult() != null
                        && file.getCriticalDetectionResult().isCritical()) {
                criticalCount++;
            }
        }

        double avgRisk = sumRisk / totalFiles;
        double highRiskDensity = (double) highRiskCount / totalFiles;
        double criticalDensity = (double) criticalCount / totalFiles;

        // Weighted aggregation
        double overallScore =
                (WEIGHT_AVG * avgRisk) +
                        (WEIGHT_MAX * maxRisk) +
                        (WEIGHT_HIGH_DENSITY * highRiskDensity) +
                        (WEIGHT_CRITICAL_DENSITY * criticalDensity);

        RiskLevel level = categorize(overallScore);

        RiskBreakdown breakdown = RiskBreakdown.builder()
                                          .volumeContribution(WEIGHT_AVG * avgRisk)
                                          .complexityContribution(WEIGHT_MAX * maxRisk)
                                          .criticalPathContribution(WEIGHT_CRITICAL_DENSITY * criticalDensity)
                                          .churnContribution(WEIGHT_HIGH_DENSITY * highRiskDensity)
                                          .build();

        return RiskAssessment.builder()
                       .overallScore(round(overallScore))
                       .level(level)
                       .breakdown(breakdown)
                       .criticalFilesDetected(metrics.getCriticalFiles())
                       .build();
    }

    /**
     * Convert categorical file risk to numeric score.
     * These values represent increasing regression probability.
     */
    private double mapRiskLevelToScore(RiskLevel level) {
        return switch (level) {
            case LOW -> 0.25;
            case MEDIUM -> 0.50;
            case HIGH -> 0.75;
            case CRITICAL -> 1.0;
        };
    }

    /**
     * Categorize final PR risk.
     */
    private RiskLevel categorize(double score) {
        if (score < 0.25) return RiskLevel.LOW;
        if (score < 0.50) return RiskLevel.MEDIUM;
        if (score < 0.75) return RiskLevel.HIGH;
        return RiskLevel.CRITICAL;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
