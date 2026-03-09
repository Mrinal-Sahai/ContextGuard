package io.contextguard.service;

import io.contextguard.dto.*;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * PR-LEVEL RISK SCORING ENGINE
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * WHAT IS "PR RISK" — AND WHAT SHOULD A REVIEWER DO WITH IT?
 * ────────────────────────────────────────────────────────────
 * PR Risk answers: "What is the probability that merging this PR introduces
 * a defect that reaches production?"
 *
 * It is NOT just LOC count or file count. Those are inputs. Risk is the
 * aggregated signal from multiple dimensions, each weighted by its empirical
 * relationship to post-merge defect rates.
 *
 * Reviewer actions by risk level:
 *   LOW      → Standard review. One reviewer, async is fine.
 *   MEDIUM   → Review same-day. Verify test coverage for changed areas.
 *   HIGH     → Review synchronously. Run integration tests locally.
 *              Consider pairing with domain expert.
 *   CRITICAL → Block merge until: 2 reviewers approve, QA sign-off obtained,
 *              rollback strategy documented.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * FORMULA & WEIGHTS
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * PR_Risk = Σ (weight_i × signal_i)    where all signals ∈ [0, 1]
 *
 * ┌────────────────────────────────┬────────┬──────────────────────────────────┐
 * │ SIGNAL                         │ WEIGHT │ RESEARCH BASIS                   │
 * ├────────────────────────────────┼────────┼──────────────────────────────────┤
 * │ 1. Average file risk score     │  0.20  │ Mean risk = overall instability  │
 * │                                │        │ level. Forsgren et al. (2018):   │
 * │                                │        │ change failure rate correlates   │
 * │                                │        │ with mean file-level risk.       │
 * │                                │        │ Weight = 0.20 (was 0.30): mean  │
 * │                                │        │ can be pulled by low-risk files; │
 * │                                │        │ peak is a better catastrophe     │
 * │                                │        │ predictor — so peak gets more.  │
 * ├────────────────────────────────┼────────┼──────────────────────────────────┤
 * │ 2. Peak file risk score        │  0.30  │ The single most dangerous file   │
 * │                                │        │ dominates failure probability.   │
 * │                                │        │ Kim et al. (2008): defect-prone  │
 * │                                │        │ files account for 80% of bugs   │
 * │                                │        │ in a release (80/20 rule).      │
 * │                                │        │ Weight raised to 0.30 to capture │
 * │                                │        │ catastrophic single-point risk.  │
 * ├────────────────────────────────┼────────┼──────────────────────────────────┤
 * │ 3. Cognitive complexity delta  │  0.20  │ Campbell (2018): higher cognitive │
 * │    (normalized to [0,1])       │        │ complexity = harder to review    │
 * │                                │        │ correctly = more missed defects. │
 * │                                │        │ Banker et al. (1993): +1 CC      │
 * │                                │        │ unit ≈ +0.15 defects/KLOC.      │
 * │                                │        │ Weight = 0.20: complexity is a   │
 * │                                │        │ strong independent signal from   │
 * │                                │        │ file-level risk.                 │
 * ├────────────────────────────────┼────────┼──────────────────────────────────┤
 * │ 4. Critical path density       │  0.20  │ Proportion of files on critical  │
 * │    (proportion of critical     │        │ paths (auth, payments, DB).      │
 * │     files in PR)               │        │ Nagappan & Ball (2005): files on │
 * │                                │        │ critical execution paths have    │
 * │                                │        │ 3-4× the baseline defect rate.  │
 * │                                │        │ Weight = 0.20: concentrated in   │
 * │                                │        │ critical files → high risk even  │
 * │                                │        │ if overall average is medium.    │
 * ├────────────────────────────────┼────────┼──────────────────────────────────┤
 * │ 5. Test coverage gap signal    │  0.10  │ Proportion of changed files with │
 * │    (proportion of changed      │        │ NO associated test changes.      │
 * │     files without test changes)│        │ Mockus et al. (2000): changes   │
 * │                                │        │ without corresponding test       │
 * │                                │        │ changes have 2× post-merge bug  │
 * │                                │        │ rate. Weight = 0.10: it is a    │
 * │                                │        │ soft signal (test files may be   │
 * │                                │        │ in a separate PR or repo).      │
 * └────────────────────────────────┴────────┴──────────────────────────────────┘
 *
 * TOTAL WEIGHTS: 0.20 + 0.30 + 0.20 + 0.20 + 0.10 = 1.00 ✓
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * FILE-LEVEL RISK NUMERIC MAPPING
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * RiskLevel → numeric value for averaging/peaking:
 *
 *   LOW      → 0.15  (baseline; not 0.0 because even low-risk files carry
 *                      some residual probability of a mistake)
 *   MEDIUM   → 0.40  (moderate churn or moderate complexity; warrants
 *                      conscious reviewer attention)
 *   HIGH     → 0.70  (high churn + complexity or critical path; reviewer
 *                      must deep-read, not skim)
 *   CRITICAL → 1.00  (combination of maximum signals; all stops pulled)
 *
 * Why not 0, 0.33, 0.66, 1.0 (even spacing)?
 * Because the jump from LOW to MEDIUM is real but modest; the jump from
 * HIGH to CRITICAL is exponential in reviewer effort. The asymmetric
 * spacing (0.15 / 0.40 / 0.70 / 1.00) reflects this non-linearity.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * RISK LEVEL THRESHOLDS
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   0.00 – 0.24 → LOW       "Standard review"
 *   0.25 – 0.49 → MEDIUM    "Same-day review, verify test coverage"
 *   0.50 – 0.74 → HIGH      "Synchronous review, integration test locally"
 *   0.75 – 1.00 → CRITICAL  "Block until 2 approvals + QA sign-off"
 *
 * EXAMPLE SCENARIOS:
 *
 *   Scenario A — Small utility PR (2 files, low complexity):
 *     avg_risk=0.15, peak=0.15, complexity=0.05, critical_density=0, test_gap=0.5
 *     Score = 0.20×0.15 + 0.30×0.15 + 0.20×0.05 + 0.20×0 + 0.10×0.5
 *           = 0.030 + 0.045 + 0.010 + 0.000 + 0.050 = 0.135 → LOW ✓
 *
 *   Scenario B — Payment refactor (8 files, 3 critical, high complexity):
 *     avg_risk=0.55, peak=1.0, complexity=0.60, critical_density=0.375, test_gap=0.25
 *     Score = 0.20×0.55 + 0.30×1.0 + 0.20×0.60 + 0.20×0.375 + 0.10×0.25
 *           = 0.110 + 0.300 + 0.120 + 0.075 + 0.025 = 0.630 → HIGH ✓
 *
 *   Scenario C — Auth deletion + migration change (3 files):
 *     avg_risk=0.85, peak=1.0, complexity=0.40, critical_density=1.0, test_gap=1.0
 *     Score = 0.20×0.85 + 0.30×1.0 + 0.20×0.40 + 0.20×1.0 + 0.10×1.0
 *           = 0.170 + 0.300 + 0.080 + 0.200 + 0.100 = 0.850 → CRITICAL ✓
 */
@Service
public class RiskScoringEngine {

    // ─────────────────────────────────────────────────────────────────────────
    // WEIGHTS (sum = 1.0 exactly)
    // ─────────────────────────────────────────────────────────────────────────

    /** Mean file-level risk across all changed files */
    private static final double W_AVERAGE_RISK      = 0.20;

    /** Highest individual file risk in the PR */
    private static final double W_PEAK_RISK         = 0.30;

    /** Cognitive complexity delta, normalized */
    private static final double W_COMPLEXITY        = 0.20;

    /** Proportion of files on critical execution paths */
    private static final double W_CRITICAL_DENSITY  = 0.20;

    /** Proportion of changed prod files with no test changes */
    private static final double W_TEST_COVERAGE_GAP = 0.10;

    // Sanity-check at class load time
    static {
        double sum = W_AVERAGE_RISK + W_PEAK_RISK + W_COMPLEXITY
                             + W_CRITICAL_DENSITY + W_TEST_COVERAGE_GAP;
        assert Math.abs(sum - 1.0) < 1e-9
                : "RiskScoringEngine weights must sum to 1.0, got: " + sum;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // COMPLEXITY NORMALIZATION PIVOT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cognitive complexity delta at which normalized signal = 0.5.
     * A delta of 20 is a significant increase (Moderate–High boundary).
     * Using saturating function: signal = delta / (PIVOT + delta)
     */
    private static final double COMPLEXITY_PIVOT = 20.0;

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────

    public RiskAssessment assessRisk(PRMetadata metadata, DiffMetrics metrics) {

        if (metrics.getFileChanges() == null || metrics.getFileChanges().isEmpty()) {
            return emptyAssessment(metrics);
        }

        List<FileChangeSummary> files = metrics.getFileChanges();
        int totalFiles = files.size();

        // ── Signal 1 & 2: Average + Peak file risk ────────────────────────────
        double sumRisk   = 0.0;
        double peakRisk  = 0.0;
        int criticalCount = 0;

        for (FileChangeSummary file : files) {
            double numericRisk = mapRiskToNumeric(file.getRiskLevel());
            sumRisk += numericRisk;
            if (numericRisk > peakRisk) peakRisk = numericRisk;

            if (file.getCriticalDetectionResult() != null
                        && file.getCriticalDetectionResult().isCritical()) {
                criticalCount++;
            }
        }

        double averageRisk = sumRisk / totalFiles;

        // ── Signal 3: Cognitive complexity (normalized) ───────────────────────
        int rawComplexityDelta = Math.abs(metrics.getComplexityDelta());
        double complexitySignal = saturate(rawComplexityDelta, COMPLEXITY_PIVOT);

        // ── Signal 4: Critical path density ──────────────────────────────────
        double criticalDensity = (double) criticalCount / totalFiles;

        // ── Signal 5: Test coverage gap ───────────────────────────────────────
        // Heuristic: count production files that have no corresponding test file change
        long prodFiles = files.stream()
                                 .filter(f -> !isTestFile(f.getFilename()))
                                 .count();
        long prodFilesWithTestChanges = countProdFilesWithTestCoverage(files);
        double testCoverageGap = prodFiles > 0
                                         ? 1.0 - ((double) prodFilesWithTestChanges / prodFiles)
                                         : 0.0;

        // ── Weighted aggregation ──────────────────────────────────────────────
        double overallScore =
                (W_AVERAGE_RISK      * averageRisk)      +
                        (W_PEAK_RISK         * peakRisk)          +
                        (W_COMPLEXITY        * complexitySignal)  +
                        (W_CRITICAL_DENSITY  * criticalDensity)   +
                        (W_TEST_COVERAGE_GAP * testCoverageGap);

        overallScore = clamp(overallScore, 0.0, 1.0);
        RiskLevel level = categorize(overallScore);

        // ── Breakdown (for UI display) ─────────────────────────────────────────
        // Each signal gets a full SignalInterpretation so the UI can show:
        //   1. The raw measured value (not the weighted contribution)
        //   2. What that value means in plain English
        //   3. The research evidence behind the verdict threshold
        //   4. The exact formula: weight × normalizedSignal = weightedContribution
        // This replaces the old "show contribution%" approach which was uninterpretable.
        List<SignalInterpretation> signals = List.of(

                SignalInterpretation.builder()
                        .key("peakRisk")
                        .label("Peak File Risk")
                        .rawValue(round3(peakRisk))
                        .unit("/ 1.00  (LOW=0.15 · MEDIUM=0.40 · HIGH=0.70 · CRITICAL=1.00)")
                        .signalVerdict(categorize(peakRisk).name())
                        .whatItMeans(interpretPeakRisk(peakRisk, metrics.getCriticalFiles()))
                        .evidence("Kim et al. (2008), IEEE TSE — 80% of bugs come from 20% of files. " +
                                          "The single highest-risk file dominates failure probability.")
                        .weight(W_PEAK_RISK)
                        .normalizedSignal(round3(peakRisk))
                        .weightedContribution(round3(W_PEAK_RISK * peakRisk))
                        .build(),

                SignalInterpretation.builder()
                        .key("averageRisk")
                        .label("Average File Risk")
                        .rawValue(round3(averageRisk))
                        .unit("/ 1.00  mean across " + totalFiles + " changed files")
                        .signalVerdict(categorize(averageRisk).name())
                        .whatItMeans(interpretAverageRisk(averageRisk, totalFiles))
                        .evidence("Forsgren et al. (2018), Accelerate — change failure rate correlates " +
                                          "with mean file-level risk across the PR.")
                        .weight(W_AVERAGE_RISK)
                        .normalizedSignal(round3(averageRisk))
                        .weightedContribution(round3(W_AVERAGE_RISK * averageRisk))
                        .build(),

                SignalInterpretation.builder()
                        .key("complexity")
                        .label("Cyclomatic Complexity Δ")
                        .rawValue(rawComplexityDelta)
                        .unit("new decision branches added  (pivot: 20 units = 0.50 signal)")
                        .signalVerdict(interpretComplexityVerdict(rawComplexityDelta))
                        .whatItMeans(interpretComplexity(rawComplexityDelta))
                        .evidence("Banker et al. (1993), MIS Quarterly — each +1 CC unit ≈ +0.15 defects/KLOC. " +
                                          "Signal = delta / (20 + delta) so it never saturates above 1.0.")
                        .weight(W_COMPLEXITY)
                        .normalizedSignal(round3(complexitySignal))
                        .weightedContribution(round3(W_COMPLEXITY * complexitySignal))
                        .build(),

                SignalInterpretation.builder()
                        .key("criticalPath")
                        .label("Critical Path Density")
                        .rawValue(round3(criticalDensity * totalFiles))
                        .unit("of " + totalFiles + " files touch auth / payments / DB / config paths")
                        .signalVerdict(criticalDensity == 0 ? "LOW" : criticalDensity < 0.4 ? "MEDIUM" : "HIGH")
                        .whatItMeans(interpretCriticalDensity(criticalDensity, totalFiles, criticalCount))
                        .evidence("Nagappan & Ball (2005), ICSE — files on critical execution paths " +
                                          "have 3-4× the baseline defect rate.")
                        .weight(W_CRITICAL_DENSITY)
                        .normalizedSignal(round3(criticalDensity))
                        .weightedContribution(round3(W_CRITICAL_DENSITY * criticalDensity))
                        .build(),

                SignalInterpretation.builder()
                        .key("testGap")
                        .label("Test Coverage Gap")
                        .rawValue(round3(testCoverageGap * 100))
                        .unit("% of production files changed without corresponding test changes")
                        .signalVerdict(testCoverageGap >= 0.8 ? "CRITICAL"
                                               : testCoverageGap >= 0.5 ? "HIGH"
                                                         : testCoverageGap >= 0.2 ? "MEDIUM" : "LOW")
                        .whatItMeans(interpretTestGap(testCoverageGap, prodFiles))
                        .evidence("Mockus & Votta (2000), ICSM — code changes without test changes " +
                                          "have 2× the post-merge defect rate.")
                        .weight(W_TEST_COVERAGE_GAP)
                        .normalizedSignal(round3(testCoverageGap))
                        .weightedContribution(round3(W_TEST_COVERAGE_GAP * testCoverageGap))
                        .build()
        );

        RiskBreakdown breakdown = RiskBreakdown.builder()
                                          // Legacy weighted contribution fields (kept for backward compat)
                                          .averageRiskContribution(round3(W_AVERAGE_RISK * averageRisk))
                                          .peakRiskContribution(round3(W_PEAK_RISK * peakRisk))
                                          .complexityContribution(round3(W_COMPLEXITY * complexitySignal))
                                          .criticalPathDensityContribution(round3(W_CRITICAL_DENSITY * criticalDensity))
                                          .testCoverageGapContribution(round3(W_TEST_COVERAGE_GAP * testCoverageGap))
                                          // Raw values
                                          .rawAverageRisk(round3(averageRisk))
                                          .rawPeakRisk(round3(peakRisk))
                                          .rawComplexityDelta(rawComplexityDelta)
                                          .rawCriticalDensity(round3(criticalDensity))
                                          .rawTestCoverageGap(round3(testCoverageGap))
                                          // NEW: full signal interpretations for self-explanatory UI
                                          .signals(signals)
                                          .build();

        String reviewerGuidance = buildReviewerGuidance(level, breakdown);

        return RiskAssessment.builder()
                       .overallScore(round3(overallScore))
                       .level(level)
                       .breakdown(breakdown)
                       .criticalFilesDetected(metrics.getCriticalFiles())
                       .reviewerGuidance(reviewerGuidance)
                       .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RISK LEVEL MAPPING
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Map categorical file risk to numeric value for aggregation.
     *
     * Values are asymmetric by design:
     * LOW=0.15 (not 0) — all code has some residual risk.
     * The spacing widens at higher levels to reflect exponentially
     * greater reviewer effort required.
     */
    // ─────────────────────────────────────────────────────────────────────────
    // SIGNAL INTERPRETATION HELPERS
    // Each method returns a plain-English sentence a developer can act on.
    // Uses the actual numbers — not vague hedging.
    // ─────────────────────────────────────────────────────────────────────────

    private String interpretPeakRisk(double peakRisk, List<String> criticalFiles) {
        if (peakRisk >= 1.0) {
            String file = (criticalFiles != null && !criticalFiles.isEmpty())
                                  ? criticalFiles.get(0).substring(criticalFiles.get(0).lastIndexOf('/') + 1)
                                  : "a file";
            return "\"" + file + "\" scored CRITICAL (1.00). This single file drives the risk score. " +
                           "Deep line-by-line review required — do not skim.";
        } else if (peakRisk >= 0.70) {
            return "Highest-risk file scored HIGH (0.70). Requires careful review of all branches. " +
                           "Run integration tests for this file's callers.";
        } else if (peakRisk >= 0.40) {
            return "Highest-risk file scored MEDIUM (0.40). Moderate attention required — " +
                           "verify correctness of changed methods rather than skimming.";
        } else {
            return "All files scored LOW risk (≤0.15). No single file is a significant defect risk. " +
                           "Standard review is sufficient.";
        }
    }

    private String interpretAverageRisk(double avgRisk, int totalFiles) {
        String score = String.format("%.2f", avgRisk);
        if (avgRisk >= 0.55) {
            return "Mean risk across all " + totalFiles + " files is " + score +
                           " — majority of files are HIGH or CRITICAL. Assign a senior reviewer; " +
                           "every file needs attention, not just the obvious ones.";
        } else if (avgRisk >= 0.30) {
            return "Mean risk across all " + totalFiles + " files is " + score +
                           " — several files carry MEDIUM risk. Ensure full coverage of all files, " +
                           "not just the largest ones.";
        } else {
            return "Mean risk across all " + totalFiles + " files is " + score +
                           " — most files are LOW risk. The PR is broadly safe; " +
                           "focus attention on the highest-risk outlier(s).";
        }
    }

    private String interpretComplexity(int delta) {
        if (delta == 0) return "No cyclomatic complexity added. All changed methods are equally complex as before.";
        if (delta < 10)  return "+" + delta + " decision branches added — modest increase. " +
                                        "Each branch is a new path the reviewer must mentally trace and validate.";
        if (delta < 30)  return "+" + delta + " decision branches added — moderate increase. " +
                                        "Allocate extra focused time to trace all new conditional paths.";
        return "+" + delta + " decision branches added — significant increase. " +
                       "At +1 CC ≈ +0.15 defects/KLOC, this level of branching materially raises defect probability. " +
                       "Request the author add inline comments explaining each non-obvious branch.";
    }

    private String interpretComplexityVerdict(int delta) {
        if (delta < 5)  return "LOW";
        if (delta < 15) return "MEDIUM";
        if (delta < 40) return "HIGH";
        return "CRITICAL";
    }

    private String interpretCriticalDensity(double density, int total, int critical) {
        if (critical == 0)
            return "0 of " + total + " changed files touch auth, payments, DB, or config paths. " +
                           "No critical-path exposure in this PR.";
        if (density <= 0.25)
            return critical + " of " + total + " files touch critical paths. " +
                           "These file(s) require thorough review — critical-path files have 3-4× the baseline defect rate.";
        return critical + " of " + total + " files (" + Math.round(density * 100) + "%) touch critical paths. " +
                       "High concentration of security-sensitive changes. Consider splitting into separate PRs.";
    }

    private String interpretTestGap(double gap, long prodFiles) {
        if (gap == 0.0)
            return "All production files have corresponding test changes. " +
                           "Good test discipline — no coverage gap detected.";
        if (gap >= 1.0)
            return prodFiles + " production file(s) changed, 0 test files modified. " +
                           "100% coverage gap. Untested changes have 2× the post-merge defect rate. " +
                           "This is the most actionable item — add tests or explain why they exist elsewhere.";
        long uncovered = Math.round(gap * prodFiles);
        return uncovered + " of " + prodFiles + " production file(s) have no corresponding test changes (" +
                       Math.round(gap * 100) + "%). Each untested file doubles its defect probability. " +
                       "Prioritise adding tests for the highest-risk uncovered files.";
    }

    private double mapRiskToNumeric(RiskLevel level) {
        if (level == null) return 0.15;
        return switch (level) {
            case LOW      -> 0.15;
            case MEDIUM   -> 0.40;
            case HIGH     -> 0.70;
            case CRITICAL -> 1.00;
        };
    }

    /**
     * Classify overall score into risk level.
     *
     * Thresholds based on calibration targets (see class-level Scenario examples).
     * 0.50 = HIGH (not 0.75) because by the time half your score budget
     * is consumed, at least one serious signal must be present.
     */
    private RiskLevel categorize(double score) {
        if (score < 0.25) return RiskLevel.LOW;
        if (score < 0.50) return RiskLevel.MEDIUM;
        if (score < 0.75) return RiskLevel.HIGH;
        return RiskLevel.CRITICAL;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEST COVERAGE GAP HEURISTIC
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Count production files that have a corresponding test file also changed
     * in this PR. This is a heuristic — a test for "UserService" is assumed
     * to be "UserServiceTest", "UserServiceSpec", etc.
     *
     * Rationale: Mockus et al. (2000) found that code changes accompanied by
     * test changes have ~50% lower post-merge defect rates.
     */
    private long countProdFilesWithTestCoverage(List<FileChangeSummary> files) {
        List<FileChangeSummary> prodFiles = files.stream()
                                                    .filter(f -> !isTestFile(f.getFilename()))
                                                    .toList();

        List<FileChangeSummary> testFiles = files.stream()
                                                    .filter(f -> isTestFile(f.getFilename()))
                                                    .toList();

        if (testFiles.isEmpty()) return 0;

        return prodFiles.stream()
                       .filter(prod -> {
                           String baseName = extractBaseName(prod.getFilename()).toLowerCase();
                           return testFiles.stream().anyMatch(test ->
                                                                      test.getFilename().toLowerCase().contains(baseName));
                       })
                       .count();
    }

    private boolean isTestFile(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return lower.contains("/test/")
                       || lower.endsWith("test.java")
                       || lower.endsWith("spec.js")
                       || lower.endsWith("_test.py")
                       || lower.endsWith("spec.rb")
                       || lower.endsWith("test.ts");
    }

    private String extractBaseName(String path) {
        String[] parts = path.split("/");
        String filename = parts[parts.length - 1];
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REVIEWER GUIDANCE GENERATION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate actionable guidance string from risk level and breakdown.
     * This is the "so what?" that turns a score into an action.
     */
    private String buildReviewerGuidance(RiskLevel level, RiskBreakdown breakdown) {
        StringBuilder sb = new StringBuilder();

        switch (level) {
            case LOW -> sb.append("Standard review. Async approval is acceptable.");
            case MEDIUM -> sb.append("Review same-day. Verify test coverage for changed areas.");
            case HIGH -> sb.append("Synchronous review recommended. Run integration tests locally before approving.");
            case CRITICAL -> sb.append("BLOCK until: (1) 2 reviewers approve, (2) QA sign-off obtained, (3) rollback plan documented.");
        }

        // Append dominant signal explanation
        double peakContrib    = breakdown.getPeakRiskContribution();
        double complexContrib = breakdown.getComplexityContribution();
        double critContrib    = breakdown.getCriticalPathDensityContribution();
        double testContrib    = breakdown.getTestCoverageGapContribution();

        double maxContrib = Math.max(Math.max(peakContrib, complexContrib),
                Math.max(critContrib, testContrib));

        if (maxContrib == peakContrib && peakContrib > 0.1) {
            sb.append(String.format(" Primary driver: one or more files are individually HIGH/CRITICAL risk (peak=%.2f).",
                    breakdown.getRawPeakRisk()));
        } else if (maxContrib == complexContrib && complexContrib > 0.05) {
            sb.append(String.format(" Primary driver: significant cognitive complexity increase (+%d units).",
                    breakdown.getRawComplexityDelta()));
        } else if (maxContrib == critContrib && critContrib > 0.05) {
            sb.append(String.format(" Primary driver: %.0f%% of changed files are on critical execution paths.",
                    breakdown.getRawCriticalDensity() * 100));
        } else if (maxContrib == testContrib && testContrib > 0.05) {
            sb.append(String.format(" Primary driver: %.0f%% of production files changed without accompanying test changes.",
                    breakdown.getRawTestCoverageGap() * 100));
        }

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITIES
    // ─────────────────────────────────────────────────────────────────────────

    /** Saturating function: value / (pivot + value) → [0, 1) */
    private double saturate(double value, double pivot) {
        if (value <= 0 || pivot <= 0) return 0.0;
        return value / (pivot + value);
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private RiskAssessment emptyAssessment(DiffMetrics metrics) {
        return RiskAssessment.builder()
                       .overallScore(0.0)
                       .level(RiskLevel.LOW)
                       .breakdown(new RiskBreakdown())
                       .criticalFilesDetected(metrics.getCriticalFiles())
                       .reviewerGuidance("No file changes detected. Possibly a metadata-only PR.")
                       .build();
    }
}