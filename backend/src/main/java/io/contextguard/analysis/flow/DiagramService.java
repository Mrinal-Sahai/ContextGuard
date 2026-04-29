package io.contextguard.analysis.flow;

import io.contextguard.client.AIProvider;
import io.contextguard.dto.*;
import io.contextguard.model.PRAnalysisResult;
import io.contextguard.repository.PRAnalysisRepository;
import io.contextguard.service.AIGenerationService;
import io.contextguard.service.SecretDetectionService;
import io.contextguard.service.SemgrepAnalyzerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
    private final SecretDetectionService secretDetector;

    public DiagramService(
            FlowExtractorService flowExtractor,
            MermaidRendererService mermaidRenderer,
            PRAnalysisRepository repository,
            AIGenerationService aiService,
            LLMSequenceDiagramService llmSequenceDiagramService,
            SemgrepAnalyzerService semgrepAnalyzer,
            SecretDetectionService secretDetector) {

        this.flowExtractor           = flowExtractor;
        this.mermaidRenderer         = mermaidRenderer;
        this.repository              = repository;
        this.aiService               = aiService;
        this.llmSequenceDiagramService = llmSequenceDiagramService;
        this.semgrepAnalyzer         = semgrepAnalyzer;
        this.secretDetector          = secretDetector;
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
        generateDiagram(analysisResult, intelligence, prMetadata, githubToken, prIdentifier,
                changedFiles, provider, files, null, null);
    }

    public void generateDiagram(
            PRAnalysisResult analysisResult,
            PRIntelligenceResponse intelligence,
            PRMetadata prMetadata,
            String githubToken,
            PRIdentifier prIdentifier,
            List<String> changedFiles,
            AIProvider provider,
            List<GitHubFile> files,
            Integer diagramMaxParticipants,
            Integer diagramMaxArrows) {

        try {
            // Step 1: Extract call graph (AST diff — base vs head)
            CallGraphDiff diff = flowExtractor.generateDiagram(
                    intelligence, prMetadata, githubToken, prIdentifier, changedFiles);
            log.info("Call graph extracted: {} added nodes, {} added edges",
                    safeSize(diff.getNodesAdded()), safeSize(diff.getEdgesAdded()));

            // Step 2: Render sequence diagram
            int maxP = diagramMaxParticipants != null ? diagramMaxParticipants : LLMSequenceDiagramService.MAX_PARTICIPANTS;
            int maxA = diagramMaxArrows       != null ? diagramMaxArrows       : LLMSequenceDiagramService.MAX_ARROWS;
            String mermaidDiagram = llmSequenceDiagramService.generate(diff, prMetadata, intelligence, provider, maxP, maxA);
            log.info("Sequence diagram rendered ({} chars)", mermaidDiagram != null ? mermaidDiagram.length() : 0);

            // Step 3a: Pure-Java secret/credential scan — always runs, no external tools needed.
            // Catches OpenAI/GitHub/AWS/Stripe keys, JWTs, Spring Boot hardcoded defaults, etc.
            List<SemgrepFinding> secretFindings = secretDetector.detect(files);

            // Step 3b: Semgrep SAST — runs if installed, degrades gracefully otherwise.
            log.info("[semgrep] Running SAST on {} files (available={})",
                    files.size(), semgrepAnalyzer.isSemgrepAvailable());
            List<SemgrepFinding> semgrepOnlyFindings = semgrepAnalyzer.analyze(files);

            // Merge: secret findings first so they appear prominently in the AI narrative
            List<SemgrepFinding> allFindings = new ArrayList<>();
            allFindings.addAll(secretFindings);
            allFindings.addAll(semgrepOnlyFindings);

            if (!allFindings.isEmpty()) {
                long highSevCount = allFindings.stream()
                        .filter(SemgrepFinding::isHighSeverity)
                        .count();
                intelligence.getMetrics().setSemgrepFindingCount(allFindings.size());
                intelligence.getMetrics().setHighSeveritySastFindingCount((int) highSevCount);
                allFindings.forEach(f ->
                    log.warn("[sast] {} {} {}:{} — {}", f.severity(), f.ruleId(), f.filePath(), f.line(), f.message()));
                if (highSevCount > 0) {
                    log.warn("[sast] {} HIGH-severity finding{} — will trigger HOLD verdict",
                            highSevCount, highSevCount > 1 ? "s" : "");
                }
            } else {
                log.info("[sast] 0 findings (secret-scan + semgrep)");
            }

            // Step 4: AI narrative — receives call graph + all SAST/secret findings
            NarrativeResult result = aiService.generateSummary(
                    files, prMetadata,
                    intelligence.getMetrics(), intelligence.getRisk(),
                    intelligence.getDifficulty(), intelligence.getBlastRadius(),
                    diff, provider, allFindings);

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
                prIdentifier, changedFiles, provider, files, null, null);
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

