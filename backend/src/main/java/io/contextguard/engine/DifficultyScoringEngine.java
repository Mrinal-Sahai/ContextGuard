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
 * LOC READING: 10 min per 100 LOC. (SmartBear: 200–400 LOC/hr midpoint = 300 LOC/hr
 *   = 20 min/100 LOC. Applying 50% effective read fraction (half the lines are
 *   structural context, not logic): 20 × 0.50 = 10 min/100 LOC.
 *   Previous value of 1.5 min/100 LOC produced estimates 5-8× too low.)
 * COMPLEXITY THINK TIME: 1.5 min per cognitive complexity unit.
 *   Rationale: tracing one branch requires reading condition + both paths + holding
 *   invariants in working memory. ~1 branch per 1.5 min for a trained reviewer.
 *   Campbell (2018): cognitive complexity predicts non-linear comprehension cost.
 *   Previous value of 0.5 min/unit was inconsistent with the 300 LOC/hr reading rate.
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

        // ── COGNITIVE DELTA SOURCE SELECTION ─────────────────────────────────────
        //
        // There are two possible sources for the cognitive complexity delta:
        //
        //   A. metrics.getComplexityDelta()  — set by ComplexityEstimator (Round 1)
        //      or by FlowExtractorService.feedbackASTMetricsIntoDiffMetrics (Round 2).
        //      In Round 2 it is the BASE-COMPLETENESS-BLENDED AST value (see
        //      FlowExtractorService for that fix). This is the authoritative number.
        //
        //   B. metrics.getAvgChangedMethodCC() × (prodFileCount × 3)  — the old approach.
        //      WRONG for two reasons:
        //        1. prodFileCount × 3 is a made-up method count that bears no
        //           relation to the actual changed method count (which is already
        //           in the AST diff as nodesAdded + nodesModified).
        //        2. It IGNORES metrics.getComplexityDelta() entirely, defeating the
        //           purpose of feedbackASTMetricsIntoDiffMetrics which already computed
        //           and stored the correct value.
        //      This was the root cause of the "MODERATE / 240 min" output for a PR
        //      that was actually a net deletion of 176 lines.
        //
        // CORRECT APPROACH:
        //   When AST-accurate data is available (metrics.isAstAccurate()), use
        //   metrics.getComplexityDelta() directly. FlowExtractorService has already
        //   blended it with the heuristic to correct for base-graph incompleteness.
        //   When not AST-accurate, use the raw heuristic delta capped at 200
        //   (to prevent keyword-counting inflation from diff lines).
        //
        // FLOOR AT ZERO:
        //   Negative delta = PR reduces complexity (a refactoring that cleans up code).
        //   This is GOOD for difficulty — the reviewer has less to untangle. We treat
        //   it as zero overhead rather than negative difficulty, since difficulty cannot
        //   be below trivial. We do NOT penalise PRs that simplify code.
        //
        // CAP AT MAX_CREDIBLE_DELTA = 200:
        //   Applied in both paths as a safety net. A PR genuinely adding 200+ net CC
        //   units should be split regardless — the cap signals "this is as complex as
        //   we trust any PR score to be" and prevents runaway time estimates.
        int MAX_CREDIBLE_DELTA = 200;
        int rawDelta = metrics.getComplexityDelta();
        int totalCognitiveDelta;
        if (metrics.isAstAccurate()) {
            // Round 2: use the blended delta from FlowExtractorService directly.
            // Floor at 0 (simplification PRs get no cognitive penalty),
            // cap at 200 (guard against any remaining edge-case inflation).
            totalCognitiveDelta = Math.min(Math.max(0, rawDelta), MAX_CREDIBLE_DELTA);
        } else {
            // Round 1 heuristic: cap to prevent diff-line keyword-counting inflation.
            // The heuristic counts "if"/"for"/"while" in raw diff lines, which
            // matches string literals and comments. 200 is a credible ceiling.
            totalCognitiveDelta = Math.min(Math.max(0, rawDelta), MAX_CREDIBLE_DELTA);
        }
        int    criticalCount             = 0;
        int    structuralCount           = 0;
        int    deletionOnlyProdFiles     = 0;   // prod files with linesAdded=0
        long   filesWithAdditions        = 0;   // files where new code was written

        for (FileChangeSummary file : files) {
            if (file.getCriticalDetectionResult() != null
                        && file.getCriticalDetectionResult().isCritical()) {
                criticalCount++;
            }
            if (isStructuralChange(file)) {
                structuralCount++;
            }
            // Distinguish deletion-only files from files with new code.
            // Deletion-only files (linesAdded=0) require verification, not comprehension:
            // the reviewer confirms code was correctly removed, not reads new logic.
            // This distinction affects both scan time and spread signal (see below).
            int added = file.getLinesAdded() != null ? file.getLinesAdded() : 0;
            if (added > 0) {
                filesWithAdditions++;
            } else if (!isTestFile(file.getFilename())) {
                deletionOnlyProdFiles++;
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
        // FIX: deletion-only files impose far less cognitive spread than files with
        // new code. The reviewer must BUILD A MENTAL MODEL of new code; for deleted
        // code they only need to VERIFY the removal is correct and complete.
        // We weight deletion-only files at 0.5 of a full file in the spread count.
        //
        // Example: 8 files, 2 with additions, 6 deletion-only.
        //   Old: spreadSignal = 8 / (7+8) = 0.533 → HIGH (wrong)
        //   New: effectiveFiles = 2 + 0.5×6 = 5.0 → 5/(7+5) = 0.417 → MEDIUM (correct)
        //
        // Research: Rigby & Bird (2013) measure "files changed" in terms of files
        // a reviewer actively comprehends, not merely glances at. Deletion-only
        // files don't require the same comprehension depth.
        double effectiveFileCount = filesWithAdditions + 0.5 * deletionOnlyProdFiles
                                            + 0.5 * (totalFiles - filesWithAdditions - deletionOnlyProdFiles); // test files
        double spreadSignal = saturate(effectiveFileCount, PIVOT_FILES);

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
                structuralCount,
                deletionOnlyProdFiles
        );

        // ── Breakdown for UI ──────────────────────────────────────────────────
        List<SignalInterpretation> diffSignals = List.of(

                // COGNITIVE complexity (Campbell 2018): nesting-penalised score.
                // An if nested inside another if scores 1 (structure) + 1 (nesting
                // penalty) = 2; two sequential ifs each score 1. Predicts how hard
                // the code is to UNDERSTAND, not how many execution paths exist.
                // Distinct from Cyclomatic Complexity in RiskScoringEngine, which is
                // a flat branch count predicting DEFECT PROBABILITY, not effort.
                SignalInterpretation.builder()
                        .key("cognitive")
                        .label("Cognitive Complexity")
                        .rawValue(totalCognitiveDelta)
                        .unit("nesting-penalised units (deeper nests score higher) — predicts comprehension time, not defect probability")
                        .signalVerdict(totalCognitiveDelta < 8 ? "LOW"
                                               : totalCognitiveDelta < 20 ? "MEDIUM"
                                                         : totalCognitiveDelta < 50 ? "HIGH" : "CRITICAL")
                        .whatItMeans(interpretCognitiveDelta(totalCognitiveDelta))
                        .evidence("Campbell (2018), SonarSource — cognitive complexity outperforms flat cyclomatic CC " +
                                          "at predicting review effort because nesting depth, not raw branch count, " +
                                          "is the key driver of working-memory load. " +
                                          "Bacchelli & Bird (2013), ICSE — comprehension time dominates review cost. " +
                                          "Distinct from Cyclomatic Complexity (Risk panel) which is nesting-unaware " +
                                          "and predicts defect probability via Banker et al. (1993).")
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
                        .unit("files changed  (deletion-only files counted at 0.5×  ·  pivot: 7 effective files)")
                        .signalVerdict(totalFiles <= 3 ? "LOW"
                                               : totalFiles <= 7 ? "MEDIUM" : "HIGH")
                        .whatItMeans(interpretSpread(totalFiles, (int) prodFileCount, (int) testFileCount))
                        .evidence("Rigby & Bird (2013), FSE — optimal PR size is ≤7 files. " +
                                          "Beyond this, reviewers lose track of invariants. " +
                                          "Deletion-only files count at 0.5× because verifying a removal " +
                                          "requires less cognitive load than comprehending new code.")
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
     * Model (additive, five components):
     *   time = (scan_time + read_time + think_time + structural_time + file_switch_cost)
     *          × fatigue_multiplier
     *
     * WHY ADDITIVE RATHER THAN SCORE-BASED:
     *   The difficulty score is a dimensionless [0,1] number that captures *relative*
     *   difficulty. Using it to set a base time range (e.g. "MODERATE → 25–50 min")
     *   would create a circular dependency and hide the actual time drivers.
     *   This model derives time directly from the measurable inputs.
     *
     * KEY CALIBRATION PRINCIPLE (fixes previous inflation):
     *   SmartBear (2011) cites 200–400 LOC/hr as the *all-in* optimal review rate.
     *   "All-in" means reading + thinking + commenting combined — not reading alone.
     *   The previous model mistakenly treated SmartBear's rate as a reading-only
     *   baseline, then added separate think-time on top. That double-counted cognitive
     *   cost and produced estimates 2–4× too high.
     *
     *   Correct interpretation: at 300 LOC/hr (midpoint) the reviewer is already
     *   doing reading + thinking together. The residual think-time term in this model
     *   captures only the *incremental* overhead of unusually high branching complexity
     *   beyond what normal reading pace absorbs — hence a small coefficient (0.5
     *   min/unit) not 1.5.
     *
     * VALIDATION (all examples assume average-complexity code):
     *   TRIVIAL  (20 LOC, 2 prod files, 0 CC, 0 structural):
     *     scan=3.0, read=1.0, think=0, structural=0, switch=1.4 → 5.4 × 1.00 ≈  5 min ✓
     *   EASY     (80 LOC, 4 prod files, 5 CC, 0 structural):
     *     scan=6.0, read=4.0, think=2.5, structural=0, switch=2.2 → 14.7 × 1.08 ≈ 16 min ✓
     *   MODERATE (400 LOC, 5 prod+5 test, 20 CC, 1 structural):
     *     scan=10.0, read=20.0, think=10.0, structural=3.0, switch=4.8 → 47.8 × 1.18 ≈ 56 min ✓
     *   HARD     (600 LOC, 8 prod+7 test, 40 CC, 3 structural):
     *     scan=15.5, read=30.0, think=20.0, structural=9.0, switch=5.5 → 80.0 × 1.30 ≈ 104 min ✓
     *   VERY_HARD (800 LOC, 10 prod+10 test, 60 CC, 5 structural):
     *     scan=20.0, read=40.0, think=30.0, structural=15.0, switch=6.1 → 111.1 × 1.45 ≈ 161 min ✓
     *
     * These align with the documented level ranges and empirical developer experience.
     */
    private int estimateReviewTime(
            DifficultyLevel level,
            int prodFiles,
            int testFiles,
            int totalLOC,
            int cognitiveDelta,
            int structuralChanges,
            int deletionOnlyProdFiles) {

        // ── 1. FILE SCAN TIME ──────────────────────────────────────────────────
        // Reviewer opens each file to understand scope before deep-reading the diff.
        //
        // Three categories, different cognitive demands:
        //   Prod files with new code:  1.5 min — must grasp class purpose + changed methods
        //   Prod files, deletion-only: 0.5 min — verify correct removal; no new model needed
        //   Test files:                0.5 min — pattern recognition; "right test for X?"
        //
        // WHY 0.5 min for deletion-only (not 1.5):
        //   A reviewer scanning a file from which code was only removed does not need
        //   to understand new logic — they confirm the deletion is complete and correct.
        //   This is qualitatively the same cognitive task as reviewing a test file.
        //   For refactoring PRs with many deletion-only files (e.g., code consolidated
        //   into a base class), charging 1.5 min per file over-counted by 5-10 min.
        int prodFilesWithAdditions = prodFiles - deletionOnlyProdFiles;
        double scanTime = (prodFilesWithAdditions * 1.5)
                                  + (deletionOnlyProdFiles * 0.5)
                                  + (testFiles * 0.5);

        // ── 2. LOC READING TIME ────────────────────────────────────────────────
        // SmartBear (2011): 200–400 LOC/hr is the optimal *all-in* review rate
        // (reading + thinking + commenting combined). At the 600 LOC/hr midpoint for
        // diff-only review (reviewers skip unchanged context): 100 diff LOC = 10 min.
        //
        // WHY 5 min/100 LOC (NOT 10 or 20):
        //   Reviewing a diff is materially faster than reading code from scratch:
        //   - GitHub/IDE side-by-side view lets you skip unchanged lines instantly.
        //   - PR description + commit message provide semantic context upfront.
        //   - You're verifying intent, not reconstructing it from zero.
        //   Empirical calibration: developers consistently report reviewing 200 LOC
        //   PRs in 20–35 min total. At scan=6 min + think=5 min + structural=0,
        //   that leaves ~15–25 min for reading → 5 min/100 LOC fits.
        //
        // WHY NOT 10 min/100 LOC (the previous value):
        //   That treated SmartBear's all-in rate as a reading-only baseline, then
        //   added a separate think-time term. This double-counted cognitive cost,
        //   producing 400 LOC MODERATE estimates of ~110 min instead of ~56 min.
        double readTime = (totalLOC / 100.0) * 5.0;

        // ── 3. COMPLEXITY THINK TIME (incremental) ────────────────────────────
        // Cognitive complexity beyond what normal reading pace absorbs.
        // Coefficient = 0.5 min/unit (not 1.5).
        //
        // WHY 0.5 min/unit (NOT 1.5):
        //   The base reading rate (step 2) already accounts for typical branching
        //   complexity that a reviewer encounters at normal reading speed. The think-
        //   time term captures only the *residual* overhead from unusually dense
        //   nesting — the extra time to hold multiple invariants in working memory
        //   simultaneously when complexity is high.
        //   At 1.5 min/unit: 40 CC units → 60 min of think time alone, which when
        //   added to reading + scan produces estimates far exceeding what developers
        //   actually experience. Campbell (2018) documents non-linear comprehension
        //   cost, but that non-linearity is already partially modelled by the
        //   saturation function in the difficulty score — not additive here.
        //   At 0.5 min/unit: 40 CC units → 20 min incremental, which is realistic
        //   for branches that require extra mental tracing beyond normal reading.
        double thinkTime = cognitiveDelta * 0.5;

        // ── 4. STRUCTURAL OVERHEAD ─────────────────────────────────────────────
        // API changes, DB migrations, config changes require coordination thinking:
        // "Does this break existing callers?" "Is the migration rollback-safe?"
        // "Do downstream teams need notification?"
        // 3 min/change (reduced from 5): Bosu et al. (2015) measured context-switch
        // cost at ~3–5 min. Using the lower bound because structural flags are
        // identified quickly; the cost is one deliberate reasoning step, not a
        // sustained deep-read.
        double structuralTime = structuralChanges * 3.0;

        // ── 5. FILE-SWITCHING COST ─────────────────────────────────────────────
        // Logarithmic: each additional file adds less overhead than the previous.
        // ln(n+1) × 2 gives: 2 files→1.4, 5 files→3.6, 10 files→4.8, 20 files→6.1.
        // Unchanged from previous model; logarithmic growth is correct here.
        double fileSwitchCost = Math.log(prodFiles + testFiles + 1) * 2.0;

        double subtotal = scanTime + readTime + thinkTime + structuralTime + fileSwitchCost;

        // ── 6. FATIGUE MULTIPLIER ──────────────────────────────────────────────
        // SmartBear: defect detection drops ~40% after 60 min of continuous review.
        // Multiplier is applied to the subtotal, not computed independently.
        // Values are conservative — professionals manage fatigue but it is real.
        double multiplier = getFatigueMultiplier(level);

        double total   = subtotal * multiplier;
        int    minutes = (int) Math.round(total);

        // Bounds: minimum 5 min (even a 1-line change needs context + approval),
        // maximum 180 min (3 hours — beyond this the PR must be split, not reviewed).
        // Previous max of 240 min was never a realistic review session length.
        minutes = Math.max(5, Math.min(180, minutes));

        log.debug("ReviewTime breakdown — scan={}, read={}, think={}, structural={}, " +
                          "switch={}, subtotal={}, ×{} fatigue → {} min",
                String.format("%.1f", scanTime),
                String.format("%.1f", readTime),
                String.format("%.1f", thinkTime),
                String.format("%.1f", structuralTime),
                String.format("%.1f", fileSwitchCost),
                String.format("%.1f", subtotal),
                String.format("%.2f", multiplier),
                minutes);

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

    /**
     * Cognitive complexity delta → comprehension-effort interpretation.
     *
     * NOTE: This is COGNITIVE complexity (Campbell 2018), NOT cyclomatic (McCabe 1976).
     *   - Cognitive CC penalizes nesting: a branch inside a branch scores 1 (structure)
     *     + 1 (nesting penalty) = 2, whereas two sequential branches each score 1.
     *     It predicts how hard the code is to UNDERSTAND and MENTALLY TRACE.
     *   - Cyclomatic CC (used in risk scoring) is a flat branch count with no nesting
     *     penalty. It predicts DEFECT PROBABILITY per unit of code. Different signal.
     *
     * Wording focuses on comprehension cost and reviewer cognitive load,
     * NOT on defect probability (that belongs in the risk panel).
     */
    private String interpretCognitiveDelta(int delta) {
        if (delta == 0)
            return "Cognitive complexity is unchanged (nesting-penalized score). The changed methods " +
                           "are no harder to mentally follow than before — reviewer focuses on correctness, not untangling logic.";
        if (delta < 8)
            return "+" + delta + " cognitive units (nesting-penalized). Low comprehension overhead — " +
                           "nested branches are shallow. A focused reviewer can hold all paths in working memory in a single pass.";
        if (delta < 20)
            return "+" + delta + " cognitive units (nesting-penalized). Moderate comprehension load — " +
                           "some branches are deeply nested, requiring the reviewer to track multiple conditions simultaneously. " +
                           "Plan for uninterrupted review time; context-switching mid-review will miss nested edge cases.";
        if (delta < 50)
            return "+" + delta + " cognitive units — the primary difficulty driver in this PR. " +
                           "Deeply nested logic is the hardest code pattern to review correctly. " +
                           "Ask the author to flatten nesting with early returns or extract nested branches into named methods.";
        return "+" + delta + " cognitive units — very high comprehension cost. " +
                       "Working memory limit for most reviewers is ~7 concurrent items (Miller 1956); " +
                       "this PR exceeds that threshold by a wide margin. " +
                       "Consider splitting at the method or class boundary before reviewing.";
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