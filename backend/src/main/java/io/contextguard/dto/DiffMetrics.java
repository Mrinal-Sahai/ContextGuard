package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@RequiredArgsConstructor
public class DiffMetrics {
    private int totalFilesChanged;
    private int linesAdded;
    private int linesDeleted;
    private int netLinesChanged;
    private Map<String, Integer> fileTypeDistribution;
    private int complexityDelta;
    private List<String> criticalFiles;
    private List<FileChangeSummary> fileChanges;
    private int maxCallDepth;
    private double avgChangedMethodCC;
    private List<String> hotspotMethodIds;
    private int removedPublicMethods;
    private int addedPublicMethods;

    /**
     * True when FlowExtractorService has successfully fed back AST-accurate values
     * into complexityDelta, avgChangedMethodCC, maxCallDepth, and hotspotMethodIds.
     * False means all those values are heuristic diff-line estimates.
     * Exposed in the API response so the frontend can show an "AST-backed" badge.
     */
    @Builder.Default
    private boolean astAccurate = false;

    /**
     * Number of Semgrep findings discovered in changed files.
     * 0 means either Semgrep is not installed or no issues were found.
     * Used by RiskScoringEngine as an optional additive signal.
     */
    @Builder.Default
    private int semgrepFindingCount = 0;

    /**
     * Number of Semgrep findings with severity ERROR (high-confidence security issues).
     * Even a single ERROR-level finding (e.g. SQLi, secret leak) should trigger HOLD
     * regardless of the total finding count. WARNING/INFO findings do not set this counter.
     *
     * Semgrep severity mapping:
     *   ERROR   → high-confidence exploitable vulnerability (maps to isHighSeverity=true)
     *   WARNING → potential issue requiring review
     *   INFO    → style / best-practice
     */
    @Builder.Default
    private int highSeveritySastFindingCount = 0;
}
