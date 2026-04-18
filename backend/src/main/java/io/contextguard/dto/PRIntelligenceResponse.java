package io.contextguard.dto;

import io.contextguard.analysis.flow.CallGraphDiff;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Complete PR intelligence package.
 *
 * Structure:
 * - Metadata: Who, what, when
 * - Metrics: Quantitative analysis (deterministic)
 * - Risk: Scored assessment (deterministic)
 * - Narrative: Human-readable summary (AI-generated)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor  // <- FIX: Makes constructor public
public class PRIntelligenceResponse {

    private UUID analysisId;
    private PRMetadata metadata;
    private DiffMetrics metrics;
    private RiskAssessment risk;
    private DifficultyAssessment difficulty;
    private AIGeneratedNarrative narrative;
    private BlastRadiusAssessment blastRadius;
    private String mermaidDiagram;
    private String diagramVerificationNotes;
    private CallGraphDiff.GraphMetrics diagramMetrics;
    private Instant analyzedAt;
    private MergeConflictStatus mergeConflictStatus;
    private CompilationStatus compilationStatus;
}