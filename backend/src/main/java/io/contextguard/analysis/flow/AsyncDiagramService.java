package io.contextguard.analysis.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contextguard.dto.PRMetadata;
import io.contextguard.model.PRAnalysisResult;
import io.contextguard.repository.PRAnalysisRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
@Service
public class AsyncDiagramService {

    private final FlowExtractorService flowExtractor;
    private final MermaidRendererService mermaidRenderer;
    private final PRAnalysisRepository repository;
    private final ObjectMapper objectMapper;

    public AsyncDiagramService(
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
     * @param analysisId Analysis UUID
     * @param prMetadata PR metadata with branches
     * @param githubToken Optional GitHub token
     * @return CompletableFuture that resolves when diagram is ready
     */
    @Async("diagramExecutor")
    public CompletableFuture<Void> generateDiagramAsync(
            UUID analysisId,
            PRMetadata prMetadata,
            String githubToken) {

        return CompletableFuture.runAsync(() -> {
            try {
                // Step 1: Extract call graph
                CallGraphDiff diff = flowExtractor.generateDiagram(prMetadata, githubToken);

                // Step 2: Render Mermaid
                String mermaidDiagram = mermaidRenderer.renderMermaid(diff);

                // Step 3: Persist
                PRAnalysisResult analysis = repository.findById(analysisId)
                                                    .orElseThrow(() -> new RuntimeException("Analysis not found"));

                analysis.setMermaidDiagram(mermaidDiagram);
                analysis.setDiagramVerificationNotes(diff.getVerificationNotes());

                // Store metrics as JSON
                String metricsJson = objectMapper.writeValueAsString(diff.getMetrics());
                analysis.setDiagramMetrics(metricsJson);

                repository.save(analysis);

            } catch (Exception e) {
                // Log error and mark as failed
                System.err.println("Diagram generation failed: " + e.getMessage());

                PRAnalysisResult analysis = repository.findById(analysisId).orElse(null);
                if (analysis != null) {
                    analysis.setDiagramVerificationNotes("Generation failed: " + e.getMessage());
                    repository.save(analysis);
                }
            }
        });
    }
}
