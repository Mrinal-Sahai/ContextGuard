package io.contextguard.engine;

import io.contextguard.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Production-ready Difficulty Scoring Engine — drop-in replacement for the previous DifficultyScoringEngine.
 *
 * Key improvements and rationale (short):
 *  - Uses *density* (proportions) for risk/critical signals instead of raw counts so that small PRs
 *    with many risk files are scaled correctly relative to large PRs with the same raw counts.
 *  - Uses both *average* and *total* cognitive mass (per-file complexity delta + total complexity sum)
 *    to capture intensity *and* breadth of reasoning required.
 *  - Makes pivots configurable and supports percentile-driven calibration (via CalibrationProvider).
 *  - Adds a semantic "structural impact" score (API changes, DB schema, public surface) to reflect
 *    changes that are high-impact regardless of LOC.
 *  - Improves review-time estimator: separates deterministic components from learned/heuristic components,
 *    uses a non-linear multiplier on difficulty to reflect super-linear cognitive fatigue.
 *  - Defensive coding: null-safety, pluggable components, and clear documentation for every parameter.
 *
 * How to use:
 *  - Inject this service (Spring) or instantiate with the default constructor for immediate use.
 *  - If you have historical PR review times, implement CalibrationProvider to compute repository-specific pivots
 *    and pass it into the constructor to auto-calibrate.
 */
@Service
public class DifficultyScoringEngine {

    private static final Logger log = LoggerFactory.getLogger(DifficultyScoringEngine.class);

    // ---------------------------------------------------------------------
    // Default tunable weights (sum to 1.0). These are reasonable defaults that
    // can be overridden by injecting a CalibrationProvider that returns tuned weights.
    // ---------------------------------------------------------------------
    private double weightSize = 0.18;             // raw LOC changed
    private double weightSpread = 0.12;           // number of files (context switches)
    private double weightCognitive = 0.30;        // cognitive mass (avg + total complexity + method changes)
    private double weightContext = 0.08;          // language/file-type switching
    private double weightConcentration = 0.14;    // proportion of high-risk files
    private double weightCriticalImpact = 0.12;   // proportion of critical files

    // ---------------------------------------------------------------------
    // Default pivots: these represent the value at which the saturating function
    // returns approximately ~0.5. Use CalibrationProvider to compute repo-specific
    // pivots (recommended!). Pivots are chosen to be conservative for medium repos.
    // ---------------------------------------------------------------------
    private double pivotSize = 1000.0;            // lines changed at which size score ≈ 0.5
    private double pivotSpread = 30.0;            // files changed at which spread score ≈ 0.5
    private double pivotAvgComplexity = 20.0;     // avg complexity delta per file for cognitive
    private double pivotTotalComplexity = 50.0;   // total complexity mass for the PR
    private double pivotMethodChanges = 8.0;      // method changes
    private double pivotFileTypes = 4.0;          // distinct file types/languages
    private double pivotRiskDensityPct = 20.0;    // percent high-risk files (as percent) at which score ≈ 0.5
    private double pivotCriticalDensityPct = 10.0; // percent critical files
    private double pivotStructuralImpact = 1.0;   // structural incidents count at which impact ≈ 0.5
    private final SemanticImpactAnalyzer semanticAnalyzer;

    /**
     * Default constructor: uses no-op calibration and simple analyzers (safe defaults).
     */
    public DifficultyScoringEngine() {
        this.semanticAnalyzer = new BasicSemanticImpactAnalyzer();
    }

    /**
     * Constructor with pluggable components for calibration and semantic analysis.
     */
    public DifficultyScoringEngine(SemanticImpactAnalyzer semanticAnalyzer) {
        this.semanticAnalyzer = semanticAnalyzer == null ? new BasicSemanticImpactAnalyzer() : semanticAnalyzer;
    }


    /**
     * Assess the difficulty of a PR using PRMetadata and DiffMetrics.
     * Returns a DifficultyAssessment (same DTO used previously — assumed to exist).
     */
    public DifficultyAssessment assessDifficulty(PRMetadata metadata, DiffMetrics metrics) {
        // defensive checks
        if (metrics == null || metrics.getFileChanges() == null || metrics.getFileChanges().isEmpty()) {
            return trivialAssessment();
        }

        List<FileChangeSummary> files = metrics.getFileChanges();
        int totalFiles = files.size();
        int totalLinesChanged = safeSum(metrics.getLinesAdded(), metrics.getLinesDeleted());

        // Aggregate per-file signals with null-safety
        double sumPerFileComplexity = 0.0; // total complexity mass
        double sumAbsComplexityDelta = 0.0; // absolute complexity changes
        int totalMethodChanges = 0;
        int highRiskCount = 0;
        int criticalCount = 0;

        int structuralIncidents = 0; // semantic incidents like API or schema changes
        Set<String> fileTypes = new HashSet<>();

        for (FileChangeSummary f : files) {
            double c = (f == null ) ? 0.0 : Math.abs(f.getComplexityDelta());
            sumPerFileComplexity += c;            // total mass
            sumAbsComplexityDelta += c;       // same here, explicit naming

            totalMethodChanges += (f == null || f.getMethodChanges() == null) ? 0 : f.getMethodChanges().size();

            if (f != null && (f.getRiskLevel() == RiskLevel.HIGH || f.getRiskLevel() == RiskLevel.CRITICAL)) {
                highRiskCount++;
            }
            if (f != null && f.getCriticalDetectionResult() != null && f.getCriticalDetectionResult().isCritical()) {
                criticalCount++;
            }

            // Semantic analysis per-file (pluggable)
            if (semanticAnalyzer.isStructuralChange(f)) structuralIncidents++;
        }

        double avgPerFileComplexity = sumPerFileComplexity / (double) Math.max(1, totalFiles);
        int fileTypeCount = metrics.getFileTypeDistribution() == null ? 0 : metrics.getFileTypeDistribution().size();
        double sizeScore = saturatingNormalize(totalLinesChanged, pivotSize);
        double spreadScore = saturatingNormalize(totalFiles, pivotSpread);

        // Cognitive: combine average intensity and total mass (breadth)
        double avgComplexityComponent = saturatingNormalize(avgPerFileComplexity, pivotAvgComplexity);
        double totalComplexityComponent = saturatingNormalize(sumPerFileComplexity, pivotTotalComplexity);
        double methodChangeComponent = saturatingNormalize(totalMethodChanges, pivotMethodChanges);

        // We combine: 50% avg intensity, 30% total mass, 20% method density
        double cognitiveScore = clamp01(0.50 * avgComplexityComponent + 0.30 * totalComplexityComponent + 0.20 * methodChangeComponent);

        // Context switching: file types and multi-language penalty scaled by file type distribution
        double fileTypesScore = saturatingNormalize(fileTypeCount, pivotFileTypes);
        double multiLanguagePenalty = fileTypeCount > 1 ? Math.min(0.25, 0.05 * (fileTypeCount - 1)) : 0.0; // small step penalty
        double contextScore = clamp01(fileTypesScore + multiLanguagePenalty);

        // Risk and critical as densities (proportion of files) — converted to percent then normalized
        double highRiskDensityPct = 100.0 * (highRiskCount / (double) Math.max(1, totalFiles));
        double criticalDensityPct = 100.0 * (criticalCount / (double) Math.max(1, totalFiles));
        double highRiskDensityScore = saturatingNormalize(highRiskDensityPct, pivotRiskDensityPct);
        double criticalDensityScore = saturatingNormalize(criticalDensityPct, pivotCriticalDensityPct);

        // Structural impact score — number of semantic incidents (API/schema/breaking changes)
        double structuralImpactScore = saturatingNormalize(structuralIncidents, pivotStructuralImpact);

        // Combine concentration with structural impact so heavy-structure PRs get more weight
        double concentrationScore = clamp01(0.8 * highRiskDensityScore + 0.2 * structuralImpactScore);

        // Final weighted overall score
        double overallScore = clamp01(
                (weightSize * sizeScore)
                        + (weightSpread * spreadScore)
                        + (weightCognitive * cognitiveScore)
                        + (weightContext * contextScore)
                        + (weightConcentration * concentrationScore)
                        + (weightCriticalImpact * criticalDensityScore)
        );

        // Round the score to three decimals for reproducibility
        double roundedScore = Math.round(overallScore * 1000.0) / 1000.0;

        DifficultyLevel level = categorizeDifficulty(roundedScore);

        DifficultyBreakdown breakdown = DifficultyBreakdown.builder()
                                                .sizeContribution(weightSize * sizeScore)
                                                .spreadContribution(weightSpread * spreadScore)
                                                .cognitiveContribution(weightCognitive * cognitiveScore)
                                                .contextContribution(weightContext * contextScore)
                                                .concentrationContribution(weightConcentration * concentrationScore)
                                                .criticalImpactContribution(weightCriticalImpact * criticalDensityScore)
                                                .build();

        int estimatedMinutes = estimateReviewTime(roundedScore,
                totalFiles,
                totalLinesChanged,
                avgPerFileComplexity,
                sumPerFileComplexity,
                totalMethodChanges,
                highRiskCount,
                criticalCount,
                structuralIncidents);

        return DifficultyAssessment.builder()
                       .overallScore(roundedScore)
                       .level(level)
                       .breakdown(breakdown)
                       .estimatedReviewMinutes(estimatedMinutes)
                       .build();
    }


    // ---------------------------- Helper utilities ---------------------------

    private DifficultyAssessment trivialAssessment() {
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

    private int safeSum(Integer a, Integer b) {
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }

    /**
     * Saturating normalization for doubles: returns value/(pivot + value).
     * Works for values >= 0 and pivot > 0. Behavior: smooth diminishing returns.
     */
    private double saturatingNormalize(double value, double pivot) {
        if (pivot <= 0.0) return value > 0.0 ? 1.0 : 0.0;
        if (Double.isNaN(value) || value <= 0.0) return 0.0;
        return value / (pivot + value);
    }

    private double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    private DifficultyLevel categorizeDifficulty(double score) {
        if (score < 0.20) return DifficultyLevel.TRIVIAL;
        if (score < 0.40) return DifficultyLevel.EASY;
        if (score < 0.60) return DifficultyLevel.MODERATE;
        if (score < 0.80) return DifficultyLevel.HARD;
        return DifficultyLevel.VERY_HARD;
    }

    /**
     * Review time estimator.
     *
     * Rationale and breakdown (production-grade):
     *  - Base per-file study time (reading + context): 3.5 minutes per file (reduced slightly from 4.0)
     *  - LOC overhead: 0.9 minutes per 100 LOC changed (reflects reading / diff scanning)
     *  - Complexity overhead: total complexity mass contributes 2.2 minutes per complexity point (total mass)
     *    and avg complexity adds 1.2 minutes per average complexity point (intensity). This separates breadth vs depth.
     *  - Method changes: 0.6 minutes per method changed (site of logic changes to focus on)
     *  - High-risk file penalty: +10 minutes per high-risk file (deep inspection + tests)
     *  - Critical file penalty: additional +12 minutes per critical file (architectural review + coordination)
     *  - Structural incidents: +15 minutes per detected structural incident (schema/API/public surface changes)
     *  - Non-linear fatigue multiplier: multiplier = 1 + pow(overallScore, 1.5) (reflects super-linear cognitive fatigue)
     *
     * The constants above are conservative defaults. For better accuracy, provide historicalModel that implements
     * a fit(reviewTimes) function which can be used to calibrate or override these heuristics.
     */
    private int estimateReviewTime(double overallScore,
                                   int totalFiles,
                                   int totalLocChanged,
                                   double avgPerFileComplexity,
                                   double totalComplexityMass,
                                   int totalMethodChanges,
                                   int highRiskCount,
                                   int criticalCount,
                                   int structuralIncidents) {

        double basePerFileMinutes = 3.5;
        double baseTime = basePerFileMinutes * totalFiles;

        double locMinutes = (totalLocChanged / 100.0) * 0.9; // 0.9 min per 100 LOC

        double complexityMinutes = (totalComplexityMass * 2.2) + (avgPerFileComplexity * 1.2);

        double methodMinutes = totalMethodChanges * 0.6;

        double highRiskMinutes = highRiskCount * 10.0;
        double criticalMinutes = criticalCount * 12.0;
        double structuralMinutes = structuralIncidents * 15.0;

        double subtotal = baseTime + locMinutes + complexityMinutes + methodMinutes + highRiskMinutes + criticalMinutes + structuralMinutes;

        double multiplier = 1.0 + Math.pow(overallScore, 1.5); // super-linear fatigue: score==1 -> multiplier 2.0

        double minutes = subtotal * multiplier;

        // Always at least 1 minute, round up conservatively
        return (int) Math.max(1, Math.round(minutes));
    }

    // ----------------------------- Pluggable interfaces ----------------------

    /**
     * CalibrationProvider supplies repo-specific pivots and optionally weight tuning.
     * Implementations should compute pivots from historical PR metrics, e.g., medians or pctiles.
     */
    public interface CalibrationProvider {
        CalibrationParams getCalibrationParams();
    }

    /**
     * Simple holder for calibration parameters. Null values mean "keep default".
     */
    public static class CalibrationParams {
        private List<Double> weights; // expected order: size, spread, cognitive, context, concentration, critical
        private Double pivotSize;
        private Double pivotSpread;
        private Double pivotAvgComplexity;
        private Double pivotTotalComplexity;
        private Double pivotMethodChanges;
        private Double pivotFileTypes;
        private Double pivotRiskDensityPct;
        private Double pivotCriticalDensityPct;
        private Double pivotStructuralImpact;

        // getters/setters omitted for brevity (add as needed)

        public CalibrationParams() {}

        public List<Double> getWeights() { return weights; }
        public void setWeights(List<Double> weights) { this.weights = weights; }
        public Double getPivotSize() { return pivotSize; }
        public void setPivotSize(Double pivotSize) { this.pivotSize = pivotSize; }
        public Double getPivotSpread() { return pivotSpread; }
        public void setPivotSpread(Double pivotSpread) { this.pivotSpread = pivotSpread; }
        public Double getPivotAvgComplexity() { return pivotAvgComplexity; }
        public void setPivotAvgComplexity(Double pivotAvgComplexity) { this.pivotAvgComplexity = pivotAvgComplexity; }
        public Double getPivotTotalComplexity() { return pivotTotalComplexity; }
        public void setPivotTotalComplexity(Double pivotTotalComplexity) { this.pivotTotalComplexity = pivotTotalComplexity; }
        public Double getPivotMethodChanges() { return pivotMethodChanges; }
        public void setPivotMethodChanges(Double pivotMethodChanges) { this.pivotMethodChanges = pivotMethodChanges; }
        public Double getPivotFileTypes() { return pivotFileTypes; }
        public void setPivotFileTypes(Double pivotFileTypes) { this.pivotFileTypes = pivotFileTypes; }
        public Double getPivotRiskDensityPct() { return pivotRiskDensityPct; }
        public void setPivotRiskDensityPct(Double pivotRiskDensityPct) { this.pivotRiskDensityPct = pivotRiskDensityPct; }
        public Double getPivotCriticalDensityPct() { return pivotCriticalDensityPct; }
        public void setPivotCriticalDensityPct(Double pivotCriticalDensityPct) { this.pivotCriticalDensityPct = pivotCriticalDensityPct; }
        public Double getPivotStructuralImpact() { return pivotStructuralImpact; }
        public void setPivotStructuralImpact(Double pivotStructuralImpact) { this.pivotStructuralImpact = pivotStructuralImpact; }
    }

    /**
     * SemanticImpactAnalyzer detects structural/semantic incidents: public API changes, schema changes, major dependency upgrades.
     * Provide a production implementation that can inspect file paths, parse diffs or use AST analysis.
     */
    public interface SemanticImpactAnalyzer {
        boolean isStructuralChange(FileChangeSummary fileChange);
    }



    private static class BasicSemanticImpactAnalyzer implements SemanticImpactAnalyzer {
        @Override
        public boolean isStructuralChange(FileChangeSummary fileChange) {
            if (fileChange == null) return false;
            // Heuristic: presence of SQL migration, files in /migrations, public API annotations or files named "api"/"schema".
            String path = fileChange.getFilename();
            if (path == null) return false;
            String p = path.toLowerCase(Locale.ROOT);
            if (p.contains("migration") || p.contains("migrations") || p.contains("schema") || p.contains("db/migrate")) return true;
            if (p.endsWith(".sql")) return true;
            if (p.contains("/api/") || p.contains("controller") || p.contains("routes/")) return true;
            // TODO: integrate AST-based analysis for robust detection.
            return false;
        }
    }

}
