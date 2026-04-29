package io.contextguard.service;

import io.contextguard.dto.GitHubFile;
import io.contextguard.dto.SemgrepFinding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java secret and credential detector. Runs on every PR regardless of
 * whether Semgrep is installed. Scans only added/changed lines from the diff
 * patch to minimise false positives from pre-existing content.
 *
 * Design choices:
 * - Patterns are anchored to known service key formats (low false-positive rate)
 * - Generic assignment patterns require a sensitive keyword AND a long value
 * - Spring Boot ${ENV_VAR:default} is flagged if the default is a plausible secret
 * - Returns SemgrepFinding (ERROR severity) so findings elevate the risk score
 *   and appear in the AI narrative exactly like Semgrep findings
 *
 * This is a defence-in-depth layer. It does NOT replace Semgrep; it ensures
 * that common credential leaks are caught even when Semgrep is unavailable.
 */
@Service
@Slf4j
public class SecretDetectionService {

    private record SecretPattern(Pattern regex, String ruleId, String description) {}

    private static final List<SecretPattern> PATTERNS = List.of(
        // ── OpenAI ──────────────────────────────────────────────────────────
        new SecretPattern(
            Pattern.compile("sk-proj-[A-Za-z0-9_-]{50,}"),
            "secret-detection.openai-project-key",
            "Hardcoded OpenAI project API key (sk-proj-...). Rotate immediately via platform.openai.com/api-keys."
        ),
        new SecretPattern(
            Pattern.compile("sk-[A-Za-z0-9]{48}(?![A-Za-z0-9])"),
            "secret-detection.openai-legacy-key",
            "Hardcoded OpenAI legacy API key (sk-...). Rotate immediately via platform.openai.com/api-keys."
        ),

        // ── Anthropic ───────────────────────────────────────────────────────
        new SecretPattern(
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{90,}"),
            "secret-detection.anthropic-key",
            "Hardcoded Anthropic API key. Rotate immediately via console.anthropic.com."
        ),

        // ── GitHub ──────────────────────────────────────────────────────────
        new SecretPattern(
            Pattern.compile("ghp_[A-Za-z0-9]{36}"),
            "secret-detection.github-pat",
            "Hardcoded GitHub Personal Access Token (ghp_...). Revoke at github.com/settings/tokens."
        ),
        new SecretPattern(
            Pattern.compile("github_pat_[A-Za-z0-9_]{82}"),
            "secret-detection.github-fine-grained-pat",
            "Hardcoded GitHub fine-grained PAT. Revoke at github.com/settings/tokens."
        ),
        new SecretPattern(
            Pattern.compile("ghs_[A-Za-z0-9]{36}"),
            "secret-detection.github-app-token",
            "Hardcoded GitHub App installation token. Rotate immediately."
        ),

        // ── AWS ─────────────────────────────────────────────────────────────
        new SecretPattern(
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            "secret-detection.aws-access-key-id",
            "Hardcoded AWS Access Key ID (AKIA...). Deactivate at AWS IAM console immediately."
        ),

        // ── Google ──────────────────────────────────────────────────────────
        new SecretPattern(
            Pattern.compile("AIza[0-9A-Za-z_-]{35}"),
            "secret-detection.google-api-key",
            "Hardcoded Google API key (AIza...). Restrict or rotate at console.cloud.google.com."
        ),

        // ── Stripe ──────────────────────────────────────────────────────────
        new SecretPattern(
            Pattern.compile("sk_live_[0-9a-zA-Z]{24}"),
            "secret-detection.stripe-secret-key",
            "Hardcoded Stripe LIVE secret key. Rotate immediately at dashboard.stripe.com/apikeys."
        ),
        new SecretPattern(
            Pattern.compile("rk_live_[0-9a-zA-Z]{24}"),
            "secret-detection.stripe-restricted-key",
            "Hardcoded Stripe restricted key. Rotate immediately at dashboard.stripe.com/apikeys."
        ),

        // ── Slack ───────────────────────────────────────────────────────────
        new SecretPattern(
            Pattern.compile("xox[baprs]-[0-9A-Za-z]{10,}-[0-9A-Za-z]{10,}-[0-9A-Za-z]{24,}"),
            "secret-detection.slack-token",
            "Hardcoded Slack token. Revoke at api.slack.com/apps."
        ),

        // ── Generic JWT (long base64url segments — active token) ────────────
        new SecretPattern(
            Pattern.compile("eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}"),
            "secret-detection.jwt-token",
            "Hardcoded JWT token. Tokens embedded in source code cannot be rotated on expiry."
        ),

        // ── Spring Boot ${ENV_VAR:hardcoded_default} in YAML/properties ─────
        // Matches ${SOME_VAR:anything_longer_than_12_chars} — the default value
        // is hardcoded and will be used if the env var is absent.
        new SecretPattern(
            Pattern.compile("\\$\\{[A-Za-z0-9_]+:(?!\\})[^}]{12,}\\}"),
            "secret-detection.spring-hardcoded-default",
            "Spring Boot property contains a hardcoded fallback value. If the env var is absent the " +
            "literal value is used in production. Use a secrets manager or leave the default empty."
        ),

        // ── Generic high-confidence assignment patterns ──────────────────────
        // password / secret / token / api_key assigned to a value ≥20 chars
        new SecretPattern(
            Pattern.compile(
                "(?i)(?:password|passwd|secret|api[_\\-]?key|access[_\\-]?token|auth[_\\-]?token|" +
                "private[_\\-]?key|client[_\\-]?secret)\\s*[=:]\\s*['\"]?[A-Za-z0-9+/\\-_!@#$%^&*]{20,}['\"]?",
                Pattern.CASE_INSENSITIVE
            ),
            "secret-detection.generic-credential-assignment",
            "Possible hardcoded credential. Verify this is not a real secret; use environment variables or a vault."
        )
    );

    /**
     * Scan all added/changed lines in the PR diff for secrets.
     * Returns one SemgrepFinding (severity=ERROR) per unique secret occurrence.
     */
    public List<SemgrepFinding> detect(List<GitHubFile> files) {
        if (files == null || files.isEmpty()) return List.of();

        List<SemgrepFinding> findings = new ArrayList<>();

        for (GitHubFile file : files) {
            if (file.getPatch() == null) continue;
            scanPatch(file.getFilename(), file.getPatch(), findings);
        }

        if (!findings.isEmpty()) {
            log.warn("[secret-scan] {} secret/credential finding(s) in PR", findings.size());
            findings.forEach(f -> log.warn("[secret-scan] {} in {}:{} — {}",
                    f.ruleId(), f.filePath(), f.line(), f.message()));
        } else {
            log.info("[secret-scan] no hardcoded secrets detected in added lines");
        }

        return findings;
    }

    private void scanPatch(String filename, String patch, List<SemgrepFinding> findings) {
        String[] rawLines = patch.split("\n");
        int currentLine = 0;

        for (String rawLine : rawLines) {
            // Track line numbers from @@ hunk headers: @@ -a,b +c,d @@
            if (rawLine.startsWith("@@")) {
                currentLine = parseNewLineStart(rawLine);
                continue;
            }
            if (rawLine.startsWith("-")) continue;   // deleted — not our problem now

            if (rawLine.startsWith("+")) {
                // Added line — the dangerous one
                String content = rawLine.substring(1);
                scanLine(filename, content, currentLine, findings);
                currentLine++;
            } else {
                // Context line
                currentLine++;
            }
        }
    }

    private void scanLine(String filename, String line, int lineNumber,
                          List<SemgrepFinding> findings) {
        for (SecretPattern sp : PATTERNS) {
            Matcher m = sp.regex().matcher(line);
            if (m.find()) {
                // Redact the matched value in the log but keep original for the finding message
                findings.add(new SemgrepFinding(
                        sp.ruleId(),
                        "ERROR",
                        sp.description(),
                        filename,
                        lineNumber
                ));
                // One finding per rule per line is enough — don't flood for the same line
                break;
            }
        }
    }

    /** Parse the new-file start line from a unified diff hunk header. */
    private static int parseNewLineStart(String hunkHeader) {
        // Format: @@ -old_start,old_count +new_start,new_count @@
        try {
            int plusIdx = hunkHeader.indexOf('+');
            if (plusIdx < 0) return 0;
            String after = hunkHeader.substring(plusIdx + 1);
            String num = after.split("[,\\s@@]")[0];
            return Integer.parseInt(num);
        } catch (Exception e) {
            return 0;
        }
    }
}
