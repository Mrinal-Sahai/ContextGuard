package io.contextguard.engine;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Estimates cyclomatic complexity delta using heuristics.
 *
 * WHY HEURISTIC, NOT REAL CYCLOMATIC COMPLEXITY:
 * - Real complexity requires AST parsing (handled by ASTParserService)
 * - Heuristic is used at the diff-ingestion stage before AST runs
 * - Defensible: "We count decision points, not full control flow"
 *
 * Heuristic: Count occurrences of control keywords in added/deleted lines.
 *
 * FIX (2025-03): Added ternary operator (? token) and enhanced-for loop
 * patterns that were previously omitted. Ternary operators are significant
 * in functional-style Java (streams, builders) and their absence caused
 * underestimation of complexity in modern codebases.
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
            Pattern.compile("\\|\\|"),
            // FIX: ternary operator — significant in stream/builder chains
            Pattern.compile("\\?\\s*[^:\\s]"),       // "? value" — avoids matching "?." (safe nav)
            // FIX: enhanced-for loop adds one decision point (element exhaustion)
            Pattern.compile("\\bfor\\s*\\(\\s*\\w+\\s+\\w+\\s*:"),
    };

    /**
     * Estimate complexity delta = (added complexity) - (deleted complexity).
     */
    public int estimateDelta(List<String> addedLines, List<String> deletedLines) {
        int addedComplexity   = estimateComplexity(addedLines);
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