package io.contextguard.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Human-readable interpretation of a single scoring signal.
 *
 * This is what the frontend renders — NOT the raw weighted contribution.
 * Every field here has a specific meaning a reviewer can act on.
 *
 * Included in RiskBreakdown and DifficultyBreakdown so the UI can show
 * WHAT was measured, WHY it matters, and EXACTLY how it affected the score.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalInterpretation {

    /**
     * Machine key — matches the field name in the breakdown object.
     * e.g. "peakRiskContribution", "cognitiveContribution"
     */
    private String key;

    /**
     * Human label shown as the signal title.
     * e.g. "Peak File Risk", "Test Coverage Gap"
     */
    private String label;

    /**
     * The actual measured value before any weight or normalization.
     * This is the number a reviewer can look at and understand directly.
     *
     * Examples:
     *   - Peak file risk raw: 0.40  (maps to MEDIUM file risk level)
     *   - CC delta raw: 13          (13 cyclomatic complexity units added)
     *   - Test gap raw: 1.0         (100% of prod files lack test changes)
     *   - LOC raw: 170              (170 lines changed)
     */
    private double rawValue;

    /**
     * Unit of the raw value — makes rawValue self-explanatory.
     * Examples: "/ 1.00 risk scale", "CC units added", "% files uncovered",
     *           "LOC changed", "files on critical paths", "architectural layers"
     */
    private String unit;

    /**
     * Plain-English verdict for this signal alone, independent of its weight.
     * Uses the same LOW/MEDIUM/HIGH/CRITICAL vocabulary as the overall score.
     *
     * This lets a reviewer see that test coverage gap is CRITICAL even though
     * its weight (0.10) makes its score contribution look small.
     */
    private String signalVerdict;   // "LOW" | "MEDIUM" | "HIGH" | "CRITICAL"

    /**
     * One-sentence explanation of WHY this raw value produces that verdict.
     * Written for a developer, not a data scientist.
     *
     * Bad:  "Normalized signal = 0.556 via saturation function"
     * Good: "+13 CC units added — each new branch the reviewer must mentally trace"
     */
    private String whatItMeans;

    /**
     * The research citation or empirical benchmark behind this signal's verdict
     * thresholds. Shows the reviewer that the thresholds aren't arbitrary.
     *
     * Example: "Rigby & Bird (2013): review thoroughness drops after 400 LOC"
     */
    private String evidence;

    /**
     * The formula weight assigned to this signal (0.0 – 1.0, sums to 1.0 across all signals).
     * Shown explicitly so users understand why a CRITICAL signal with weight 0.10
     * contributes less to the score than a MEDIUM signal with weight 0.30.
     */
    private double weight;

    /**
     * The normalized signal value AFTER saturation/mapping, BEFORE weight.
     * Range: [0, 1].
     *
     * Shown as part of the formula:
     *   weightedContribution = weight × normalizedSignal
     *   e.g. 0.30 × 0.40 = 0.12
     */
    private double normalizedSignal;

    /**
     * The weighted contribution to the overall score.
     * = weight × normalizedSignal
     *
     * This is what was previously shown as a standalone "%" — now shown
     * in context: "0.30 × 0.40 = 0.12 → adds 12 points to your score"
     */
    private double weightedContribution;
}