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
        if (!semgrepEnabled) {
            log.info("Semgrep disabled via semgrep.enabled=false");
            return;
        }
        try {
            Process p = new ProcessBuilder("semgrep", "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean exited = p.waitFor(5, TimeUnit.SECONDS);
            semgrepAvailable = exited && p.exitValue() == 0;
            if (semgrepAvailable) {
                log.info("Semgrep available — SAST analysis enabled");
            } else {
                log.warn("Semgrep not found in PATH — install with: pip install semgrep");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Semgrep availability check interrupted");
        } catch (Exception e) {
            semgrepAvailable = false;
            log.warn("Semgrep availability check failed: {} — SAST disabled", e.getMessage());
        }
    }

    /**
     * Run Semgrep on the changed files from a PR and return findings.
     *
     * @param files Changed files from GitHub PR (with patch content)
     * @return List of Semgrep findings, empty if Semgrep unavailable or no issues found
     */
    public List<SemgrepFinding> analyze(List<GitHubFile> files) {
        if (!semgrepAvailable || files == null || files.isEmpty()) {
            return Collections.emptyList();
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("contextguard-semgrep-");
            List<Path> writtenFiles = writeFilesToTemp(files, tempDir);

            if (writtenFiles.isEmpty()) {
                return Collections.emptyList();
            }

            List<SemgrepFinding> findings = runSemgrep(tempDir);
            log.info("Semgrep scan complete: {} findings across {} files", findings.size(), writtenFiles.size());
            return findings;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Semgrep analysis interrupted");
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("Semgrep analysis failed (non-fatal): {}", e.getMessage());
            return Collections.emptyList();
        } finally {
            deleteTempDir(tempDir);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private List<Path> writeFilesToTemp(List<GitHubFile> files, Path tempDir) {
        List<Path> written = new ArrayList<>();

        for (GitHubFile file : files) {
            if (written.size() >= MAX_FILES_TO_SCAN) break;
            if (file.getFilename() == null || file.getPatch() == null) continue;
            if ("removed".equalsIgnoreCase(file.getStatus())) continue;

            String content = extractAddedContent(file.getPatch());
            if (content.isBlank()) continue;

            try {
                Path filePath = tempDir.resolve(file.getFilename());
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, content);
                written.add(filePath);
            } catch (Exception e) {
                log.debug("Could not write {} to temp: {}", file.getFilename(), e.getMessage());
            }
        }
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

        Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.warn("Semgrep timed out after {}s — no findings returned", timeoutSeconds);
            return Collections.emptyList();
        }

        String stdout = new String(process.getInputStream().readAllBytes());
        if (stdout.isBlank()) return Collections.emptyList();

        return parseSemgrepOutput(stdout, tempDir.toAbsolutePath().toString());
    }

    private List<SemgrepFinding> parseSemgrepOutput(String json, String tempDirPrefix) {
        List<SemgrepFinding> findings = new ArrayList<>();
        try {
            JsonNode root    = objectMapper.readTree(json);
            JsonNode results = root.path("results");
            if (!results.isArray()) return findings;

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

                findings.add(new SemgrepFinding(ruleId, severity, message, filePath, line));

                if (findings.size() >= MAX_FINDINGS) break;
            }
        } catch (Exception e) {
            log.warn("Failed to parse Semgrep JSON output: {}", e.getMessage());
        }
        return findings;
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
                            log.trace("Could not delete {}: {}", p, ex.getMessage());
                        }
                    });
            }
        } catch (Exception e) {
            log.debug("Could not delete temp dir {}: {}", dir, e.getMessage());
        }
    }

    public boolean isSemgrepAvailable() {
        return semgrepAvailable;
    }
}
