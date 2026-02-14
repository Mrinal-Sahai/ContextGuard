package io.contextguard.service;

import io.contextguard.dto.*;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PR-LEVEL RISK AGGREGATION ENGINE
 *
 * PURPOSE:
 * Aggregates file-level categorical risks into a statistically
 * normalized Pull Request risk score.
 *
 * PR Risk Formula:
 *
 * PR_Risk =
 *   0.35 × averageFileRisk
 * + 0.30 × peakFileRisk
 * + 0.20 × highRiskFileDensity
 * + 0.15 × criticalPathFileDensity
 *
 * DEFINITIONS:
 *
 * 1. Average File Risk
 *    → Mean numeric risk across all modified files
 *    → Represents overall instability level
 *
 * 2. Peak File Risk
 *    → Highest individual file risk
 *    → Captures single-point catastrophic failure risk
 *
 * 3. High-Risk File Density
 *    → Proportion of files classified HIGH or CRITICAL
 *    → Represents concentration of serious changes
 *
 * 4. Critical Path File Density
 *    → Proportion of files affecting business-critical paths
 *    → Represents systemic business impact exposure
 */
@Service
public class RiskScoringEngine {

    private static final double WEIGHT_AVG = 0.35;
    private static final double WEIGHT_PEAK = 0.30;
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
        double peakRisk = 0.0;
        int highRiskCount = 0;
        int criticalPathCount = 0;
        int publicAPICount = 0;

        for (FileChangeSummary file : files) {

            double numericRisk = mapRiskToNumeric(file.getRiskLevel());
            sumRisk += numericRisk;

            if (numericRisk > peakRisk) {
                peakRisk = numericRisk;
            }

            if (file.getRiskLevel() == RiskLevel.HIGH
                        || file.getRiskLevel() == RiskLevel.CRITICAL) {
                highRiskCount++;
            }

            if (file.getCriticalDetectionResult() != null
                        && file.getCriticalDetectionResult().isCritical()) {
                criticalPathCount++;
            }
            if (isPublicAPI(file)) {
                publicAPICount++;
            }
        }


        double averageRisk = sumRisk / totalFiles;
        double highRiskDensity = (double) highRiskCount / totalFiles;
        double criticalDensity = (double) criticalPathCount / totalFiles;
        double publicAPIDensity = (double) publicAPICount / totalFiles;

        double overallScore =
                (WEIGHT_AVG * averageRisk) +
                        (WEIGHT_PEAK * peakRisk) +
                        (WEIGHT_HIGH_DENSITY * highRiskDensity) +
                        (WEIGHT_CRITICAL_DENSITY * criticalDensity) +
                        (0.10 * publicAPIDensity);

        RiskLevel level = categorize(overallScore);

        RiskBreakdown breakdown = RiskBreakdown.builder()
                                          .averageRiskContribution(WEIGHT_AVG * averageRisk)
                                          .peakRiskContribution(WEIGHT_PEAK * peakRisk)
                                          .highRiskDensityContribution(WEIGHT_HIGH_DENSITY * highRiskDensity)
                                          .criticalPathDensityContribution(WEIGHT_CRITICAL_DENSITY * criticalDensity)
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
    private double mapRiskToNumeric(RiskLevel level) {
        return switch (level) {
            case LOW -> 0.1;
            case MEDIUM -> 0.4;
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

    private boolean isPublicAPI(FileChangeSummary file) {
        return file.getFilename().contains("/api/") ||
                       file.getFilename().contains("/controller/") ||
                       (file.getMethodChanges() != null &&
                                file.getMethodChanges().stream().anyMatch(m ->
                                                                                  m.getAnnotations() != null &&
                                                                                          m.getAnnotations().contains("@RestController")));
    }
}
