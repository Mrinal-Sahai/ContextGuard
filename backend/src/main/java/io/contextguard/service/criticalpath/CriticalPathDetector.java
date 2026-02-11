package io.contextguard.service.criticalpath;

import io.contextguard.dto.CriticalDetectionResult;
import io.contextguard.dto.GitHubFile;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CriticalPathDetector {

    // Domain keywords
    private static final Set<String> HIGH_RISK_KEYWORDS = Set.of(
            "auth", "security", "token", "password", "credential",
            "encryption", "crypto", "payment", "transaction", "oauth", "acl", "access"
    );

    private static final Set<String> STRUCTURAL_PATH_KEYWORDS = Set.of(
            "/controller", "/service", "/repository", "/config", "/middleware", "/adapter", "/api"
    );

    private static final Set<String> DB_KEYWORDS = Set.of(
            "migration", "schema", "database", "ddl", "sql", "sequel"
    );

    private static final Set<String> CONFIG_EXTENSIONS = Set.of(
            "yml", "yaml", "properties", "env", "conf", "ini"
    );

    // Weights (explainable, can be tuned)
    private static final int WEIGHT_HIGH_RISK_KEYWORD = 4;
    private static final int WEIGHT_STRUCTURAL_PATH = 3;
    private static final int WEIGHT_DB_KEYWORD = 4;
    private static final int WEIGHT_CONFIG_EXTENSION = 2;
    private static final int WEIGHT_DELETION = 3;
    private static final int WEIGHT_LARGE_CHURN = 2;
    private static final int WEIGHT_IMPORT_IN_DEGREE = 2;
    private static final int WEIGHT_TEST_IMPACT = 3;
    private static final int WEIGHT_TEST_FILE_PENALTY = -4; // reduce score if file is test


    private static final int CRITICAL_THRESHOLD = 6;

    private final ImportAnalyzer importAnalyzer;
    private final TestImpactAnalyzer testImpactAnalyzer;

    public CriticalPathDetector(ImportAnalyzer importAnalyzer,
                                TestImpactAnalyzer testImpactAnalyzer) {
        this.importAnalyzer = importAnalyzer;
        this.testImpactAnalyzer = testImpactAnalyzer;
    }

    /**
     * Main entrypoint: returns sorted list of CriticalDetectionResult (highest score first).
     * Always deterministic and explainable.
     */
    public List<CriticalDetectionResult> detect(List<GitHubFile> files, String owner, String repo) {
        if (files == null || files.isEmpty()) return List.of();

        // Precompute import in-degree map (fast textual heuristics)
        Map<String, Integer> importInDegree = importAnalyzer != null
                                                      ? importAnalyzer.computeImportInDegree(files)
                                                      : Collections.emptyMap();


        List<CriticalDetectionResult> results = new ArrayList<>(files.size());
        for (GitHubFile f : files) {
            List<String> reasons = new ArrayList<>();
            int score = computeScoreForFile(f, importInDegree, reasons, owner, repo);
            boolean isCritical = score >= CRITICAL_THRESHOLD;
            results.add(CriticalDetectionResult.builder()
                                .filename(f.getFilename())
                                .score(score)
                                .reasons(reasons)
                                .isCritical(isCritical)
                                .build());
        }

        // sort descending by score, tie-break by churn
        results.sort(Comparator.comparingInt(CriticalDetectionResult::getScore).reversed()
                             .thenComparing(r -> -findChurn(files, r.getFilename())));
        return results;
    }

    /**
     * Compute the score for a single file and append textual reasons for each signal.
     */
    private int computeScoreForFile(GitHubFile file,
                                    Map<String, Integer> importInDegree,
                                    List<String> reasons,
                                    String owner,
                                    String repo) {
        int score = 0;
        String filename = file.getFilename();
        String filenameLower = filename.toLowerCase();

        // 1) High-risk domain keywords
        for (String k : HIGH_RISK_KEYWORDS) {
            if (filenameLower.contains(k)) {
                score += WEIGHT_HIGH_RISK_KEYWORD;
                reasons.add("Contains high-risk keyword: '" + k + "' (+" + WEIGHT_HIGH_RISK_KEYWORD + ")");
                break; // count only once to avoid overweighting multiple keywords
            }
        }

        // 2) Structural path (controller/service/repository)
        for (String p : STRUCTURAL_PATH_KEYWORDS) {
            if (filenameLower.contains(p)) {
                score += WEIGHT_STRUCTURAL_PATH;
                reasons.add("Structural location: '" + p + "' (+" + WEIGHT_STRUCTURAL_PATH + ")");
                break;
            }
        }

        // 3) DB/migration signal
        for (String k : DB_KEYWORDS) {
            if (filenameLower.contains(k)) {
                score += WEIGHT_DB_KEYWORD;
                reasons.add("Database/migration related: '" + k + "' (+" + WEIGHT_DB_KEYWORD + ")");
                break;
            }
        }

        // 4) Config file extension
        String ext = extractExtension(filenameLower);
        if (CONFIG_EXTENSIONS.contains(ext)) {
            score += WEIGHT_CONFIG_EXTENSION;
            reasons.add("Configuration file extension: '." + ext + "' (+" + WEIGHT_CONFIG_EXTENSION + ")");
        }

        // 5) Deletion increases risk
        if ("deleted".equalsIgnoreCase(file.getStatus())) {
            score += WEIGHT_DELETION;
            reasons.add("File deleted (+" + WEIGHT_DELETION + ")");
        }

        // 6) Large churn (additions + deletions)
        int churn = file.getAdditions() + file.getDeletions();
        if (churn > 200) {
            score += WEIGHT_LARGE_CHURN;
            reasons.add("Large churn: " + churn + " lines (+" + WEIGHT_LARGE_CHURN + ")");
        } else if (churn > 50) {
            // smaller signal for medium churn
            score += 1;
            reasons.add("Medium churn: " + churn + " lines (+1)");
        }

        // 7) Import in-degree (how many changed files reference this file)
        int indeg = importInDegree.getOrDefault(filename, 0);
        if (indeg >= 3) {
            score += WEIGHT_IMPORT_IN_DEGREE;
            reasons.add("Referenced by " + indeg + " changed files (+" + WEIGHT_IMPORT_IN_DEGREE + ")");
        } else if (indeg > 0) {
            reasons.add("Referenced by " + indeg + " changed files (+0)");
        }


        // 9) Test impact: if changing this file causes test failures or touches test harness, raise
        if (testImpactAnalyzer != null) {
            TestImpactAnalyzer.TestImpact impact = testImpactAnalyzer.estimateImpact(file);
            if (impact.isLikelyBreaking()) {
                score += WEIGHT_TEST_IMPACT;
                reasons.add("Test-impact detected: " + impact.getSummary() + " (+" + WEIGHT_TEST_IMPACT + ")");
            }
        }

        // 11) Heavily de-prioritize obvious test files
        if (filenameLower.contains("/test/") || filenameLower.contains("_test") || filenameLower.endsWith("test.java") || filenameLower.endsWith("spec.rb")) {
            score += WEIGHT_TEST_FILE_PENALTY;
            reasons.add("File appears to be a test (-" + Math.abs(WEIGHT_TEST_FILE_PENALTY) + ")");
        }

        // Ensure reasons list is never empty (for auditability)
        if (reasons.isEmpty()) {
            reasons.add("No positive signals; base score " + score);
        }

        return score;
    }

    private static int findChurn(List<GitHubFile> files, String filename) {
        return files.stream().filter(f -> f.getFilename().equals(filename))
                       .mapToInt(f -> f.getAdditions() + f.getDeletions())
                       .findFirst().orElse(0);
    }

    private String extractExtension(String filenameLower) {
        int idx = filenameLower.lastIndexOf('.');
        return idx > 0 ? filenameLower.substring(idx + 1) : "";
    }
}
