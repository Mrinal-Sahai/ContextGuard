package io.contextguard.analysis.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contextguard.dto.PRIdentifier;
import io.contextguard.dto.PRIntelligenceResponse;
import io.contextguard.dto.PRMetadata;
import io.contextguard.model.PRAnalysisResult;
import io.contextguard.repository.PRAnalysisRepository;
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
    private final ObjectMapper objectMapper;

    public DiagramService(
            FlowExtractorService flowExtractor,
            MermaidRendererService mermaidRenderer,
            PRAnalysisRepository repository,
            ObjectMapper objectMapper) {

        this.flowExtractor = flowExtractor;
        this.mermaidRenderer = mermaidRenderer;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Generate diagram asynchronously.
     *
     * @param prMetadata PR metadata with branches
     * @param githubToken Optional GitHub token
     */
//    @Async("diagramExecutor")
    public void generateDiagram(
            UUID analysisId,
            PRIntelligenceResponse intelligence,
            PRMetadata prMetadata,
            String githubToken,
            PRIdentifier prIdentifier, List<String> changedFiles) {

            try {
                // Step 1: Extract call graph
                CallGraphDiff diff = flowExtractor.generateDiagram(intelligence,prMetadata, githubToken, prIdentifier, changedFiles);

                // Step 2: Render Mermaid
                String mermaidDiagram = mermaidRenderer.renderMermaid(diff);

                // Step 3: Persist
                PRAnalysisResult analysis = repository.findById(analysisId)
                                                    .orElseThrow(() -> new RuntimeException("Analysis not found"));

                analysis.setMermaidDiagram(mermaidDiagram);
                analysis.setDiagramVerificationNotes(diff.getVerificationNotes());

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
