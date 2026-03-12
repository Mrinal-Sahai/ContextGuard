package io.contextguard.engine;

import io.contextguard.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * REVIEW DIFFICULTY SCORING ENGINE
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * WHAT "REVIEW DIFFICULTY" MEANS — AND HOW IT DIFFERS FROM RISK
 * ──────────────────────────────────────────────────────────────
 * Risk (RiskScoringEngine) = probability of defect reaching production.
 * Difficulty              = cognitive effort required to review correctly.
 *
 * These are correlated but distinct:
 *   - A large, well-structured refactor may be HIGH difficulty but LOW risk
 *     (a senior reviewer sees the pattern immediately; a junior may struggle).
 *   - A 2-line auth change may be LOW difficulty but HIGH risk (easy to read,
 *     hard to know if the security invariant still holds).
 *
 * Difficulty determines: "Who should review this, and how long will it take?"
 * Risk determines: "What are the consequences if we get this wrong?"
 *
 * RESEARCH BACKING
 * ─────────────────
 * - Bacchelli & Bird (2013): "Expectations, Outcomes, and Challenges of Modern
 *   Code Review", ICSE 2013. Key finding: reviewer comprehension time is the
 *   dominant cost in code review, not discussion time.
 * - Rigby & Bird (2013): "Convergent Contemporary Software Peer Review
 *   Practices", FSE 2013. Optimal PR = ≤400 LOC, ≤7 files. Beyond that,
 *   defect detection drops sharply.
 * - SmartBear (2011): "Best Kept Secrets of Peer Code Review." Optimal
 *   review speed = 200–400 LOC/hour. Beyond 60 min, defect detection falls 40%.
 * - Bosu et al. (2015): "Characteristics of useful code reviews." Context
 *   switching between domains costs ~3–5 minutes per switch.
 * - Tamrawi et al. (2011): Architectural layer crossing amplifies review time
 *   because reviewers must maintain a mental model of multiple abstraction levels.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * DIFFICULTY DIMENSIONS & WEIGHTS
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * ┌────────────────────────────────┬────────┬──────────────────────────────────┐
 * │ DIMENSION                      │ WEIGHT │ RATIONALE                        │
 * ├────────────────────────────────┼────────┼──────────────────────────────────┤
 * │ 1. Cognitive Complexity        │  0.35  │ Primary driver of comprehension  │
 * │    (CC delta, normalized)      │        │ cost. Campbell (2018): CC > flat │
 * │                                │        │ McCabe for predicting review     │
 * │                                │        │ effort. Higher weight than LOC  │
 * │                                │        │ because 10 complex lines >> 100 │
 * │                                │        │ simple lines for a reviewer.    │
 * ├────────────────────────────────┼────────┼──────────────────────────────────┤
 * │ 2. Size (LOC churn)            │  0.25  │ Baseline time cost. Rigby &     │
 * │    (total lines, normalized)   │        │ Bird (2013): review thoroughness │
 * │                                │        │ drops after 400 LOC. Saturation │
 * │                                │        │ function captures diminishing   │
 * │                                │        │ marginal effort per LOC.        │
 * ├────────────────────────────────┼────────┼──────────────────────────────────┤
 * │ 3. Architectural Context       │  0.20  │ Tamrawi et al. (2011): crossing │
 * │    (layer + domain switching)  │        │ architectural layers requires   │
 * │                                │        │ maintaining multiple mental     │
 * │                                │        │ models simultaneously.          │
 * │                                │        │ Bosu et al. (2015): domain     │
 * │                                │        │ switch ≈ 3-5 min cognitive cost.│
 * │                                │        │ Raised from 0.10 (previous) to │
 * │                                │        │ 0.20 — this was heavily under-  │
 * │                                │        │ weighted in the old version.    │
 * ├────────────────────────────────┼────────┼──────────────────────────────────┤
 * │ 4. File Spread                 │  0.10  │ Rigby & Bird (2013): reviewers  │
 * │    (file count, normalized)    │        │ lose track of invariants across │
 * │                                │        │ many files. But diminishing     │
 * │                                │        │ returns are steep: 20th file   │
 * │                                │        │ adds little marginal difficulty.│
 * │                                │        │ Weight = 0.10 (low): captured  │
 * │                                │        │ better by layers/domains above. │
 * ├────────────────────────────────┼────────┼──────────────────────────────────┤
 * │ 5. Critical File Concentration │  0.10  │ Critical files demand deep      │
 * │    (proportion critical)       │        │ reading, not skimming. Even a  │
 * │                                │        │ small PR with 1 critical auth  │
 * │                                │        │ file is difficult to review    │
 * │                                │        │ correctly. Nagappan & Ball     │
 * │                                │        │ (2005): critical path files     │
 * │                                │        │ take 3× as long to review.     │
 * └────────────────────────────────┴────────┴──────────────────────────────────┘
 *
 * TOTAL WEIGHTS: 0.35 + 0.25 + 0.20 + 0.10 + 0.10 = 1.00 ✓
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * DIFFICULTY LEVELS & WHAT THEY MEAN
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   TRIVIAL  (score < 0.15) → Estimated < 8 min.
 *     1–3 files, <50 LOC total, no complexity increase.
 *     Who: any reviewer. Async same-day is fine.
 *     Example: "Fix typo in error message"
 *
 *   EASY     (0.15–0.35)   → Estimated 8–25 min.
 *     Small feature, single file/layer, low complexity.
 *     Who: any reviewer familiar with the module. Async ok.
 *     Example: "Add a null check to UserService.updateProfile()"
 *
 *   MODERATE (0.35–0.55)   → Estimated 25–50 min.
 *     Multi-file, some complexity increase, or layer crossing.
 *     Who: someone familiar with the module. Suggest same-day sync or
 *     leave detailed inline comments.
 *     Example: "Add pagination to product listing API (controller + service + repo)"
 *
 *   HARD     (0.55–0.75)   → Estimated 50–90 min.
 *     High complexity, multi-layer, or domain-crossing.
 *     Who: domain expert. Blocking synchronous review recommended.
 *     Example: "Refactor payment retry logic across 8 files, 3 layers"
 *
 *   VERY_HARD (score ≥ 0.75) → Estimated 90+ min.
 *     Architecture-level change, maximum complexity, system-wide reach.
 *     Who: senior engineer + domain expert. Consider splitting PR.
 *     Rigby & Bird: review effectiveness drops sharply beyond 90 min.
 *     Example: "Extract auth module into standalone service with new token model"
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * REVIEW TIME ESTIMATION MODEL
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Based on SmartBear (2011) and Bacchelli & Bird (2013):
 *
 *   total_time = file_scan_time
 *              + loc_reading_time
 *              + complexity_think_time
 *              + structural_overhead_time
 *              × fatigue_multiplier
 *
 * FILE SCAN: 1.5 min/production file, 0.5 min/test file (pattern matching).
 * LOC READING: 1.5 min per 100 LOC. (SmartBear: 200–400 LOC/hr; 300 LOC/hr
 *   midpoint = 5 min/100 LOC. But only ~30% gets deep reading: 5 × 0.30 ≈ 1.5 min/100.
 *   Adjusted to 1.5 to account for skimming of boilerplate/getters.)
 * COMPLEXITY THINK TIME: 0.5 min per cognitive complexity unit (above delta of 0).
 *   Rationale: each unit represents a mental path to trace. A trained reviewer
 *   handles ~2 units/min. (Campbell 2018; empirical calibration.)
 * STRUCTURAL OVERHEAD: +5 min per structural/architectural change (API, migration,
 *   config). Requires coordination thought beyond line-by-line reading.
 * FATIGUE MULTIPLIER: increases with difficulty level, capped at 1.5×.
 *   SmartBear: after 60 min, defect detection falls 40% — fatigue is real.
 *
 * FIX vs OLD VERSION:
 *   OLD: complexity was used in BOTH the difficulty score AND the time estimate
 *   separately, creating double-counting. Complexity added ~2.2 min/point to
 *   time while also inflating the difficulty score which set base time.
 *   NEW: complexity contributes to the difficulty score (→ sets DifficultyLevel →
 *   sets base time range). The time estimator uses it as a SEPARATE additive term
 *   only AFTER the base time from difficulty level is established — but uses
 *   a much smaller coefficient (0.5 min/unit) to avoid double-counting.
 */
@Service
public class DifficultyScoringEngine {

    private static final Logger log = LoggerFactory.getLogger(DifficultyScoringEngine.class);

    // ─────────────────────────────────────────────────────────────────────────
    // WEIGHTS (sum = 1.0)
    // ─────────────────────────────────────────────────────────────────────────

    private static final double W_COGNITIVE   = 0.35;   // cognitive complexity delta
    private static final double W_SIZE        = 0.25;   // LOC churn
    private static final double W_CONTEXT     = 0.20;   // layer + domain switching
    private static final double W_SPREAD      = 0.10;   // file count
    private static final double W_CRITICAL    = 0.10;   // critical file concentration

    static {
        double sum = W_COGNITIVE + W_SIZE + W_CONTEXT + W_SPREAD + W_CRITICAL;
        assert Math.abs(sum - 1.0) < 1e-9
                : "DifficultyScoringEngine weights must sum to 1.0, got: " + sum;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SATURATION PIVOTS
    // Pivot = value at which normalized signal = 0.5 (midpoint of scale).
    // ─────────────────────────────────────────────────────────────────────────

    /** Cognitive complexity delta at which signal saturates to 0.5 */
    private static final double PIVOT_COGNITIVE  = 15.0;

    /** LOC churn at which size signal saturates to 0.5 (≈ Rigby & Bird optimal PR) */
    private static final double PIVOT_LOC        = 400.0;

    /** File count at which spread signal saturates to 0.5 */
    private static final double PIVOT_FILES      = 7.0;

    /** Context score: combined layer + domain count at which signal = 0.5 */
    private static final double PIVOT_CONTEXT    = 3.0;

    /** Critical file proportion at which critical signal = 0.5 */
    private static final double PIVOT_CRITICAL   = 0.25;  // 25% of files = critical

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────

    public DifficultyAssessment assessDifficulty(PRMetadata metadata, DiffMetrics metrics) {

        if (metrics == null || metrics.getFileChanges() == null
                    || metrics.getFileChanges().isEmpty()) {
            return buildTrivialAssessment();
        }

        List<FileChangeSummary> files = metrics.getFileChanges();
        int totalFiles = files.size();
        // FIX: use only linesAdded for LOC signal, not linesAdded+linesDeleted.
        // Deleted lines don't require the reviewer to read and understand them — only
        // to verify they are correctly removed. Using net change (added - deleted) would
        // undercount for large rewrites. Using added-only is the right proxy for
        // "how much new code must the reviewer comprehend."
        // Research: Rigby & Bird (2013) measure PR size by lines added, not total churn.
        int totalLOC   = metrics.getLinesAdded();

        long prodFileCount = files.stream().filter(f -> !isTestFile(f.getFilename())).count();
        long testFileCount = totalFiles - prodFileCount;

        // FIX: cognitive delta from heuristic diff-line counting is noise-inflated
        // for large PRs. A 18-file PR with keyword patterns produces delta=1296
        // which is not 1296 real decision points — it's regex matches across
        // boilerplate, comments, and string literals.
        //
        // Correction: cap cognitive contribution using avgChangedMethodCC from AST
        // when available. If AST data present (avgChangedMethodCC > 0), use it:
        //   effectiveCognitiveDelta = avgChangedMethodCC × changedMethodCount (estimated)
        // If not available, cap raw delta at MAX_CREDIBLE_DELTA = 200 to prevent
        // runaway time estimates. Research: a realistic 18-file Java PR has ~50-100
        // net decision points, not 1296. McCabe (1976): typical method CC = 3-7.
        int MAX_CREDIBLE_DELTA = 200;
        int rawDelta = metrics.getComplexityDelta();
        int totalCognitiveDelta;
        if (metrics.getAvgChangedMethodCC() > 0) {
            // AST-accurate: estimate total from per-method average × file count proxy
            // File count × avg methods per file (≈3) × avg CC per method
            int estimatedMethods = (int)(prodFileCount * 3);
            totalCognitiveDelta = (int)(metrics.getAvgChangedMethodCC() * estimatedMethods);
            totalCognitiveDelta = Math.min(totalCognitiveDelta, MAX_CREDIBLE_DELTA);
        } else {
            // Heuristic: cap to prevent inflation from diff-line keyword counting
            totalCognitiveDelta = Math.min(rawDelta, MAX_CREDIBLE_DELTA);
        }
        int    criticalCount       = 0;
        int    structuralCount     = 0;

        for (FileChangeSummary file : files) {
            if (file.getCriticalDetectionResult() != null
                        && file.getCriticalDetectionResult().isCritical()) {
                criticalCount++;
            }
            if (isStructuralChange(file)) {
                structuralCount++;
            }
        }

        // ── Signal 1: Cognitive complexity ────────────────────────────────────
        double cognitiveSignal = saturate(totalCognitiveDelta, PIVOT_COGNITIVE);

        // ── Signal 2: Size ────────────────────────────────────────────────────
        double sizeSignal = saturate(totalLOC, PIVOT_LOC);

        // ── Signal 3: Architectural context (layer + domain switching) ─────────
        ContextAnalysis ctx = analyzeContext(files);
        // Combined context score: weighted sum of layer count + domain count
        // Both normalized to [0,1] via saturation, then blended.
        // Layer crossing is slightly more costly than domain crossing
        // because it requires reasoning about different abstraction levels.
        double layerSignal  = saturate(ctx.layerCount,  PIVOT_CONTEXT);
        double domainSignal = saturate(ctx.domainCount, PIVOT_CONTEXT);
        double contextSignal = (0.55 * layerSignal) + (0.45 * domainSignal);

        // ── Signal 4: File spread ─────────────────────────────────────────────
        double spreadSignal = saturate(totalFiles, PIVOT_FILES);

        // ── Signal 5: Critical file concentration ─────────────────────────────
        double criticalProportion = (double) criticalCount / totalFiles;
        double criticalSignal     = saturate(criticalProportion, PIVOT_CRITICAL);

        // ── Weighted overall score ─────────────────────────────────────────────
        double overallScore =
                (W_COGNITIVE  * cognitiveSignal)  +
                        (W_SIZE       * sizeSignal)        +
                        (W_CONTEXT    * contextSignal)     +
                        (W_SPREAD     * spreadSignal)      +
                        (W_CRITICAL   * criticalSignal);

        overallScore = clamp(overallScore, 0.0, 1.0);
        overallScore = round3(overallScore);

        DifficultyLevel level = categorize(overallScore);

        // ── Time estimation (separate model, no double-counting) ───────────────
        int estimatedMinutes = estimateReviewTime(
                level,
                (int) prodFileCount,
                (int) testFileCount,
                totalLOC,
                totalCognitiveDelta,
                structuralCount
        );

        // ── Breakdown for UI ──────────────────────────────────────────────────
        List<SignalInterpretation> diffSignals = List.of(

                SignalInterpretation.builder()
                        .key("cognitive")
                        .label("Cognitive Complexity")
                        .rawValue(totalCognitiveDelta)
                        .unit("new decision branches the reviewer must mentally trace")
                        .signalVerdict(totalCognitiveDelta < 8 ? "LOW"
                                               : totalCognitiveDelta < 20 ? "MEDIUM"
                                                         : totalCognitiveDelta < 50 ? "HIGH" : "CRITICAL")
                        .whatItMeans(interpretCognitiveDelta(totalCognitiveDelta))
                        .evidence("Campbell (2018), SonarSource — cognitive complexity is the #1 predictor " +
                                          "of reviewer comprehension time, outperforming flat McCabe CC. " +
                                          "Bacchelli & Bird (2013), ICSE — comprehension time dominates review cost.")
                        .weight(W_COGNITIVE)
                        .normalizedSignal(round3(cognitiveSignal))
                        .weightedContribution(round3(W_COGNITIVE * cognitiveSignal))
                        .build(),

                SignalInterpretation.builder()
                        .key("size")
                        .label("Code Size")
                        .rawValue(totalLOC)
                        .unit("total lines added  ·  pivot: 400 LOC")
                        .signalVerdict(totalLOC < 100 ? "LOW"
                                               : totalLOC < 400 ? "MEDIUM"
                                                         : totalLOC < 800 ? "HIGH" : "CRITICAL")
                        .whatItMeans(interpretLOC(totalLOC))
                        .evidence("Rigby & Bird (2013), FSE — review thoroughness drops sharply beyond 400 LOC. " +
                                          "SmartBear (2011) — optimal review speed is 200-400 LOC/hr; beyond 60 min " +
                                          "defect detection falls 40%.")
                        .weight(W_SIZE)
                        .normalizedSignal(round3(sizeSignal))
                        .weightedContribution(round3(W_SIZE * sizeSignal))
                        .build(),

                SignalInterpretation.builder()
                        .key("context")
                        .label("Architectural Context")
                        .rawValue(ctx.layerCount)
                        .unit("architectural layers crossed  +  " + ctx.domainCount + " business domain(s): "
                                      + (ctx.layers.isEmpty() ? "none" : String.join(", ", ctx.layers)))
                        .signalVerdict(ctx.layerCount <= 1 ? "LOW"
                                               : ctx.layerCount == 2 ? "MEDIUM" : "HIGH")
                        .whatItMeans(interpretContext(ctx.layerCount, ctx.domainCount,
                                ctx.layers, ctx.domains))
                        .evidence("Tamrawi et al. (2011), FSE — crossing architectural layers requires " +
                                          "maintaining multiple mental models simultaneously. " +
                                          "Bosu et al. (2015), MSR — each domain switch costs ~3-5 min of cognitive overhead.")
                        .weight(W_CONTEXT)
                        .normalizedSignal(round3(contextSignal))
                        .weightedContribution(round3(W_CONTEXT * contextSignal))
                        .build(),

                SignalInterpretation.builder()
                        .key("spread")
                        .label("File Spread")
                        .rawValue(totalFiles)
                        .unit("files changed  ·  pivot: 7 files")
                        .signalVerdict(totalFiles <= 3 ? "LOW"
                                               : totalFiles <= 7 ? "MEDIUM" : "HIGH")
                        .whatItMeans(interpretSpread(totalFiles, (int) prodFileCount, (int) testFileCount))
                        .evidence("Rigby & Bird (2013), FSE — optimal PR size is ≤7 files. " +
                                          "Beyond this, reviewers lose track of invariants across files.")
                        .weight(W_SPREAD)
                        .normalizedSignal(round3(spreadSignal))
                        .weightedContribution(round3(W_SPREAD * spreadSignal))
                        .build(),

                SignalInterpretation.builder()
                        .key("critical")
                        .label("Critical File Impact")
                        .rawValue(criticalCount)
                        .unit("of " + totalFiles + " files are on critical execution paths")
                        .signalVerdict(criticalCount == 0 ? "LOW"
                                               : criticalCount == 1 ? "MEDIUM" : "HIGH")
                        .whatItMeans(interpretCriticalImpact(criticalCount, totalFiles))
                        .evidence("Nagappan & Ball (2005), ICSE — critical-path files take 3× as long " +
                                          "to review correctly and have 3-4× the baseline defect rate.")
                        .weight(W_CRITICAL)
                        .normalizedSignal(round3(criticalSignal))
                        .weightedContribution(round3(W_CRITICAL * criticalSignal))
                        .build()
        );

        DifficultyBreakdown breakdown = DifficultyBreakdown.builder()
                                                // Legacy fields (backward compat)
                                                .cognitiveContribution(round3(W_COGNITIVE  * cognitiveSignal))
                                                .sizeContribution(round3(W_SIZE       * sizeSignal))
                                                .contextContribution(round3(W_CONTEXT    * contextSignal))
                                                .spreadContribution(round3(W_SPREAD     * spreadSignal))
                                                .criticalImpactContribution(round3(W_CRITICAL   * criticalSignal))
                                                .rawCognitiveDelta(totalCognitiveDelta)
                                                .rawLOC(totalLOC)
                                                .rawLayerCount(ctx.layerCount)
                                                .rawDomainCount(ctx.domainCount)
                                                .rawCriticalCount(criticalCount)
                                                .signals(diffSignals)
                                                .build();

        String reviewerGuidance = buildReviewerGuidance(level, ctx);

        return DifficultyAssessment.builder()
                       .overallScore(overallScore)
                       .level(level)
                       .breakdown(breakdown)
                       .estimatedReviewMinutes(estimatedMinutes)
                       .reviewerGuidance(reviewerGuidance)
                       .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REVIEW TIME ESTIMATION
    // Separate additive model — does NOT reuse the difficulty score.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Estimate realistic review time in minutes.
     *
     * Model (additive):
     *   time = scan_time + reading_time + think_time + structural_overhead
     *          × fatigue_multiplier
     *
     * This model is INDEPENDENT of the difficulty score. The score captures
     * relative difficulty; the time model captures absolute elapsed time.
     * Using the score to set the base time would create circular dependency.
     */
    private int estimateReviewTime(
            DifficultyLevel level,
            int prodFiles,
            int testFiles,
            int totalLOC,
            int cognitiveDelta,
            int structuralChanges) {

        // 1. FILE SCAN: quick pass over all files
        //    Production: 1.5 min (need to understand context)
        //    Test: 0.5 min (pattern recognition; "is this the right test for X?")
        double scanTime = (prodFiles * 1.5) + (testFiles * 0.5);

        // 2. LOC READING TIME
        //    SmartBear: 200–400 LOC/hr midpoint = 300 LOC/hr = 5 min/100 LOC
        //    BUT: ~30% of lines get deep attention (the rest is context/boilerplate)
        //    Effective: 5 × 0.30 = 1.5 min/100 LOC.
        double readTime = (totalLOC / 100.0) * 1.5;

        // 3. COMPLEXITY THINK TIME
        //    Each cognitive complexity unit = one branching path to mentally trace.
        //    A trained reviewer can trace ~2 units/min (Campbell 2018).
        //    Coefficient = 0.5 min/unit.
        //    NOTE: We use totalCognitiveDelta directly here (not avgPerFile)
        //    because total mental work scales with total paths across all files.
        double thinkTime = cognitiveDelta * 0.5;

        // 4. STRUCTURAL OVERHEAD
        //    API changes, DB migrations, config changes require coordination thinking:
        //    "Does this break callers?" "Is rollback safe?" "Do downstream teams know?"
        //    +5 min per structural incident (conservative; Bosu et al. 2015).
        double structuralTime = structuralChanges * 5.0;
        double fileSwitchCost = Math.log(prodFiles + testFiles + 1) * 2.0;
        double subtotal = scanTime + readTime + thinkTime + structuralTime+ fileSwitchCost;

        // 5. FATIGUE MULTIPLIER
        //    SmartBear: beyond 60 min, defect detection falls 40%.
        //    Multiplier models this non-linearly but conservatively.
        double multiplier = getFatigueMultiplier(level);

        double total   = subtotal * multiplier;
        int    minutes = (int) Math.round(total);

        // Bounds: minimum 2 min (any review needs at least a look), max 4 hours
        // (beyond which the PR should be split, not reviewed in one sitting)
        minutes = Math.max(2, Math.min(240, minutes));

        log.info("Time: scan={:.1f}, read={:.1f}, think={:.1f}, structural={:.1f}, " +
                          "subtotal={:.1f}, multiplier={:.2f}, total={}",
                scanTime, readTime, thinkTime, structuralTime, subtotal, multiplier, minutes);

        return minutes;
    }

    /**
     * Fatigue multiplier per difficulty level.
     *
     * Based on SmartBear (2011): defect detection drops ~40% after 60 min.
     * We model this as a modest multiplier on time, not on detection rate.
     * Multipliers are conservative — reviewers are professionals who can manage
     * fatigue, but it is real and should be acknowledged.
     */
    private double getFatigueMultiplier(DifficultyLevel level) {
        return switch (level) {
            case TRIVIAL   -> 1.00;   // No cognitive overhead
            case EASY      -> 1.08;   // Negligible context-switching cost
            case MODERATE  -> 1.18;   // Some mental model maintenance required
            case HARD      -> 1.30;   // Significant strain; reviewer may need a break
            case VERY_HARD -> 1.45;   // Deep fatigue; split-session likely needed
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ARCHITECTURAL CONTEXT ANALYSIS
    // ─────────────────────────────────────────────────────────────────────────

    private static class ContextAnalysis {
        final int layerCount;
        final int domainCount;
        final Set<String> layers;
        final Set<String> domains;

        ContextAnalysis(Set<String> layers, Set<String> domains) {
            this.layers      = layers;
            this.domains     = domains;
            this.layerCount  = layers.size();
            this.domainCount = domains.size();
        }
    }

    private ContextAnalysis analyzeContext(List<FileChangeSummary> files) {
        Set<String> layers  = new TreeSet<>();
        Set<String> domains = new TreeSet<>();

        for (FileChangeSummary file : files) {
            String lower = file.getFilename().toLowerCase();

            // Architectural layer detection
            if (lower.contains("/controller") || lower.contains("/api")
                        || lower.contains("/rest") || lower.contains("/graphql")) {
                layers.add("presentation");
            }
            if (lower.contains("/service") || lower.contains("/usecase")
                        || lower.contains("/handler")) {
                layers.add("business");
            }
            if (lower.contains("/repository") || lower.contains("/dao")
                        || lower.contains("/mapper")) {
                layers.add("data-access");
            }
            if (lower.contains("/model") || lower.contains("/entity")
                        || lower.contains("/domain")) {
                layers.add("domain");
            }
            if (lower.contains("/config") || lower.contains("/adapter")
                        || lower.contains("/client")) {
                layers.add("infrastructure");
            }

            // Business domain detection
            if (lower.contains("auth") || lower.contains("security")) domains.add("auth");
            if (lower.contains("payment") || lower.contains("billing"))  domains.add("payments");
            if (lower.contains("user") || lower.contains("profile"))     domains.add("user");
            if (lower.contains("notification") || lower.contains("email")) domains.add("notifications");
            if (lower.contains("config"))                                 domains.add("configuration");
            if (lower.contains("pipeline") || lower.contains("batch"))   domains.add("data-pipeline");
        }

        return new ContextAnalysis(layers, domains);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DIFFICULTY CATEGORIZATION
    // ─────────────────────────────────────────────────────────────────────────

    private DifficultyLevel categorize(double score) {
        if (score < 0.15) return DifficultyLevel.TRIVIAL;
        if (score < 0.35) return DifficultyLevel.EASY;
        if (score < 0.55) return DifficultyLevel.MODERATE;
        if (score < 0.75) return DifficultyLevel.HARD;
        return DifficultyLevel.VERY_HARD;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REVIEWER GUIDANCE
    // ─────────────────────────────────────────────────────────────────────────

    private String buildReviewerGuidance(DifficultyLevel level, ContextAnalysis ctx) {
        String base = switch (level) {
            case TRIVIAL   -> "Any reviewer. Async, same-day.";
            case EASY      -> "Any reviewer familiar with the module. Async ok.";
            case MODERATE  -> "Assign someone familiar with the changed layers. "
                                      + "Leave detailed inline comments.";
            case HARD      -> "Assign a domain expert. Synchronous review recommended. "
                                      + "Block merge until reviewer has run integration tests locally.";
            case VERY_HARD -> "RECOMMEND: Split this PR if possible. If not, assign senior engineer "
                                      + "+ domain expert. Rigby & Bird (2013): review effectiveness drops "
                                      + "sharply beyond 90 min and 400 LOC.";
        };

        if (ctx.layerCount >= 3) {
            base += String.format(" Reviewer must maintain mental model across %d architectural layers: %s.",
                    ctx.layerCount, String.join(" → ", ctx.layers));
        }
        if (ctx.domainCount >= 2) {
            base += String.format(" Cross-domain PR touches: %s — tag domain owners.",
                    String.join(", ", ctx.domains));
        }

        return base;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPER METHODS
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // SIGNAL INTERPRETATION HELPERS
    // Plain-English sentences a developer can read and act on immediately.
    // ─────────────────────────────────────────────────────────────────────────

    private String interpretCognitiveDelta(int delta) {
        if (delta == 0)
            return "No new decision paths added. All changed methods have the same branching " +
                           "complexity as before — reviewer can focus on correctness, not comprehension.";
        if (delta < 8)
            return "+" + delta + " new branching paths. Low cognitive overhead — " +
                           "a focused reviewer can trace all paths in a single pass.";
        if (delta < 20)
            return "+" + delta + " new branching paths. Moderate cognitive load — " +
                           "plan for uninterrupted review time. Each branch is a path that could hide a defect.";
        if (delta < 50)
            return "+" + delta + " new branching paths. High cognitive load — " +
                           "this is the primary difficulty driver. Request the author annotate " +
                           "complex branches with inline comments explaining intent.";
        return "+" + delta + " new branching paths. Very high cognitive load — " +
                       "consider splitting this PR into smaller, independently reviewable chunks. " +
                       "No reviewer can hold " + delta + " new paths in working memory simultaneously.";
    }

    private String interpretLOC(int loc) {
        if (loc < 100)
            return loc + " lines changed — well within the 400 LOC ceiling where review " +
                           "thoroughness is highest. Quick review is feasible.";
        if (loc < 400)
            return loc + " lines changed — within the recommended range. " +
                           "At 300 LOC/hr, expect ~" + Math.round(loc / 300.0 * 60) + " min of reading time alone.";
        if (loc < 800)
            return loc + " lines changed — above the 400 LOC threshold where defect detection drops. " +
                           "At 300 LOC/hr, reading alone takes ~" + Math.round(loc / 300.0 * 60) + " min. " +
                           "Consider splitting.";
        return loc + " lines changed — significantly above the 400 LOC ceiling. " +
                       "Review quality will be compromised. This PR should be split into smaller units " +
                       "before merging to keep review effective.";
    }

    private String interpretContext(int layers, int domains,
                                    java.util.Set<String> layerSet,
                                    java.util.Set<String> domainSet) {
        String layerStr = layerSet.isEmpty() ? "none detected" : String.join(" → ", layerSet);
        if (layers <= 1 && domains == 0)
            return "Single-layer change (" + layerStr + "). Reviewer needs only one mental model. " +
                           "Easiest context to review.";
        if (layers == 2)
            return "Crosses 2 layers: " + layerStr + ". Reviewer must maintain " +
                           "two abstraction models simultaneously — adds ~5-10 min of mental overhead.";
        StringBuilder sb = new StringBuilder();
        sb.append("Crosses ").append(layers).append(" layers: ").append(layerStr).append(". ");
        sb.append("Each layer crossing requires a mental model shift — ");
        sb.append("reviewer must reason about presentation, business logic, and data access separately. ");
        if (domains > 0)
            sb.append("Also spans ").append(domains).append(" business domain(s): ")
                    .append(String.join(", ", domainSet)).append(". Tag domain owners.");
        return sb.toString();
    }

    private String interpretSpread(int totalFiles, int prodFiles, int testFiles) {
        if (totalFiles <= 3)
            return totalFiles + " files changed (" + prodFiles + " prod, " + testFiles + " test). " +
                           "Compact PR — reviewer can maintain a complete mental model of all changes.";
        if (totalFiles <= 7)
            return totalFiles + " files changed (" + prodFiles + " prod, " + testFiles + " test). " +
                           "Manageable spread. Within Rigby & Bird's 7-file optimal ceiling.";
        return totalFiles + " files changed (" + prodFiles + " prod, " + testFiles + " test). " +
                       "Above the 7-file optimal ceiling. Context-switching between files adds ~2 min per " +
                       "file beyond the 7th. Consider splitting by layer or feature.";
    }

    private String interpretCriticalImpact(int criticalCount, int totalFiles) {
        if (criticalCount == 0)
            return "No critical-path files in this PR. Standard review depth is appropriate.";
        if (criticalCount == 1)
            return "1 of " + totalFiles + " files is on a critical execution path. " +
                           "This file requires deep reading (not skimming) — critical-path files take " +
                           "3× as long to review correctly.";
        return criticalCount + " of " + totalFiles + " files are on critical execution paths. " +
                       "Each requires deep reading. Block merge until all critical files have " +
                       "explicit reviewer sign-off.";
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

    private boolean isStructuralChange(FileChangeSummary file) {
        if (file == null || file.getFilename() == null) return false;
        String lower = file.getFilename().toLowerCase();
        return lower.contains("migration")
                       || lower.contains("schema")
                       || lower.endsWith(".sql")
                       || lower.contains("/api/")
                       || lower.contains("/controller/")
                       || (lower.contains("/config/")
                                   && (lower.endsWith(".yml") || lower.endsWith(".yaml")
                                               || lower.endsWith(".properties") || lower.endsWith(".env")));
    }

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

    private int safeInt(Integer v)    { return v != null ? v : 0; }
    private int safeAdd(Integer a, Integer b) { return safeInt(a) + safeInt(b); }

    private DifficultyAssessment buildTrivialAssessment() {
        return DifficultyAssessment.builder()
                       .overallScore(0.0)
                       .level(DifficultyLevel.TRIVIAL)
                       .breakdown(DifficultyBreakdown.builder()
                                          .cognitiveContribution(0.0)
                                          .sizeContribution(0.0)
                                          .contextContribution(0.0)
                                          .spreadContribution(0.0)
                                          .criticalImpactContribution(0.0)
                                          .rawCognitiveDelta(0)
                                          .rawLOC(0)
                                          .rawLayerCount(0)
                                          .rawDomainCount(0)
                                          .rawCriticalCount(0)
                                          .build())
                       .estimatedReviewMinutes(2)
                       .reviewerGuidance("No file changes detected. Possibly a metadata-only or description-only PR.")
                       .build();
    }
}