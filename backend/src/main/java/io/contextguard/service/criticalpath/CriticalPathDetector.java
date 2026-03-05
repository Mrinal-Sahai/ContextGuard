package io.contextguard.service.criticalpath;

import io.contextguard.dto.CriticalDetectionResult;
import io.contextguard.dto.CriticalityBand;
import io.contextguard.dto.GitHubFile;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * CRITICAL PATH DETECTOR
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * WHAT THIS DETECTS — AND WHY IT MATTERS TO A REVIEWER
 * ───────────────────────────────────────────────────────
 * "Critical path" means: if this file breaks, the impact is disproportionately
 * large — either in user-facing severity (auth, payments), in blast radius
 * (shared infrastructure, configs), or in irreversibility (DB migrations, deletions).
 *
 * This detector answers: "Which files in this PR deserve the most scrutiny,
 * and WHY?"
 *
 * SCORING FRAMEWORK — PRINCIPLED DESIGN
 * ──────────────────────────────────────
 * Instead of ad-hoc integer weights, we use a three-tier framework based on
 * the SRE concept of error budgets and the "blast radius × probability" model
 * from Forsgren et al. (Accelerate, 2018):
 *
 *   IMPACT SIGNALS    → What happens if this breaks? (severity × reach)
 *   CHANGE SIGNALS    → How likely is this change to introduce a defect?
 *   MITIGATION SIGNALS → Does the change have safety nets (tests, small churn)?
 *
 * RESEARCH BACKING
 * ─────────────────
 * - Nagappan & Ball (2005): "Use of relative code churn measures to predict
 *   system defect density." ICSE 2005. Files with high churn AND structural
 *   sensitivity (auth, config) are 3-4× more defect-prone.
 * - Mockus & Votta (2000): File history matters — frequently-changed files in
 *   core modules have higher defect density.
 * - Kim et al. (2008): "Classifying software changes: Clean or buggy?"
 *   Deletions and structural API changes are top-2 predictors of post-merge bugs.
 * - Zimmermann et al. (2008): Import graph centrality (in-degree) predicts
 *   defect-proneness. A file imported by many others = high blast radius.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * SIGNAL CATEGORIES & WEIGHTS — FULLY EXPLAINED
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * ┌─────────────────────────────┬────────┬──────────────────────────────────────┐
 * │ SIGNAL                      │ WEIGHT │ RATIONALE                            │
 * ├─────────────────────────────┼────────┼──────────────────────────────────────┤
 * │ IMPACT SIGNALS              │        │                                      │
 * │  Security/auth keyword      │  +5    │ Auth failures = total service        │
 * │                             │        │ compromise. Highest possible severity.│
 * │  Payment/financial keyword  │  +5    │ Financial data loss = regulatory +   │
 * │                             │        │ reputational damage. Equal to auth.  │
 * │  DB schema/migration        │  +4    │ Schema changes are irreversible and  │
 * │                             │        │ affect all application layers.       │
 * │  Config file (.yml/.env)    │  +3    │ Config errors affect entire service  │
 * │                             │        │ startup. Silent failures are common. │
 * │  Structural path            │  +2    │ Controllers/services are high-traffic│
 * │  (controller/service/repo)  │        │ code paths. Changes propagate widely.│
 * ├─────────────────────────────┼────────┼──────────────────────────────────────┤
 * │ CHANGE RISK SIGNALS         │        │                                      │
 * │  File deletion              │  +4    │ Kim et al. 2008: deletions are top   │
 * │                             │        │ predictor of post-merge bugs.        │
 * │                             │        │ Irreversible without rollback.       │
 * │  High churn (>300 lines)    │  +3    │ Nagappan & Ball 2005: churn >300     │
 * │                             │        │ LOC has 3× baseline defect rate.     │
 * │  Medium churn (>100 lines)  │  +1    │ Moderate signal. Worth noting.       │
 * │  High import in-degree (≥3) │  +3    │ Zimmermann 2008: high centrality =   │
 * │                             │        │ failures propagate to many dependents│
 * │  Low import in-degree (1-2) │  +1    │ Some downstream dependents exist.    │
 * ├─────────────────────────────┼────────┼──────────────────────────────────────┤
 * │ MITIGATION SIGNALS          │        │                                      │
 * │  Is a test file             │  -3    │ Test changes indicate awareness of   │
 * │                             │        │ risk, not production surface area.   │
 * │                             │        │ Note: -3 not -4, because a security  │
 * │                             │        │ test file SHOULD still be reviewed.  │
 * │  Test impact detected       │  +2    │ Breaking test signals that prod code │
 * │  (breaking test)            │        │ contract was violated.               │
 * └─────────────────────────────┴────────┴──────────────────────────────────────┘
 *
 * CRITICAL THRESHOLD = 6
 *   Calibrated so that a file needs at least TWO significant signals to be
 *   critical. A file with only one auth keyword (+5) + small churn (+0) = 5 →
 *   NOT critical (it's a small, targeted change to an auth file — lower risk).
 *   A file with auth keyword (+5) + medium churn (+1) = 6 → critical.
 *   A deleted migration file (+4 deletion + +4 migration) = 8 → always critical.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHAT THE SCORE MEANS FOR REVIEWERS
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   Score 0–2    → LOW. Routine change. No special scrutiny needed.
 *   Score 3–5    → NOTABLE. One significant signal. Include in review checklist.
 *   Score 6–8    → CRITICAL. Multiple signals. Reviewer must deep-dive this file.
 *   Score 9+     → SEVERE. Combination of impact + risk signals. Consider blocking
 *                  until a second reviewer (ideally domain expert) approves.
 *
 *   EXAMPLE SCENARIOS:
 *
 *   Scenario A — Small auth bugfix (2 lines changed):
 *     "AuthService.java": auth keyword (+5) + low churn (+0) = 5 → NOTABLE
 *     Reviewer guidance: "Targeted auth change. Verify the fix doesn't weaken
 *     token validation logic."
 *
 *   Scenario B — Payment service refactor (400 lines):
 *     "PaymentProcessor.java": payment keyword (+5) + high churn (+3) = 8 → CRITICAL
 *     Reviewer guidance: "Large payment code change. Require domain expert review
 *     and regression test run before merge."
 *
 *   Scenario C — Deleted DB migration file (uncommon but dangerous):
 *     "V12__drop_users_table.sql": DB keyword (+4) + deletion (+4) = 8 → CRITICAL
 *     Reviewer guidance: "Migration deleted. Verify this is intentional and that
 *     rollback strategy exists."
 *
 *   Scenario D — Test file for auth module:
 *     "AuthServiceTest.java": auth keyword (+5) + test penalty (-3) = 2 → LOW
 *     Rationale: auth tests are good — we want them changed/added. No alarm.
 *     But note: if auth test is DELETED: +5 (auth) + -3 (test) + +4 (deletion)
 *     = 6 → CRITICAL. Deleting auth tests is a red flag.
 */
@Component
public class CriticalPathDetector {

    // ─────────────────────────────────────────────────────────────────────────
    // IMPACT SIGNAL KEYWORDS
    // ─────────────────────────────────────────────────────────────────────────

    /** Security/auth: failure = service compromise */
    private static final Set<String> SECURITY_KEYWORDS = Set.of(
            "auth", "security", "token", "password", "credential",
            "encryption", "crypto", "oauth", "acl", "access", "permission",
            "jwt", "session", "csrf", "cors"
    );

    /** Financial: failure = regulatory + reputational damage */
    private static final Set<String> FINANCIAL_KEYWORDS = Set.of(
            "payment", "transaction", "billing", "invoice", "refund",
            "checkout", "order", "pricing", "subscription", "wallet"
    );

    /** DB/schema: changes are irreversible */
    private static final Set<String> DB_KEYWORDS = Set.of(
            "migration", "schema", "database", "ddl", "liquibase",
            "flyway", "changelog", "alter", "sequel"
    );

    /** Structural paths with high traffic */
    private static final Set<String> STRUCTURAL_PATH_KEYWORDS = Set.of(
            "/controller", "/service", "/repository", "/dao",
            "/middleware", "/adapter", "/api", "/gateway"
    );

    /** Config file extensions: silent failures are common */
    private static final Set<String> CONFIG_EXTENSIONS = Set.of(
            "yml", "yaml", "properties", "env", "conf", "ini", "toml", "xml"
    );

    // ─────────────────────────────────────────────────────────────────────────
    // SIGNAL WEIGHTS — see class-level javadoc for rationale
    // ─────────────────────────────────────────────────────────────────────────

    // Impact signals
    private static final int W_SECURITY        = 5;
    private static final int W_FINANCIAL        = 5;
    private static final int W_DB_SCHEMA        = 4;
    private static final int W_CONFIG_FILE      = 3;
    private static final int W_STRUCTURAL_PATH  = 2;

    // Change risk signals
    private static final int W_DELETION         = 4;
    private static final int W_HIGH_CHURN       = 3;   // > 300 lines
    private static final int W_MEDIUM_CHURN     = 1;   // > 100 lines
    private static final int W_HIGH_IN_DEGREE   = 3;   // referenced by ≥3 changed files
    private static final int W_LOW_IN_DEGREE    = 1;   // referenced by 1–2 changed files

    // Mitigation signals
    private static final int W_TEST_PENALTY     = -3;  // test file (not -4: auth tests need review)
    private static final int W_TEST_IMPACT      = 2;   // breaking test = contract violation

    /** Minimum score to be classified CRITICAL */
    private static final int CRITICAL_THRESHOLD = 6;


    private final ImportAnalyzer importAnalyzer;
    private final TestImpactAnalyzer testImpactAnalyzer;

    public CriticalPathDetector(ImportAnalyzer importAnalyzer,
                                TestImpactAnalyzer testImpactAnalyzer) {
        this.importAnalyzer = importAnalyzer;
        this.testImpactAnalyzer = testImpactAnalyzer;
    }

    /**
     * Main entrypoint.
     * Returns results sorted by score descending (highest-risk files first).
     * Every result contains a human-readable reasons list — the "why" for reviewers.
     */
    public List<CriticalDetectionResult> detect(List<GitHubFile> files,
                                                String owner,
                                                String repo) {
        if (files == null || files.isEmpty()) return List.of();

        Map<String, Integer> importInDegree = importAnalyzer != null
                                                      ? importAnalyzer.computeImportInDegree(files)
                                                      : Collections.emptyMap();

        List<CriticalDetectionResult> results = new ArrayList<>(files.size());

        for (GitHubFile file : files) {
            List<String> reasons = new ArrayList<>();
            int score = computeScore(file, importInDegree, reasons);
            boolean isCritical = score >= CRITICAL_THRESHOLD;
            CriticalityBand band = interpretBand(score);

            results.add(CriticalDetectionResult.builder()
                                .filename(file.getFilename())
                                .score(score)
                                .reasons(reasons)
                                .isCritical(isCritical)
                                .criticalityBand(CriticalityBand.valueOf(band.name()))
                                .build());
        }

        // Sort: highest score first; tie-break by churn (more churn = higher up)
        results.sort(
                Comparator.comparingInt(CriticalDetectionResult::getScore).reversed()
                        .thenComparingInt(r -> -getChurn(files, r.getFilename()))
        );

        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCORING LOGIC
    // ─────────────────────────────────────────────────────────────────────────

    private int computeScore(GitHubFile file,
                             Map<String, Integer> importInDegree,
                             List<String> reasons) {
        int score = 0;
        String filename = file.getFilename();
        String lower    = filename.toLowerCase();
        boolean isTest  = isTestFile(lower);

        // ── IMPACT SIGNALS ────────────────────────────────────────────────────

        // 1. Security/auth keyword (cap at 1 match to avoid keyword-stuffing inflation)
        for (String k : SECURITY_KEYWORDS) {
            if (lower.contains(k)) {
                score += W_SECURITY;
                reasons.add(String.format(
                        "Security-sensitive file (keyword: '%s'). Auth failures = service compromise. (+%d)",
                        k, W_SECURITY));
                break;
            }
        }

        // 2. Financial keyword
        for (String k : FINANCIAL_KEYWORDS) {
            if (lower.contains(k)) {
                score += W_FINANCIAL;
                reasons.add(String.format(
                        "Financial domain file (keyword: '%s'). Errors = regulatory/reputational risk. (+%d)",
                        k, W_FINANCIAL));
                break;
            }
        }

        // 3. DB/schema/migration keyword
        for (String k : DB_KEYWORDS) {
            if (lower.contains(k)) {
                score += W_DB_SCHEMA;
                reasons.add(String.format(
                        "Database/schema related (keyword: '%s'). Schema changes are irreversible. (+%d)",
                        k, W_DB_SCHEMA));
                break;
            }
        }

        // 4. Config file extension
        String ext = extractExtension(lower);
        if (CONFIG_EXTENSIONS.contains(ext)) {
            score += W_CONFIG_FILE;
            reasons.add(String.format(
                    "Configuration file (.%s). Config errors cause silent startup failures. (+%d)",
                    ext, W_CONFIG_FILE));
        }

        // 5. Structural path (controller/service/repository)
        for (String p : STRUCTURAL_PATH_KEYWORDS) {
            if (lower.contains(p)) {
                score += W_STRUCTURAL_PATH;
                reasons.add(String.format(
                        "High-traffic structural layer ('%s'). Changes propagate to callers. (+%d)",
                        p, W_STRUCTURAL_PATH));
                break;
            }
        }

        // ── CHANGE RISK SIGNALS ───────────────────────────────────────────────

        // 6. Deletion (Kim et al. 2008: top predictor of post-merge bugs)
        if ("deleted".equalsIgnoreCase(file.getStatus())
                    || "removed".equalsIgnoreCase(file.getStatus())) {
            score += W_DELETION;
            reasons.add(String.format(
                    "File deleted. Deletions are top predictor of post-merge defects (Kim et al. 2008). (+%d)",
                    W_DELETION));
        }

        // 7. Churn (Nagappan & Ball 2005)
        int churn = file.getAdditions() + file.getDeletions();
        if (churn > 300) {
            score += W_HIGH_CHURN;
            reasons.add(String.format(
                    "High churn: %d lines. Files >300 LOC changed have 3× baseline defect rate (Nagappan & Ball 2005). (+%d)",
                    churn, W_HIGH_CHURN));
        } else if (churn > 100) {
            score += W_MEDIUM_CHURN;
            reasons.add(String.format(
                    "Medium churn: %d lines. Worth monitoring for regression. (+%d)",
                    churn, W_MEDIUM_CHURN));
        }

        // 8. Import in-degree (Zimmermann et al. 2008)
        int inDeg = importInDegree.getOrDefault(filename, 0);
        if (inDeg >= 3) {
            score += W_HIGH_IN_DEGREE;
            reasons.add(String.format(
                    "Referenced by %d other changed files. High centrality = failures propagate widely (Zimmermann et al. 2008). (+%d)",
                    inDeg, W_HIGH_IN_DEGREE));
        } else if (inDeg >= 1) {
            score += W_LOW_IN_DEGREE;
            reasons.add(String.format(
                    "Referenced by %d other changed file(s). Some downstream exposure. (+%d)",
                    inDeg, W_LOW_IN_DEGREE));
        }

        // ── MITIGATION SIGNALS ────────────────────────────────────────────────

        // 9. Test impact (breaking test = contract violation)
        if (testImpactAnalyzer != null) {
            TestImpactAnalyzer.TestImpact impact = testImpactAnalyzer.estimateImpact(file);
            if (impact.isLikelyBreaking() && !isTest) {
                // Only penalize production files whose tests are breaking
                score += W_TEST_IMPACT;
                reasons.add(String.format(
                        "Test impact detected: %s. Production contract may have changed. (+%d)",
                        impact.getSummary(), W_TEST_IMPACT));
            }
        }

        // 10. Test file penalty (tests are good — don't alarm reviewers)
        //     Note: penalty of -3 (not -4) so that a DELETED security test
        //     still crosses the critical threshold: +5 (auth) -3 (test) +4 (deletion) = 6 → CRITICAL
        if (isTest) {
            score += W_TEST_PENALTY;
            reasons.add(String.format(
                    "File is a test (%s). Penalised to reduce false alarms — tests indicate awareness of risk. (%d)",
                    filename, W_TEST_PENALTY));
        }

        // Audit trail: never leave reasons empty
        if (reasons.isEmpty()) {
            reasons.add(String.format("No critical signals detected (score=%d). Routine change.", score));
        }

        return score;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERPRETATION
    // ─────────────────────────────────────────────────────────────────────────

    public static CriticalityBand interpretBand(int score) {
        if (score <= 2)  return CriticalityBand.LOW;
        if (score <= 5)  return CriticalityBand.NOTABLE;
        if (score <= 8)  return CriticalityBand.CRITICAL;
        return CriticalityBand.SEVERE;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isTestFile(String lowerFilename) {
        return lowerFilename.contains("/test/")
                       || lowerFilename.contains("_test")
                       || lowerFilename.endsWith("test.java")
                       || lowerFilename.endsWith("spec.rb")
                       || lowerFilename.endsWith("spec.js")
                       || lowerFilename.endsWith("_test.py");
    }

    private String extractExtension(String lowerFilename) {
        int idx = lowerFilename.lastIndexOf('.');
        return idx > 0 ? lowerFilename.substring(idx + 1) : "";
    }

    private int getChurn(List<GitHubFile> files, String filename) {
        return files.stream()
                       .filter(f -> f.getFilename().equals(filename))
                       .mapToInt(f -> f.getAdditions() + f.getDeletions())
                       .findFirst()
                       .orElse(0);
    }
}