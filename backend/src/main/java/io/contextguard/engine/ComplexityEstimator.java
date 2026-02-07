package io.contextguard.engine;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Estimates cyclomatic complexity delta using heuristics.
 *
 * WHY HEURISTIC, NOT REAL CYCLOMATIC COMPLEXITY:
 * - Real complexity requires AST parsing (out of scope)
 * - Heuristic approximation is "good enough" for risk scoring
 * - Defensible: "We count decision points, not full control flow"
 *
 * Heuristic: Count occurrences of control keywords in added/deleted lines.
 * Keywords: if, else, for, while, switch, case, catch, &&, ||
 */
@Component
public class ComplexityEstimator {

    private static final Pattern[] COMPLEXITY_KEYWORDS = {
            Pattern.compile("\\bif\\b"),
            Pattern.compile("\\belse\\b"),
            Pattern.compile("\\bfor\\b"),
            Pattern.compile("\\bwhile\\b"),
            Pattern.compile("\\bswitch\\b"),
            Pattern.compile("\\bcase\\b"),
            Pattern.compile("\\bcatch\\b"),
            Pattern.compile("&&"),
            Pattern.compile("\\|\\|")
    };

    /**
     * Estimate complexity delta = (added complexity) - (deleted complexity)
     */
    public int estimateDelta(List<String> addedLines, List<String> deletedLines) {

        int addedComplexity = estimateComplexity(addedLines);
        int deletedComplexity = estimateComplexity(deletedLines);

        return addedComplexity - deletedComplexity;
    }

    private int estimateComplexity(List<String> lines) {

        int count = 0;
        for (String line : lines) {
            for (Pattern pattern : COMPLEXITY_KEYWORDS) {
                if (pattern.matcher(line).find()) {
                    count++;
                }
            }
        }
        return count;
    }
}
