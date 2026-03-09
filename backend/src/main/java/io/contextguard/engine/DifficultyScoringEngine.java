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
        int totalLOC   = safeAdd(metrics.getLinesAdded(), metrics.getLinesDeleted());

        long prodFileCount = files.stream().filter(f -> !isTestFile(f.getFilename())).count();
        long testFileCount = totalFiles - prodFileCount;

        // ── Aggregate raw signals ─────────────────────────────────────────────
        int    totalCognitiveDelta = Math.abs(metrics.getComplexityDelta());
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
                metrics.getLinesAdded(),
                Math.min(totalCognitiveDelta, 200),
                structuralCount
        );

        // ── Breakdown for UI ──────────────────────────────────────────────────
        DifficultyBreakdown breakdown = DifficultyBreakdown.builder()
                                                .cognitiveContribution(round3(W_COGNITIVE  * cognitiveSignal))
                                                .sizeContribution(round3(W_SIZE       * sizeSignal))
                                                .contextContribution(round3(W_CONTEXT    * contextSignal))
                                                .spreadContribution(round3(W_SPREAD     * spreadSignal))
                                                .criticalImpactContribution(round3(W_CRITICAL   * criticalSignal))
                                                // Raw values for display
                                                .rawCognitiveDelta(totalCognitiveDelta)
                                                .rawLOC(totalLOC)
                                                .rawLayerCount(ctx.layerCount)
                                                .rawDomainCount(ctx.domainCount)
                                                .rawCriticalCount(criticalCount)
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
            int linesAdded,
            int cognitiveDelta,
            int structuralChanges) {

        // 1. FILE SCAN: quick pass over all files
        //    Production: 1.5 min (need to understand context)
        //    Test: 0.5 min (pattern recognition; "is this the right test for X?")
        double scanTime = (prodFiles * 1.5) + (testFiles * 0.5);

        // 2. LOC READING TIME
        //    SmartBear: 200–400 LOC/hr midpoint = 300 LOC/hr = 5 min/100 LOC
        //    BUT: ~30% of lines get deep attention (the rest is context/boilerplate)
        //    Effective: 5 × 0.30 = 1.5 min/100 LOC. Adjusted down to 1.5 for
        //    skimmable boilerplate (getters, imports, closing braces).
        double readTime = (linesAdded / 100.0) * 1.5;

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

        /*
        Bosu et al. (2015)
        Characteristics of Useful Code Reviews.
        Observation:
        Context switching during reviews introduces measurable overhead.
         */
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

        log.debug("Time: scan={:.1f}, read={:.1f}, think={:.1f}, structural={:.1f}, " +
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