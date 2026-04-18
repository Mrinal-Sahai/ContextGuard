package io.contextguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contextguard.dto.GitHubFile;
import io.contextguard.dto.SemgrepFinding;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * SEMGREP STATIC ANALYSIS SERVICE
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Runs Semgrep on changed file contents BEFORE the LLM is called.
 * Findings are injected into the AI narrative prompt as ground-truth facts.
 *
 * WHY THIS MATTERS (the CodeRabbit gap)
 * ──────────────────────────────────────
 * CodeRabbit runs 40+ deterministic linters before any LLM call. The LLM does
 * not invent security issues — it explains ones that Semgrep already found.
 * This is why CodeRabbit reviews feel specific and accurate rather than generic.
 *
 * DESIGN DECISIONS
 * ─────────────────
 * 1. Semgrep availability is checked once at startup (@PostConstruct).
 *    If semgrep is not in PATH, the service degrades gracefully — it returns an
 *    empty list and logs a WARN. The rest of the analysis pipeline is unaffected.
 *
 * 2. Changed files are written to a temp directory, scanned, then deleted.
 *    We use --config auto (Semgrep Registry OSS rules — free) which covers:
 *    security, correctness, performance, and best-practices for Java, Python,
 *    Go, JavaScript, TypeScript, and Ruby.
 *
 * 3. Scan timeout is configurable (default 60s). A PR with 50+ files could
 *    take longer; the timeout prevents blocking the analysis pipeline.
 *
 * 4. Only findings for files in the PR are returned (path filtering guards
 *    against temp-dir pollution from unrelated files).
 *
 * INSTALL SEMGREP (free)
 * ───────────────────────
 *   pip install semgrep          # macOS / Linux
 *   brew install semgrep         # macOS Homebrew
 *   # Dockerfile: RUN pip install semgrep
 *
 * READING THE LOGS
 * ─────────────────
 * Startup:
 *   [semgrep] version check → version string or error
 *   [semgrep] available=true/false
 *
 * Per analysis:
 *   [semgrep] analyze() called: N files, available=true/false, enabled=true/false
 *   [semgrep] writing files: X written, Y skipped (with reasons)
 *   [semgrep] command: semgrep --config auto --json ...
 *   [semgrep] process exit=0 stdout=N bytes stderr=M bytes
 *   [semgrep] stderr: <semgrep's own output — rules download progress, errors>
 *   [semgrep] parsed N findings from JSON
 *   [semgrep] finding[0]: ERROR rule-id file:line — message
 *   [semgrep] summary: total=N ERROR=X WARNING=Y INFO=Z
 */
@Service
@Slf4j
public class SemgrepAnalyzerService {

    private static final int MAX_FILES_TO_SCAN = 30;   // avoid very long scans on huge PRs
    private static final int MAX_FINDINGS      = 20;   // cap findings injected into prompt

    private final ObjectMapper objectMapper;
    private final boolean semgrepEnabled;
    private boolean semgrepAvailable = false;

    @Value("${semgrep.timeout-seconds:60}")
    private int timeoutSeconds;

    public SemgrepAnalyzerService(
            ObjectMapper objectMapper,
            @Value("${semgrep.enabled:true}") boolean semgrepEnabled) {
        this.objectMapper   = objectMapper;
        this.semgrepEnabled = semgrepEnabled;
    }

    @PostConstruct
    void checkAvailability() {
        log.info("[semgrep] startup check — enabled={}", semgrepEnabled);

        if (!semgrepEnabled) {
            log.info("[semgrep] disabled via semgrep.enabled=false — SAST will be skipped for all analyses");
            return;
        }
        try {
            Process p = new ProcessBuilder("semgrep", "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean exited = p.waitFor(5, TimeUnit.SECONDS);
            String versionOutput = new String(p.getInputStream().readAllBytes()).trim();

            if (exited && p.exitValue() == 0) {
                semgrepAvailable = true;
                log.info("[semgrep] available — version: {}", versionOutput);
                log.info("[semgrep] SAST analysis ENABLED (timeout={}s, maxFiles={}, maxFindings={})",
                        timeoutSeconds, MAX_FILES_TO_SCAN, MAX_FINDINGS);
            } else {
                semgrepAvailable = false;
                log.warn("[semgrep] NOT available — exit={} output='{}' — install with: pip install semgrep",
                        exited ? p.exitValue() : "timed-out", versionOutput);
                log.warn("[semgrep] SAST analysis DISABLED — all analyses will have semgrepFindingCount=0");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[semgrep] availability check interrupted — SAST disabled");
        } catch (Exception e) {
            semgrepAvailable = false;
            log.warn("[semgrep] availability check failed: {} — SAST disabled", e.getMessage());
        }
    }

    /**
     * Run Semgrep on the changed files from a PR and return findings.
     *
     * @param files Changed files from GitHub PR (with patch content)
     * @return List of Semgrep findings, empty if Semgrep unavailable or no issues found
     */
    public List<SemgrepFinding> analyze(List<GitHubFile> files) {
        int fileCount = files != null ? files.size() : 0;
        log.info("[semgrep] analyze() called: {} input files, available={}, enabled={}",
                fileCount, semgrepAvailable, semgrepEnabled);

        if (!semgrepEnabled) {
            log.info("[semgrep] skipping — semgrep.enabled=false");
            return Collections.emptyList();
        }
        if (!semgrepAvailable) {
            log.warn("[semgrep] skipping — semgrep not found in PATH (run 'pip install semgrep' to enable)");
            return Collections.emptyList();
        }
        if (files == null || files.isEmpty()) {
            log.info("[semgrep] skipping — file list is empty");
            return Collections.emptyList();
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("contextguard-semgrep-");
            log.info("[semgrep] temp dir: {}", tempDir);

            List<Path> writtenFiles = writeFilesToTemp(files, tempDir);

            if (writtenFiles.isEmpty()) {
                log.info("[semgrep] no files written to temp dir (all skipped) — returning 0 findings");
                return Collections.emptyList();
            }

            log.info("[semgrep] scanning {} file{}: {}",
                    writtenFiles.size(),
                    writtenFiles.size() == 1 ? "" : "s",
                    writtenFiles.stream()
                            .map(p -> p.getFileName().toString())
                            .collect(Collectors.joining(", ")));

            List<SemgrepFinding> findings = runSemgrep(tempDir);
            logFindingsSummary(findings);
            return findings;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[semgrep] analysis interrupted — returning 0 findings");
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("[semgrep] analysis failed (non-fatal, pipeline continues): {}", e.getMessage(), e);
            return Collections.emptyList();
        } finally {
            deleteTempDir(tempDir);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private List<Path> writeFilesToTemp(List<GitHubFile> files, Path tempDir) {
        List<Path> written = new ArrayList<>();
        int skippedRemoved  = 0;
        int skippedNoPatch  = 0;
        int skippedEmpty    = 0;
        int skippedOverLimit = 0;

        for (GitHubFile file : files) {
            if (written.size() >= MAX_FILES_TO_SCAN) {
                skippedOverLimit++;
                continue;
            }
            if (file.getFilename() == null || file.getPatch() == null) {
                skippedNoPatch++;
                log.debug("[semgrep] skip {} — no patch content", file.getFilename());
                continue;
            }
            if ("removed".equalsIgnoreCase(file.getStatus())) {
                skippedRemoved++;
                log.debug("[semgrep] skip {} — file was removed", file.getFilename());
                continue;
            }

            String content = extractAddedContent(file.getPatch());
            if (content.isBlank()) {
                skippedEmpty++;
                log.debug("[semgrep] skip {} — patch produced blank content (deletion-only diff)", file.getFilename());
                continue;
            }

            try {
                Path filePath = tempDir.resolve(file.getFilename());
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, content);
                written.add(filePath);
                log.debug("[semgrep] wrote {} ({} chars)", file.getFilename(), content.length());
            } catch (Exception e) {
                log.warn("[semgrep] could not write {} to temp: {}", file.getFilename(), e.getMessage());
            }
        }

        log.info("[semgrep] file staging: {} written, {} skipped (removed={}, noPatch={}, blankContent={}, overLimit={})",
                written.size(), skippedRemoved + skippedNoPatch + skippedEmpty + skippedOverLimit,
                skippedRemoved, skippedNoPatch, skippedEmpty, skippedOverLimit);

        return written;
    }

    /**
     * Extract non-deleted lines from a unified diff patch to approximate file content.
     * Lines prefixed with '+' (added) and ' ' (context) are kept; '-' (deleted) are skipped.
     * Header lines starting with '@@ ' are also skipped.
     */
    private String extractAddedContent(String patch) {
        if (patch == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String rawLine : patch.split("\n")) {
            if (rawLine.startsWith("@@") || rawLine.startsWith("---") || rawLine.startsWith("+++")) {
                // skip diff header lines
            } else if (rawLine.startsWith("-")) {
                // deleted line — skip
            } else if (rawLine.startsWith("+")) {
                sb.append(rawLine.substring(1)).append('\n');
            } else {
                sb.append(rawLine).append('\n');   // context line
            }
        }
        return sb.toString();
    }

    private List<SemgrepFinding> runSemgrep(Path tempDir) throws IOException, InterruptedException {
        List<String> cmd = List.of(
                "semgrep",
                "--config", "auto",      // free OSS rules from Semgrep Registry
                "--json",
                "--quiet",
                "--no-git-ignore",
                tempDir.toAbsolutePath().toString()
        );

        log.info("[semgrep] running: {}", String.join(" ", cmd));
        long startMs = System.currentTimeMillis();

        Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        long elapsedMs = System.currentTimeMillis() - startMs;

        // Always read stderr — Semgrep prints rule download progress and errors here.
        // Not reading it can cause the process to block on a full pipe buffer.
        String stderr = new String(process.getErrorStream().readAllBytes()).trim();

        if (!finished) {
            process.destroyForcibly();
            log.warn("[semgrep] timed out after {}ms (limit={}s) — no findings returned", elapsedMs, timeoutSeconds);
            if (!stderr.isBlank()) {
                log.warn("[semgrep] stderr before timeout:\n{}", stderr);
            }
            return Collections.emptyList();
        }

        String stdout = new String(process.getInputStream().readAllBytes());
        int exitCode  = process.exitValue();

        log.info("[semgrep] process finished: exit={}, elapsed={}ms, stdout={} bytes, stderr={} bytes",
                exitCode, elapsedMs, stdout.length(), stderr.length());

        // Semgrep exits with 1 when it finds issues (not a real error).
        // Exit 2+ = configuration/runtime error.
        if (exitCode >= 2) {
            log.warn("[semgrep] non-zero exit code {} — stderr:\n{}", exitCode, stderr);
        } else if (!stderr.isBlank()) {
            // exit 0 or 1: log stderr at DEBUG so rule-download noise doesn't pollute INFO logs
            log.debug("[semgrep] stderr:\n{}", stderr);
        }

        if (stdout.isBlank()) {
            log.info("[semgrep] stdout is empty — 0 findings");
            return Collections.emptyList();
        }

        return parseSemgrepOutput(stdout, tempDir.toAbsolutePath().toString());
    }

    private List<SemgrepFinding> parseSemgrepOutput(String json, String tempDirPrefix) {
        List<SemgrepFinding> findings = new ArrayList<>();
        try {
            JsonNode root    = objectMapper.readTree(json);
            JsonNode results = root.path("results");

            if (!results.isArray()) {
                log.warn("[semgrep] JSON response has no 'results' array — raw json (first 500 chars): {}",
                        json.substring(0, Math.min(500, json.length())));
                return findings;
            }

            log.info("[semgrep] parsing {} result(s) from JSON (cap={})", results.size(), MAX_FINDINGS);

            for (JsonNode r : results) {
                String ruleId   = textOr(r.path("check_id"),                  "unknown");
                String filePath = textOr(r.path("path"),                       "");
                int    line     = r.path("start").path("line").asInt(0);
                String message  = textOr(r.path("extra").path("message"),      "");
                String severity = textOr(r.path("extra").path("severity"),     "WARNING").toUpperCase();

                // Strip temp dir prefix so only the relative PR file path is shown
                if (filePath.startsWith(tempDirPrefix)) {
                    filePath = filePath.substring(tempDirPrefix.length())
                                       .replaceFirst("^[/\\\\]+", "");
                }

                SemgrepFinding finding = new SemgrepFinding(ruleId, severity, message, filePath, line);
                findings.add(finding);

                log.info("[semgrep] finding[{}]: {} {} {}:{} — {}",
                        findings.size() - 1, severity, ruleId, filePath, line, message);

                if (findings.size() >= MAX_FINDINGS) {
                    int remaining = results.size() - findings.size();
                    if (remaining > 0) {
                        log.warn("[semgrep] capped at {} findings — {} more exist but were not recorded",
                                MAX_FINDINGS, remaining);
                    }
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("[semgrep] failed to parse JSON output: {} — raw (first 500 chars): {}",
                    e.getMessage(), json.substring(0, Math.min(500, json.length())));
        }
        return findings;
    }

    private void logFindingsSummary(List<SemgrepFinding> findings) {
        if (findings.isEmpty()) {
            log.info("[semgrep] summary: 0 findings — SAST clean");
            return;
        }
        long errorCount   = findings.stream().filter(f -> "ERROR".equalsIgnoreCase(f.severity())).count();
        long warningCount = findings.stream().filter(f -> "WARNING".equalsIgnoreCase(f.severity())).count();
        long infoCount    = findings.stream().filter(f -> "INFO".equalsIgnoreCase(f.severity())).count();

        if (errorCount > 0) {
            log.warn("[semgrep] summary: total={} ERROR={} WARNING={} INFO={} — {} high-severity finding{} will trigger HOLD verdict",
                    findings.size(), errorCount, warningCount, infoCount,
                    errorCount, errorCount > 1 ? "s" : "");
        } else {
            log.info("[semgrep] summary: total={} ERROR={} WARNING={} INFO={}",
                    findings.size(), errorCount, warningCount, infoCount);
        }
    }

    /** Non-deprecated replacement for JsonNode.asText(defaultValue). */
    private static String textOr(JsonNode node, String defaultValue) {
        String v = node.textValue();
        return (v != null) ? v : defaultValue;
    }

    private void deleteTempDir(Path dir) {
        if (dir == null) return;
        try {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ex) {
                            log.trace("[semgrep] could not delete {}: {}", p, ex.getMessage());
                        }
                    });
            }
            log.debug("[semgrep] temp dir deleted: {}", dir);
        } catch (Exception e) {
            log.debug("[semgrep] could not delete temp dir {}: {}", dir, e.getMessage());
        }
    }

    public boolean isSemgrepAvailable() {
        return semgrepAvailable;
    }
}
