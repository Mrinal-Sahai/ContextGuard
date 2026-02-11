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
        String name = file.getFilename().toLowerCase();
        if (name.contains("/test/") || name.contains("_test") || name.endsWith("spec.rb") || name.endsWith("test.java")) {
            return new TestImpact(true, "Test file changed");
        }
        // default: low confidence
        return new TestImpact(false, "No direct test change detected");
    }
}

