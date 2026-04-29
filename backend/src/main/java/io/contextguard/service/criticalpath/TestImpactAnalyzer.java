package io.contextguard.service.criticalpath;

import io.contextguard.dto.GitHubFile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Lightweight heuristic: detect likely test-impact when:
 * - test files are changed
 * - or production file change is accompanied by changes in tests referencing it
 *
 * In full infra you would run test selection / coverage analysis.
 */
@Component
public class TestImpactAnalyzer {

    public static class TestImpact {
        private final boolean likelyBreaking;
        private final String summary;

        public TestImpact(boolean likelyBreaking, String summary) {
            this.likelyBreaking = likelyBreaking;
            this.summary = summary;
        }

        public boolean isLikelyBreaking() { return likelyBreaking; }
        public String getSummary() { return summary; }
    }

    public TestImpact estimateImpact(GitHubFile file) {
        String lower = file.getFilename().toLowerCase();
        String fileOnly = lower.substring(lower.lastIndexOf('/') + 1);
        boolean isTest =
                lower.contains("/test/")   || lower.contains("/tests/") || lower.contains("/__tests__/")
                || lower.contains("/test-")
                // Java/Kotlin
                || lower.endsWith("test.java")  || lower.endsWith("tests.java")
                || lower.endsWith("spec.java")  || lower.endsWith("it.java")
                || lower.endsWith("test.kt")    || lower.endsWith("spec.kt")
                // Python
                || fileOnly.startsWith("test_") || lower.endsWith("_test.py") || lower.endsWith("_tests.py")
                // JS/TS
                || lower.endsWith(".test.js")   || lower.endsWith(".spec.js")
                || lower.endsWith(".test.ts")   || lower.endsWith(".spec.ts")
                || lower.endsWith(".test.tsx")  || lower.endsWith(".spec.tsx")
                || lower.endsWith(".test.jsx")  || lower.endsWith(".spec.jsx")
                // Ruby
                || lower.endsWith("_spec.rb")  || lower.endsWith("_test.rb")
                // Go
                || lower.endsWith("_test.go");
        return isTest
                ? new TestImpact(true, "Test file changed")
                : new TestImpact(false, "No direct test change detected");
    }
}

