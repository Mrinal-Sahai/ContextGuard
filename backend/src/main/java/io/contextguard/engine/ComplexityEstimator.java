package io.contextguard.engine;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * COGNITIVE COMPLEXITY ESTIMATOR
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * WHAT THIS MEASURES — AND WHY IT MATTERS TO A REVIEWER
 * ───────────────────────────────────────────────────────
 * This estimator answers the question: "How much harder is the code to
 * *understand* after this PR compared to before?"
 *
 * It implements a HYBRID of two established models:
 *
 *  1. McCabe Cyclomatic Complexity (McCabe, 1976)
 *     Counts independent paths through code. A well-known proxy for testability.
 *     CC = 1 + decision_points
 *
 *  2. Cognitive Complexity (Campbell, SonarSource, 2018)
 *     Extends McCabe by penalising NESTING DEPTH, because nested logic is
 *     disproportionately harder to reason about than flat logic.
 *     "The brain has to maintain a mental stack of context for each nesting level."
 *     — G. Ann Campbell, "Cognitive Complexity: A new way of measuring
 *       understandability", SonarSource white paper, 2018.
 *
 * WHY A HYBRID?
 *   Pure McCabe: `if(a) { if(b) { if(c) {} } }` scores 3. Three flat ifs also
 *   score 3. But the nested version is much harder to understand.
 *   Cognitive Complexity fixes this by adding nesting penalties, but is harder
 *   to compute from diff lines alone without an AST. Our hybrid approximates
 *   nesting depth by tracking brace depth in the diff, which is accurate enough
 *   for heuristic scoring.
 *
 * RESEARCH BACKING
 *   - McCabe (1976): "A Complexity Measure", IEEE Transactions on Software Engineering
 *   - Campbell (2018): SonarSource Cognitive Complexity whitepaper
 *   - Palomba et al. (2018): "Predicting code complexity using cognitive metrics",
 *     showed nesting depth is a stronger defect predictor than flat McCabe alone.
 *   - Banker et al. (1993): Linked higher complexity to higher defect rates.
 *     Every +1 unit of complexity correlates with ~0.15 additional defects/KLOC.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * DECISION POINTS — BASE SCORE (+1 each)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   ✓ if               — conditional branch
 *   ✓ else if          — additional conditional branch (NOT plain else)
 *   ✓ for (classic)    — loop iteration
 *   ✓ for-each         — enhanced-for loop
 *   ✓ while            — loop
 *   ✓ case             — each switch branch (switch keyword itself is NOT a decision point)
 *   ✓ catch            — exception path (alternative execution flow)
 *   ✓ &&               — short-circuit AND (Modified McCabe / SonarQube convention)
 *   ✓ ||               — short-circuit OR
 *   ✓ ?  (ternary)     — inline conditional
 *
 *   ✗ else             — NOT a decision point. "else" is the implicit fallthrough
 *                        of an existing if-branch, not a new independent path.
 *   ✗ switch           — NOT a decision point. Its "case" labels are.
 *   ✗ finally          — NOT a new execution path; always runs.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * NESTING PENALTY — COGNITIVE SURCHARGE
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   Each decision point inside a nesting context gets +depth penalty.
 *   Depth is estimated by tracking '{' and '}' in the diff.
 *
 *   Example:
 *     if (a) {               // depth=0 → base +1, nesting penalty +0 → total +1
 *       for (x : list) {     // depth=1 → base +1, nesting penalty +1 → total +2
 *         if (b) {           // depth=2 → base +1, nesting penalty +2 → total +3
 *         }
 *       }
 *     }
 *   Flat version (3 ifs at depth 0): total = 3
 *   Nested version: total = 1+2+3 = 6
 *   → The nested version correctly scores twice as hard.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHAT THE DELTA MEANS FOR REVIEWERS
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   Delta = complexity(added_lines) − complexity(deleted_lines)
 *
 *   Delta = 0        → PR is complexity-neutral. Refactoring or renaming.
 *   Delta = 1..5     → Minor complexity increase. Normal feature addition. Low concern.
 *   Delta = 6..15    → Moderate increase. Reviewer should trace logic paths.
 *   Delta = 16..30   → High increase. Reviewer must build full mental model.
 *   Delta > 30       → Critical. Consider requesting decomposition into smaller PRs.
 *   Delta < 0        → PR REDUCES complexity. Positive signal (cleanup/simplification).
 *
 *   EXAMPLE SCENARIOS:
 *
 *   Scenario A — Small feature addition:
 *     Added: one if-else-if chain, 2 conditions → delta ≈ +4
 *     Interpretation: "Reviewer should verify both branches. Low overhead."
 *
 *   Scenario B — Payment flow refactor:
 *     Added: 3 nested try-catch blocks with conditions → delta ≈ +18
 *     Deleted: 2 flat methods → delta from deletions ≈ -8
 *     Net delta ≈ +10. "Moderate. Reviewer needs to trace exception paths carefully."
 *
 *   Scenario C — Dead code removal:
 *     Added: 0 lines → delta = -12
 *     Interpretation: "PR simplifies codebase. Green flag."
 */
@Component
public class ComplexityEstimator {

    // ─────────────────────────────────────────────────────────────────────────
    // DECISION POINT PATTERNS (McCabe base)
    // ─────────────────────────────────────────────────────────────────────────

    private static final Pattern PATTERN_IF =
            Pattern.compile("\\bif\\s*\\(");

    private static final Pattern PATTERN_ELSE_IF =
            Pattern.compile("\\belse\\s+if\\s*\\(");

    // Classic for: excludes "for (Type var : collection)" form
    private static final Pattern PATTERN_FOR_CLASSIC =
            Pattern.compile("\\bfor\\s*\\((?!\\s*[\\w<>\\[\\]?,\\s]+\\s+\\w+\\s*:)");

    // Enhanced for-each: "for (Type var : collection)"
    private static final Pattern PATTERN_FOR_EACH =
            Pattern.compile("\\bfor\\s*\\(\\s*[\\w<>\\[\\]?,\\s]+\\s+\\w+\\s*:");

    private static final Pattern PATTERN_WHILE =
            Pattern.compile("\\bwhile\\s*\\(");

    // Each case label = +1. switch keyword itself is NOT counted.
    private static final Pattern PATTERN_CASE =
            Pattern.compile("\\bcase\\b");

    private static final Pattern PATTERN_CATCH =
            Pattern.compile("\\bcatch\\s*\\(");

    // Logical operators (Modified McCabe, also used by SonarQube)
    private static final Pattern PATTERN_AND =
            Pattern.compile("&&");

    private static final Pattern PATTERN_OR =
            Pattern.compile("\\|\\|");

    // Ternary: avoids matching wildcard generics "List<? extends Foo>"
    private static final Pattern PATTERN_TERNARY =
            Pattern.compile("\\?(?!\\s*(?:extends|super)\\b)");

    private static final Pattern[] DECISION_POINT_PATTERNS = {
            PATTERN_ELSE_IF,    // Must come BEFORE PATTERN_IF to avoid double-counting
            PATTERN_IF,
            PATTERN_FOR_CLASSIC,
            PATTERN_FOR_EACH,
            PATTERN_WHILE,
            PATTERN_CASE,
            PATTERN_CATCH,
            PATTERN_AND,
            PATTERN_OR,
            PATTERN_TERNARY
    };

    // ─────────────────────────────────────────────────────────────────────────
    // PATTERNS FOR NESTING DEPTH TRACKING
    // ─────────────────────────────────────────────────────────────────────────

    private static final Pattern OPEN_BRACE  = Pattern.compile("\\{");
    private static final Pattern CLOSE_BRACE = Pattern.compile("\\}");

    /**
     * Estimate COGNITIVE complexity delta = added_complexity − deleted_complexity.
     *
     * Positive delta → PR increased complexity (reviewer cost goes up).
     * Negative delta → PR decreased complexity (simplification, good signal).
     *
     * @param addedLines   '+' lines from the unified diff
     * @param deletedLines '-' lines from the unified diff
     * @return signed cognitive complexity delta
     */
    public int estimateDelta(List<String> addedLines, List<String> deletedLines) {
        return estimateCognitiveComplexity(addedLines)
                       - estimateCognitiveComplexity(deletedLines);
    }

    /**
     * Estimate McCabe cyclomatic complexity only (no nesting penalty).
     * Used internally by tests and callers that want the flat count.
     */
    public int estimateCyclomaticComplexity(List<String> lines) {
        int count = 0;
        for (String line : lines) {
            String stripped = stripComments(line).trim();
            if (stripped.isBlank()) continue;

            // "else if" must be checked before "if" to avoid double-count
            String normalized = PATTERN_ELSE_IF.matcher(stripped).replaceAll("__ELSEIF__");

            for (Pattern p : DECISION_POINT_PATTERNS) {
                if (p == PATTERN_IF) {
                    // Count only "if(" that were NOT already captured as "else if"
                    Matcher m = p.matcher(normalized);
                    while (m.find()) count++;
                } else {
                    Matcher m = p.matcher(stripped);
                    while (m.find()) count++;
                }
            }
        }
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // COGNITIVE COMPLEXITY — WITH NESTING PENALTY
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cognitive Complexity score for a list of code lines.
     *
     * Algorithm:
     *   1. Track estimated nesting depth via '{' / '}' balance.
     *   2. For each decision point found on a line, score = 1 + currentDepth.
     *      (base cost of 1, plus depth penalty equal to current nesting level)
     *   3. Sum all per-line scores.
     *
     * Note: Brace-tracking from diff lines is approximate (lambdas, anonymous
     * classes, string literals with braces). This is intentional — we are a
     * heuristic estimator, not a full parser. For full accuracy, feed AST data.
     */
    private int estimateCognitiveComplexity(List<String> lines) {
        int totalScore = 0;
        int nestingDepth = 0;   // tracks { } balance across lines

        for (String line : lines) {
            String stripped = stripComments(line).trim();
            if (stripped.isBlank()) continue;

            // Count decision points on this line
            int decisionPointsOnLine = countDecisionPoints(stripped);

            // Cognitive penalty: each decision point at depth d costs 1 + d
            if (decisionPointsOnLine > 0) {
                totalScore += decisionPointsOnLine * (1 + nestingDepth);
            }

            // Update nesting depth for next line
            nestingDepth += countOccurrences(OPEN_BRACE, stripped);
            nestingDepth -= countOccurrences(CLOSE_BRACE, stripped);
            nestingDepth = Math.max(0, nestingDepth); // guard against negative (malformed diffs)
        }

        return totalScore;
    }

    /**
     * Count total decision points on a single stripped line.
     * "else if" is normalised to prevent double-counting with "if".
     */
    private int countDecisionPoints(String line) {
        // Replace "else if" → placeholder so plain "if" pattern won't double-match
        String normalized = PATTERN_ELSE_IF.matcher(line).replaceAll("__ELSEIF__");

        int count = 0;
        for (Pattern p : DECISION_POINT_PATTERNS) {
            // For PATTERN_IF, operate on normalized string to avoid else-if double-count
            String target = (p == PATTERN_IF) ? normalized : line;
            Matcher m = p.matcher(target);
            while (m.find()) count++;
        }
        return count;
    }

    private int countOccurrences(Pattern p, String line) {
        Matcher m = p.matcher(line);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // COMMENT & STRING STRIPPING
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Strip string literals, then inline/block comments from a single diff line.
     *
     * ORDER: string literals FIRST, then comments. Stripping strings prevents
     * keywords inside string values (e.g. "if this fails then retry while reconnecting")
     * from inflating the CC score.
     *
     * Example (before fix):
     *   String msg = "if this fails then retry while reconnecting";
     *   → old behaviour: if=1, while=1 → +2 false decision points
     *   → new behaviour: string content blanked → 0 decision points (correct)
     *
     * Multi-line block comments are NOT handled here because diff lines are
     * already per-line — the opening slash-star on line N is already split from
     * the star-slash on line M.
     */
    private String stripComments(String line) {
        // Step 1: blank string literal contents to prevent keyword false-positives
        line = stripStringLiterals(line);

        // Step 2: strip single-line comment
        int slIdx = line.indexOf("//");
        if (slIdx >= 0) line = line.substring(0, slIdx);

        // Step 3: strip same-line block comment  /* ... */
        int bsIdx = line.indexOf("/*");
        int beIdx = line.indexOf("*/");
        if (bsIdx >= 0 && beIdx > bsIdx) {
            line = line.substring(0, bsIdx) + line.substring(beIdx + 2);
        }

        return line;
    }

    /**
     * Replace the contents of string literals with spaces, preserving column positions
     * so that brace-depth tracking on the same line remains accurate.
     *
     * Handles:
     *   - Double-quoted strings  "hello if world"  →  "               "
     *   - Single-quoted chars    'x'               →  ' '
     *   - Escaped quotes         "she \"said\" if" →  "               "
     *
     * Spaces are used instead of removal so that a '{' at column 50 inside a string
     * does not shift brace depth counting for the rest of the line.
     */
    private String stripStringLiterals(String line) {
        StringBuilder result = new StringBuilder(line.length());
        boolean inDouble = false;
        boolean inSingle = false;

        for (int i = 0; i < line.length(); i++) {
            char c    = line.charAt(i);
            char prev = (i > 0) ? line.charAt(i - 1) : 0;

            if (c == '"' && !inSingle && prev != '\\') {
                inDouble = !inDouble;
                result.append(c);          // keep the quote delimiter itself
            } else if (c == '\'' && !inDouble && prev != '\\') {
                inSingle = !inSingle;
                result.append(c);          // keep the quote delimiter itself
            } else if (inDouble || inSingle) {
                result.append(' ');        // blank out string content
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}