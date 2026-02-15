package io.contextguard.analysis.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Asynchronous diagram generation service.
 *
 * WHY ASYNC:
 * - Repository cloning takes 5-30 seconds
 * - AST parsing takes 10-60 seconds for large repos
 * - Don't block PR analysis completion
 *
 * FLOW:
 * 1. PR analysis completes → returns immediately
 * 2. Diagram generation starts in background
 * 3. Frontend polls /diagram endpoint until ready
 */
@Slf4j
@Service
public class DiagramService {

    private final FlowExtractorService flowExtractor;
    private final MermaidRendererService mermaidRenderer;
    private final PRAnalysisRepository repository;
    private final AIGenerationService aiService;

    public DiagramService(
            FlowExtractorService flowExtractor,
            MermaidRendererService mermaidRenderer,
            PRAnalysisRepository repository, AIGenerationService aiService) {

        this.flowExtractor = flowExtractor;
        this.mermaidRenderer = mermaidRenderer;
        this.repository = repository;
        this.aiService = aiService;
    }

    /**
     * Generate diagram asynchronously.
     *
     * @param prMetadata PR metadata with branches
     * @param githubToken Optional GitHub token
     */
    public void generateDiagram(
            UUID analysisId,
            PRIntelligenceResponse intelligence,
            PRMetadata prMetadata,
            String githubToken,
            PRIdentifier prIdentifier,
            List<String> changedFiles,
            AIProvider provider,
            List<GitHubFile> files
    ) {

            try {
                // Step 1: Extract call graph
                CallGraphDiff diff = flowExtractor.generateDiagram(intelligence,prMetadata, githubToken, prIdentifier, changedFiles);
                System.out.println("Extracted Call Graph Diff: " + diff);


                // Step 2: Render Mermaid
                String mermaidDiagram = mermaidRenderer.renderMermaid(diff);
                System.out.println("Built mermaid diagram code");


                // Step 3: Persist
                PRAnalysisResult analysis = repository.findById(analysisId)
                                                    .orElseThrow(() -> new RuntimeException("Analysis not found"));

                AIGeneratedNarrative narrative = aiService.generateSummary(files, prMetadata, intelligence.getMetrics(), intelligence.getRisk(), intelligence.getDifficulty(),intelligence.getBlastRadius(),diff,provider);

                intelligence.setNarrative(narrative);

                analysis.setMermaidDiagram(mermaidDiagram);
                analysis.setDiagramVerificationNotes(diff.getVerificationNotes());
                analysis.setIntelligence(intelligence);


                analysis.setDiagramMetrics(diff.getMetrics());

                repository.save(analysis);

            } catch (Exception e) {
                // Log error and mark as failed
                log.error("Diagram generation failed: " + e.getMessage());

                PRAnalysisResult analysis = repository.findById(analysisId).orElse(null);
                if (analysis != null) {
                    analysis.setDiagramVerificationNotes("Generation failed: " + e.getMessage());
                    repository.save(analysis);
                }
            }
    }
}
