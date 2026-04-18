package io.contextguard.analysis.flow;

import io.contextguard.client.AIProvider;
import io.contextguard.dto.*;
import io.contextguard.model.PRAnalysisResult;
import io.contextguard.repository.PRAnalysisRepository;
import io.contextguard.service.AIGenerationService;
import io.contextguard.service.SemgrepAnalyzerService;
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
    private final LLMSequenceDiagramService llmSequenceDiagramService;
    private final SemgrepAnalyzerService semgrepAnalyzer;

    public DiagramService(
            FlowExtractorService flowExtractor,
            MermaidRendererService mermaidRenderer,
            PRAnalysisRepository repository,
            AIGenerationService aiService,
            LLMSequenceDiagramService llmSequenceDiagramService,
            SemgrepAnalyzerService semgrepAnalyzer) {

        this.flowExtractor           = flowExtractor;
        this.mermaidRenderer         = mermaidRenderer;
        this.repository              = repository;
        this.aiService               = aiService;
        this.llmSequenceDiagramService = llmSequenceDiagramService;
        this.semgrepAnalyzer         = semgrepAnalyzer;
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

            // Step 3: Run Semgrep BEFORE the LLM — findings are injected as ground-truth
            // facts into the prompt so the AI explains real issues, not invented ones.
            // Degrades gracefully: returns empty list if Semgrep is not installed.
            log.info("[semgrep] Running SAST on {} files (available={})",
                    files.size(), semgrepAnalyzer.isSemgrepAvailable());
            List<io.contextguard.dto.SemgrepFinding> semgrepFindings = semgrepAnalyzer.analyze(files);
            if (semgrepFindings.isEmpty()) {
                log.info("[semgrep] 0 findings");
            } else {
                long highSevCount = semgrepFindings.stream()
                        .filter(io.contextguard.dto.SemgrepFinding::isHighSeverity)
                        .count();
                intelligence.getMetrics().setSemgrepFindingCount(semgrepFindings.size());
                intelligence.getMetrics().setHighSeveritySastFindingCount((int) highSevCount);
                semgrepFindings.forEach(f ->
                    log.info("[semgrep] {} {} {}:{} — {}", f.severity(), f.ruleId(), f.filePath(), f.line(), f.message()));
                if (highSevCount > 0) {
                    log.warn("[semgrep] {} HIGH-severity (ERROR) finding{} — will trigger HOLD verdict",
                            highSevCount, highSevCount > 1 ? "s" : "");
                }
            }

            // Step 4: AI narrative — receives call graph + Semgrep findings
            NarrativeResult result = aiService.generateSummary(
                    files, prMetadata,
                    intelligence.getMetrics(), intelligence.getRisk(),
                    intelligence.getDifficulty(), intelligence.getBlastRadius(),
                    diff, provider, semgrepFindings);

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

