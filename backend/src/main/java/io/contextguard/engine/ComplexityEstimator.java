package io.contextguard.engine;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estimates cyclomatic complexity delta from diff lines.
 *
 * ─────────────────────────────────────────────────────────────
 * WHAT IS CYCLOMATIC COMPLEXITY (McCabe, 1976)?
 * ─────────────────────────────────────────────────────────────
 * CC = 1 + number of decision points in a method.
 * (for a single connected component, which all methods are)
 *
 * DECISION POINTS (+1 each):
 *   ✓ if               — conditional branch
 *   ✓ for              — classic for-loop
 *   ✓ for-each         — enhanced-for loop (separate pattern)
 *   ✓ while            — loop
 *   ✓ case             — each switch branch is +1 (switch keyword is NOT)
 *   ✓ catch            — alternative exception path
 *   ✓ &&               — short-circuit AND (Modified McCabe / SonarQube)
 *   ✓ ||               — short-circuit OR  (Modified McCabe / SonarQube)
 *   ✓ ?  (ternary)     — inline conditional
 *
 * NOT a decision point (removed from previous version):
 *   ✗ else   — "else" is the fallthrough of an existing if-branch, not a new path
 *   ✗ switch — the keyword itself is not a branch; its "case" labels are
 *
 * ─────────────────────────────────────────────────────────────
 * BUG FIXES IN THIS VERSION
 * ─────────────────────────────────────────────────────────────
 * BUG-C1 FIX: Removed \\bswitch\\b — counted alongside \\bcase\\b = double-count.
 *             Each case label is the decision point, not the switch expression.
 *
 * BUG-C2 FIX: Removed \\belse\\b — "else" does not add a path, it is the
 *             implicit fallthrough of the existing if-branch.
 *
 * BUG-C3 FIX: Replaced pattern.matcher(line).find() with a while(m.find()) loop
 *             so multiple occurrences on the same line are all counted.
 *             Example: "if (a) return x; if (b) return y;" → +2, not +1.
 */
@Component
public class ComplexityEstimator {

    /**
     * Each pattern counts as +1 per match per line (ALL occurrences via while-find).
     */
    private static final Pattern[] DECISION_POINT_PATTERNS = {

            // ── Conditionals ─────────────────────────────────────────────
            Pattern.compile("\\bif\\b"),

            // ── Classic for-loop (NOT enhanced-for) ──────────────────────
            // Negative lookahead excludes "for (Type var : collection)" form
            Pattern.compile("\\bfor\\s*\\((?!\\s*[\\w<>\\[\\]]+\\s+\\w+\\s*:)"),

            // ── Enhanced for-each loop ────────────────────────────────────
            Pattern.compile("\\bfor\\s*\\(\\s*[\\w<>\\[\\]]+\\s+\\w+\\s*:"),

            // ── Loops ─────────────────────────────────────────────────────
            Pattern.compile("\\bwhile\\b"),

            // ── Switch branches ───────────────────────────────────────────
            // Each case = +1. switch keyword itself is NOT counted.
            Pattern.compile("\\bcase\\b"),

            // ── Exception paths ───────────────────────────────────────────
            Pattern.compile("\\bcatch\\b"),

            // ── Logical short-circuit operators (Modified McCabe) ─────────
            Pattern.compile("&&"),
            Pattern.compile("\\|\\|"),

            // ── Ternary operator ──────────────────────────────────────────
            // Avoids matching wildcard generics: List<? extends Foo>
            Pattern.compile("\\?(?!\\s*(?:extends|super)\\b)")
    };

    /**
     * Estimate complexity delta = added_complexity - deleted_complexity.
     *
     * @param addedLines   '+' lines from the unified diff
     * @param deletedLines '-' lines from the unified diff
     * @return signed delta; positive = PR increased complexity
     */
    public int estimateDelta(List<String> addedLines, List<String> deletedLines) {
        return estimateComplexity(addedLines) - estimateComplexity(deletedLines);
    }

    /**
     * Sum all decision points across a list of code lines.
     *
     * BUG-C3 FIX: while (m.find()) counts ALL occurrences per line per pattern.
     */
    private int estimateComplexity(List<String> lines) {
        int count = 0;
        for (String line : lines) {
            String stripped = stripInlineComment(line).trim();
            if (stripped.isBlank()) continue;

            for (Pattern p : DECISION_POINT_PATTERNS) {
                Matcher m = p.matcher(stripped);
                while (m.find()) count++;
            }
        }
        return count;
    }


    private String stripInlineComment(String line) {
        int slIdx = line.indexOf("//");
        if (slIdx >= 0) line = line.substring(0, slIdx);

        int bsIdx = line.indexOf("/*");
        int beIdx = line.indexOf("*/");
        if (bsIdx >= 0 && beIdx > bsIdx) {
            line = line.substring(0, bsIdx) + line.substring(beIdx + 2);
        }
        return line;
    }
}