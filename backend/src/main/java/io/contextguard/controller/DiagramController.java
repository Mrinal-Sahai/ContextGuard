package io.contextguard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contextguard.analysis.flow.AsyncDiagramService;
import io.contextguard.analysis.flow.CallGraphDiff;
import io.contextguard.dto.PRMetadata;
import io.contextguard.model.PRAnalysisResult;
import io.contextguard.repository.PRAnalysisRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pr-analysis")
public class DiagramController {

    private final AsyncDiagramService asyncDiagramService;
    private final PRAnalysisRepository repository;
    private final ObjectMapper objectMapper;

    public DiagramController(
            AsyncDiagramService asyncDiagramService,
            PRAnalysisRepository repository,
            ObjectMapper objectMapper) {

        this.asyncDiagramService = asyncDiagramService;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Trigger async diagram generation.
     *
     * POST /api/v1/pr-analysis/{analysisId}/diagram/generate
     */
    @PostMapping("/{analysisId}/diagram/generate")
    public ResponseEntity<Map<String, String>> generateDiagram(
            @PathVariable UUID analysisId,
            @RequestHeader(value = "X-GitHub-Token", required = false) String githubToken) {

        PRAnalysisResult analysis = repository.findById(analysisId)
                                            .orElseThrow(() -> new RuntimeException("Analysis not found"));

        try {
            PRMetadata prMetadata = extractMetadata(analysis);

            // Start async generation
            asyncDiagramService.generateDiagramAsync(analysisId, prMetadata, githubToken);

            Map<String, String> response = new HashMap<>();
            response.put("analysisId", analysisId.toString());
            response.put("status", "GENERATING");
            response.put("message", "Diagram generation started. Poll /diagram endpoint for status.");

            return ResponseEntity.accepted().body(response);

        } catch (Exception e) {
            throw new RuntimeException("Failed to start diagram generation: " + e.getMessage());
        }
    }

    /**
     * Get diagram (with status).
     *
     * GET /api/v1/pr-analysis/{analysisId}/diagram
     */
    @GetMapping("/{analysisId}/diagram")
    public ResponseEntity<Map<String, Object>> getDiagram(@PathVariable UUID analysisId) {

        PRAnalysisResult analysis = repository.findById(analysisId)
                                            .orElseThrow(() -> new RuntimeException("Analysis not found"));

        Map<String, Object> response = new HashMap<>();
        response.put("analysisId", analysisId.toString());

        if (analysis.getMermaidDiagram() != null) {
            response.put("status", "READY");
            response.put("mermaid", analysis.getMermaidDiagram());
            response.put("verificationNotes", analysis.getDiagramVerificationNotes());

            // Include metrics if available
            if (analysis.getDiagramMetrics() != null) {
                try {
                    CallGraphDiff.GraphMetrics metrics = objectMapper.readValue(
                            analysis.getDiagramMetrics(),
                            CallGraphDiff.GraphMetrics.class);
                    response.put("metrics", metrics);
                } catch (Exception e) {
                    // Ignore
                }
            }
        } else if (analysis.getDiagramVerificationNotes() != null &&
                           analysis.getDiagramVerificationNotes().contains("failed")) {
            response.put("status", "FAILED");
            response.put("error", analysis.getDiagramVerificationNotes());
        } else {
            response.put("status", "GENERATING");
            response.put("message", "Diagram generation in progress...");
        }

        return ResponseEntity.ok(response);
    }

    private PRMetadata extractMetadata(PRAnalysisResult analysis) throws Exception {
        String json = analysis.getIntelligenceJson();
        Map<String, Object> data = objectMapper.readValue(json, Map.class);
        Map<String, String> metadata = (Map<String, String>) data.get("metadata");

        PRMetadata prMetadata = new PRMetadata();
        prMetadata.setPrUrl(metadata.get("prUrl"));
        prMetadata.setBaseBranch(metadata.get("baseBranch"));
        prMetadata.setHeadBranch(metadata.get("headBranch"));

        return prMetadata;
    }
}
