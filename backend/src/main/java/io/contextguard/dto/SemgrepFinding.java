package io.contextguard.dto;

/**
 * A single finding returned by Semgrep static analysis.
 *
 * Populated by SemgrepAnalyzerService and injected into the AI narrative prompt
 * as ground-truth SAST evidence — the LLM explains findings it did not invent.
 *
 * Maps to Semgrep JSON output schema: results[].{check_id, extra.message,
 * extra.severity, path, start.line}
 */
public record SemgrepFinding(

    /** Semgrep rule ID, e.g. "java.lang.security.audit.sqli.jdbc-sqli" */
    String ruleId,

    /**
     * Severity as reported by Semgrep: ERROR, WARNING, or INFO.
     * ERROR = high-confidence security issue; WARNING = potential issue; INFO = style.
     */
    String severity,

    /** Human-readable description of the finding. */
    String message,

    /** Relative file path within the temp scan directory. */
    String filePath,

    /** 1-based line number of the finding. */
    int line
) {
    /** Returns true for findings that should elevate the risk score. */
    public boolean isHighSeverity() {
        return "ERROR".equalsIgnoreCase(severity);
    }

    /** Compact one-line representation for prompt injection. */
    public String toPromptLine() {
        String sev = isHighSeverity() ? "HIGH" : "WARN";
        String shortRule = ruleId.contains(".") ? ruleId.substring(ruleId.lastIndexOf('.') + 1) : ruleId;
        return String.format("[%s] %s — %s:%d\n       %s", sev, shortRule, filePath, line, message);
    }
}
