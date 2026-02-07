package io.contextguard.engine;
import io.contextguard.dto.GitHubFile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Detects critical files using keyword matching.
 *
 * Critical files are those whose changes carry higher risk:
 * - Authentication/Authorization logic
 * - Payment processing
 * - Data persistence layer
 * - Security configurations
 *
 * WHY KEYWORD MATCHING:
 * - Simple, explainable, no ML required
 * - Customizable per project domain
 * - Defensible: "We flag files based on naming conventions"
 */
@Component
public class CriticalPathDetector {

    private static final Set<String> CRITICAL_KEYWORDS = Set.of(
            "auth", "security", "payment", "transaction",
            "encryption", "credential", "password", "token",
            "database", "migration", "config"
    );

    public List<String> detect(List<GitHubFile> files) {

        return files.stream()
                       .filter(this::isCriticalFile)
                       .map(GitHubFile::getFilename)
                       .toList();
    }

    private boolean isCriticalFile(GitHubFile file) {

        String filename = file.getFilename().toLowerCase();

        return CRITICAL_KEYWORDS.stream()
                       .anyMatch(filename::contains);
    }
}