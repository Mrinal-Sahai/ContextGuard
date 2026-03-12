package io.contextguard.analysis.flow;

import io.contextguard.client.AIProvider;
import io.contextguard.dto.*;
import io.contextguard.model.PRAnalysisResult;
import io.contextguard.repository.PRAnalysisRepository;
import io.contextguard.service.AIGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates: AST call graph extraction → Sequence diagram rendering → AI narrative.
 *
 * CHANGED (2025-03):
 * - MermaidRendererService now generates sequenceDiagram (runtime flow) instead of graph TB
 * - Entity passed directly to avoid redundant DB fetch (BUG-006 fix)
 * - Generated mermaidDiagram stored as sequenceDiagram Mermaid string
 * - AIGenerationService receives the rendered diagram so it can reference it in the narrative
 */
@Slf4j
@Service
public class DiagramService {

    private final FlowExtractorService flowExtractor;
    private final MermaidRendererService mermaidRenderer;
    private final PRAnalysisRepository repository;
    private final AIGenerationService aiService;
    private final LLMSequenceDiagramService llmSequenceDiagramService ;

    public DiagramService(
            FlowExtractorService flowExtractor,
            MermaidRendererService mermaidRenderer,
            PRAnalysisRepository repository,
            AIGenerationService aiService, LLMSequenceDiagramService llmSequenceDiagramService) {

        this.flowExtractor = flowExtractor;
        this.mermaidRenderer = mermaidRenderer;
        this.repository = repository;
        this.aiService = aiService;
        this.llmSequenceDiagramService = llmSequenceDiagramService;
    }

    /**
     * Primary entry point — accepts entity directly to avoid redundant DB fetch.
     */
    public void generateDiagram(
            PRAnalysisResult analysisResult,
            PRIntelligenceResponse intelligence,
            PRMetadata prMetadata,
            String githubToken,
            PRIdentifier prIdentifier,
            List<String> changedFiles,
            AIProvider provider,
            List<GitHubFile> files) {

        try {
            // Step 1: Extract call graph (AST diff — base vs head)
            CallGraphDiff diff = flowExtractor.generateDiagram(
                    intelligence, prMetadata, githubToken, prIdentifier, changedFiles);
            log.info("Call graph extracted: {} added nodes, {} added edges",
                    safeSize(diff.getNodesAdded()), safeSize(diff.getEdgesAdded()));

            // Step 2: Render sequence diagram
            // MermaidRendererService now generates `sequenceDiagram` (runtime flow)
            // falling back to `graph LR` only for pure internal refactors with no new edges.
//            String mermaidDiagram = mermaidRenderer.renderMermaid(diff);
            String mermaidDiagram = llmSequenceDiagramService.generate(diff, prMetadata, provider);
            log.info("Sequence diagram rendered ({} chars)", mermaidDiagram != null ? mermaidDiagram.length() : 0);

            // Step 3: AI narrative — receives the rendered diagram so the summary
            // can refer to specific sequence steps ("as shown in step 4 above...")
            RiskAssessment finalRisk=intelligence.getRisk();
            DifficultyAssessment finalDifficulty=intelligence.getDifficulty();

            NarrativeResult result = aiService.generateSummary(
                    files, prMetadata,
                    intelligence.getMetrics(), intelligence.getRisk(),
                    intelligence.getDifficulty(), intelligence.getBlastRadius(),
                    diff, provider);

            // Step 4: Enrich and persist
            intelligence.setNarrative(result.narrative());

            intelligence.setRisk(result.risk());
            intelligence.setDifficulty(result.difficulty());
            analysisResult.setMermaidDiagram(mermaidDiagram);
            analysisResult.setDiagramVerificationNotes(buildVerificationNote(diff));
            analysisResult.setIntelligence(intelligence);
            analysisResult.setDiagramMetrics(diff.getMetrics());

            repository.save(analysisResult);
            log.info("Analysis {} saved with sequence diagram and AI narrative", analysisResult.getId());

        } catch (Exception e) {
            log.error("Diagram generation failed for analysis {}: {}",
                    analysisResult.getId(), e.getMessage(), e);
            analysisResult.setDiagramVerificationNotes("Generation failed: " + e.getMessage());
            repository.save(analysisResult);
        }
    }


    @Deprecated
    public void generateDiagram(
            UUID analysisId,
            PRIntelligenceResponse intelligence,
            PRMetadata prMetadata,
            String githubToken,
            PRIdentifier prIdentifier,
            List<String> changedFiles,
            AIProvider provider,
            List<GitHubFile> files) {

        PRAnalysisResult analysis = repository.findById(analysisId)
                                            .orElseThrow(() -> new RuntimeException("Analysis not found: " + analysisId));
        generateDiagram(analysis, intelligence, prMetadata, githubToken,
                prIdentifier, changedFiles, provider, files);
    }

    // ─────────────────────────────────────────────────────────────────────

    private String buildVerificationNote(CallGraphDiff diff) {
        if (diff.getVerificationStatus() == null) return "Analysis complete";
        return String.format("%s — %s", diff.getVerificationStatus(), diff.getVerificationNotes());
    }

    private int safeSize(List<?> list) {
        return list != null ? list.size() : 0;
    }
}

