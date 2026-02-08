package io.contextguard.engine;

import io.contextguard.dto.GitHubFile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Detects critical files using a multi-signal, rule-based scoring model.
 *
 * DESIGN GOALS:
 * - Deterministic and explainable (no ML)
 * - Reduce false positives from naive keyword matching
 * - Capture high-risk infrastructure and security changes
 */
@Component
public class CriticalPathDetector {

    private static final Set<String> HIGH_RISK_KEYWORDS = Set.of(
            "auth", "security", "token", "password", "credential",
            "encryption", "crypto", "payment", "transaction"
    );

    private static final Set<String> STRUCTURAL_PATH_KEYWORDS = Set.of(
            "controller", "service", "repository", "config", "middleware"
    );

    private static final Set<String> DB_KEYWORDS = Set.of(
            "migration", "schema", "database", "ddl", "sql"
    );

    private static final Set<String> CONFIG_EXTENSIONS = Set.of(
            "yml", "yaml", "properties", "env", "conf"
    );

    private static final int CRITICAL_THRESHOLD = 5;

    public List<String> detect(List<GitHubFile> files) {
        return files.stream()
                       .filter(this::isCritical)
                       .map(GitHubFile::getFilename)
                       .toList();
    }

    private boolean isCritical(GitHubFile file) {
        return computeScore(file) >= CRITICAL_THRESHOLD;
    }

    /**
     * Compute a transparent criticality score for a file.
     */
    private int computeScore(GitHubFile file) {
        int score = 0;
        String filename = file.getFilename().toLowerCase();

        // 1. High-risk domain keywords
        if (HIGH_RISK_KEYWORDS.stream().anyMatch(filename::contains)) {
            score += 3;
        }

        // 2. Structural role (controllers, services, config)
        if (STRUCTURAL_PATH_KEYWORDS.stream().anyMatch(filename::contains)) {
            score += 2;
        }

        // 3. Database / migration files
        if (DB_KEYWORDS.stream().anyMatch(filename::contains)) {
            score += 3;
        }

        // 4. Config files by extension
        String ext = extractExtension(filename);
        if (CONFIG_EXTENSIONS.contains(ext)) {
            score += 2;
        }

        // 5. Change type
        if ("deleted".equalsIgnoreCase(file.getStatus())) {
            score += 2;
        }

        // 6. Large diffs
        int churn = file.getAdditions() + file.getDeletions();
        if (churn > 100) {
            score += 1;
        }

        // 7. De-prioritize tests
        if (filename.contains("/test") || filename.contains("_test")) {
            score -= 3;
        }

        return score;
    }

    private String extractExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }
}
