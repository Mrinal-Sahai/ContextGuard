package io.contextguard.service;

import io.contextguard.dto.GitHubFile;
import io.contextguard.dto.SemgrepFinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SecretDetectionService.
 * Every real secret type must be caught. Every legitimate non-secret must NOT be flagged.
 * These tests are the regression gate — a pattern removal that breaks a test here
 * means a real credential class will silently pass through.
 */
class SecretDetectionServiceTest {

    private SecretDetectionService detector;

    @BeforeEach
    void setUp() {
        detector = new SecretDetectionService();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Build a GitHubFile whose patch adds exactly one line containing the given content. */
    private GitHubFile addedLine(String filename, int line, String content) {
        GitHubFile f = new GitHubFile();
        f.setFilename(filename);
        f.setStatus("modified");
        // Hunk header for line N, then a single added line
        f.setPatch("@@ -" + line + ",1 +" + line + ",1 @@\n+" + content);
        return f;
    }

    /** Build a GitHubFile whose patch only deletes a line (should never trigger). */
    private GitHubFile deletedLine(String filename, int line, String content) {
        GitHubFile f = new GitHubFile();
        f.setFilename(filename);
        f.setStatus("modified");
        f.setPatch("@@ -" + line + ",1 +" + line + ",0 @@\n-" + content);
        return f;
    }

    private List<SemgrepFinding> scan(GitHubFile file) {
        return detector.detect(List.of(file));
    }

    private void assertDetected(GitHubFile file, String expectedRuleFragment) {
        List<SemgrepFinding> findings = scan(file);
        assertThat(findings)
                .as("Expected to detect secret matching rule '%s' in %s", expectedRuleFragment, file.getFilename())
                .isNotEmpty();
        assertThat(findings.get(0).ruleId()).contains(expectedRuleFragment);
        assertThat(findings.get(0).severity()).isEqualTo("ERROR");
    }

    private void assertClean(GitHubFile file) {
        assertThat(scan(file))
                .as("Expected no findings for safe content in %s", file.getFilename())
                .isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OpenAI
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class OpenAI {

        @Test
        void detectsProjectKey() {
            // The exact format from the incident screenshot
            assertDetected(
                addedLine("application.yaml", 50,
                    "api-key: ${OPENAI_API_KEY:sk-proj-y16bCLofDmtIElNpvqvmizIvg_dGu0ksipN1Ta777z2p3WbGMoh6AFWJrjDSBejjOOj75pRxH5T3BlbkFJnCDdH6ZDd6UcPU7WvIyGBzTNxyGioQsfm75S10UYC0RUXerUovv6rNBpTjz8KfWyBY8U6u9MA}"),
                "openai-project-key"
            );
        }

        @Test
        void detectsLegacyKey() {
            assertDetected(
                addedLine("config.py", 3, "OPENAI_API_KEY = \"sk-" + "A".repeat(48) + "\""),
                "openai-legacy-key"
            );
        }

        @Test
        void doesNotFlagPlaceholder() {
            assertClean(addedLine("README.md", 1, "api-key: ${OPENAI_API_KEY:}"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Anthropic
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Anthropic {

        @Test
        void detectsAnthropicKey() {
            assertDetected(
                addedLine("settings.py", 10,
                    "ANTHROPIC_KEY = 'sk-ant-api03-" + "x".repeat(95) + "'"),
                "anthropic-key"
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GitHub
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class GitHub {

        @Test
        void detectsClassicPAT() {
            assertDetected(
                addedLine("ci.yml", 5, "token: ghp_" + "A".repeat(36)),
                "github-pat"
            );
        }

        @Test
        void detectsFineGrainedPAT() {
            assertDetected(
                addedLine("deploy.sh", 2, "export GITHUB_TOKEN=github_pat_" + "B".repeat(82)),
                "github-fine-grained-pat"
            );
        }

        @Test
        void detectsAppInstallToken() {
            assertDetected(
                addedLine("app.js", 7, "const token = 'ghs_" + "C".repeat(36) + "'"),
                "github-app-token"
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AWS
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class AWS {

        @Test
        void detectsAccessKeyId() {
            assertDetected(
                addedLine("terraform.tf", 3, "access_key = \"AKIAIOSFODNN7EXAMPLE\""),
                "aws-access-key-id"
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Google
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Google {

        @Test
        void detectsApiKey() {
            assertDetected(
                addedLine("maps.js", 1, "const API_KEY = 'AIza" + "D".repeat(35) + "'"),
                "google-api-key"
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stripe
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Stripe {

        @Test
        void detectsLiveSecretKey() {
            assertDetected(
                addedLine("payments.rb", 4, "Stripe.api_key = 'sk_live_" + "E".repeat(24) + "'"),
                "stripe-secret-key"
            );
        }

        @Test
        void doesNotFlagTestKeyAsStripeRule() {
            // sk_test_ is not a live key — verify the Stripe-specific rule does NOT fire.
            // The generic credential pattern may still fire because the line contains
            // api_key + long value, which is intentional (can't distinguish live vs test
            // from syntax alone; the caller should review and suppress if expected).
            List<SemgrepFinding> findings = scan(
                addedLine("payments_test.rb", 1, "Stripe.api_key = 'sk_test_" + "F".repeat(24) + "'"));
            assertThat(findings)
                    .extracting(SemgrepFinding::ruleId)
                    .doesNotContain("secret-detection.stripe-secret-key");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Spring Boot hardcoded default
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class SpringBootDefault {

        @Test
        void detectsHardcodedDefaultValue() {
            assertDetected(
                addedLine("application.yaml", 50,
                    "    api-key: ${SOME_SECRET:this-is-a-hardcoded-fallback-value}"),
                "spring-hardcoded-default"
            );
        }

        @Test
        void doesNotFlagEmptyDefault() {
            // ${VAR:} is an explicit "fail if absent" — not a leaked value
            assertClean(addedLine("application.yaml", 10, "key: ${MY_KEY:}"));
        }

        @Test
        void doesNotFlagShortDefault() {
            // Short defaults like ${ENV:dev} are environment names, not secrets
            assertClean(addedLine("application.yaml", 10, "profile: ${SPRING_PROFILES_ACTIVE:dev}"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Generic credential assignment
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class GenericCredential {

        @ParameterizedTest
        @ValueSource(strings = {
            "password = SuperSecret1234567890XYZ",
            "api_key: my-very-long-secret-key-value-here",
            "secret: ABCDEFGHIJKLMNOPQRSTUVWX",
            "access_token = eyXXXXXXXXXXXXXXXXXXXXXXXX"
        })
        void detectsGenericCredentialAssignment(String line) {
            List<SemgrepFinding> findings = scan(addedLine("config.yml", 1, line));
            assertThat(findings).isNotEmpty();
        }

        @Test
        void doesNotFlagEnvVarReference() {
            // ${VAR} references are safe — no literal value
            assertClean(addedLine("application.yaml", 1, "password: ${DB_PASSWORD}"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Diff scanning correctness — only added lines trigger, never deleted/context
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class DiffLineHandling {

        @Test
        void doesNotFlagDeletedLine() {
            // A key being removed is the right action — no warning needed
            assertClean(deletedLine("application.yaml", 50,
                "api-key: ${OPENAI_API_KEY:sk-proj-y16bCLofDmtIElNpvqvmizIvg_dGu0ksipN1Ta777z2p3WbG}"));
        }

        @Test
        void doesNotFlagContextLine() {
            GitHubFile f = new GitHubFile();
            f.setFilename("config.yaml");
            f.setStatus("modified");
            // Context line (no leading +/-) that happens to look like a key — pre-existing content
            f.setPatch("@@ -50,1 +50,1 @@\n api-key: ${OPENAI_API_KEY:sk-proj-y16bCLofDmtIElNpvqvmizIvg_dGu0ksipN1Ta}");
            assertClean(f);
        }

        @Test
        void handlesMultiHunkPatch() {
            GitHubFile f = new GitHubFile();
            f.setFilename("application.yaml");
            f.setStatus("modified");
            f.setPatch(
                "@@ -10,1 +10,1 @@\n- old-value: safe\n+ new-value: also_safe\n" +
                "@@ -50,1 +50,1 @@\n- api-key: ${OPENAI_API_KEY:}\n" +
                "+ api-key: ${OPENAI_API_KEY:sk-proj-y16bCLofDmtIElNpvqvmizIvg_dGu0ksipN1Ta777z2p}"
            );
            assertThat(scan(f)).isNotEmpty();
        }

        @Test
        void returnsEmptyForNullPatch() {
            GitHubFile f = new GitHubFile();
            f.setFilename("file.java");
            f.setPatch(null);
            assertClean(f);
        }

        @Test
        void returnsEmptyForEmptyFileList() {
            assertThat(detector.detect(List.of())).isEmpty();
            assertThat(detector.detect(null)).isEmpty();
        }
    }
}
