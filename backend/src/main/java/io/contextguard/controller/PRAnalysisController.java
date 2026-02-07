package io.contextguard.controller;

import io.contextguard.dto.ApiResponse;
import io.contextguard.dto.PRAnalysisRequest;
import io.contextguard.dto.PRAnalysisResponse;
import io.contextguard.dto.PRIntelligenceResponse;
import io.contextguard.service.PRAnalysisOrchestrator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST API for PR Intelligence System.
 *
 * Design Principle: Idempotent operations with cache-first strategy.
 * Same PR URL analyzed twice returns identical cached result.
 */
@RestController
@RequestMapping("/api/v1/pr-analysis")
public class PRAnalysisController {

    private final PRAnalysisOrchestrator orchestrator;

    public PRAnalysisController(PRAnalysisOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Trigger PR analysis.
     *
     * Flow:
     * 1. Parse GitHub PR URL → extract {owner, repo, prNumber}
     * 2. Check cache using composite key
     * 3. If cached → return immediately
     * 4. If not cached → fetch, analyze, generate AI summary, cache, return
     *
     * Idempotency: Same PR analyzed multiple times returns same analysis ID.
     */
    @PostMapping("/analyze")
    public ResponseEntity<ApiResponse<PRAnalysisResponse>> analyzePR(
            @Valid @RequestBody PRAnalysisRequest request) {

        try {

            PRAnalysisResponse response = orchestrator.analyzeOrRetrieve(request);



            return ResponseEntity
                           .status(response.isCached() ? HttpStatus.OK : HttpStatus.CREATED)
                           .body(ApiResponse.success(response,"Analysis/Retrieval process was successful"));
        }
        catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(
                    e.getMessage(), e.getCause().getMessage()));
        }
    }

    /**
     * Retrieve cached PR intelligence by analysis ID.
     *
     * Use case: Frontend retrieves analysis after async processing
     * (though this implementation is synchronous for simplicity).
     */
    @GetMapping("/{analysisId}")
    public ResponseEntity<PRIntelligenceResponse> getAnalysis(
            @PathVariable UUID analysisId) {

        PRIntelligenceResponse intelligence = orchestrator.getAnalysisById(analysisId);

        return ResponseEntity.ok(intelligence);
    }

    /**
     * Retrieve analysis by PR identifiers (alternative lookup).
     * Useful for UI showing "previously analyzed" badge.
     */
    @GetMapping("/by-pr")
    public ResponseEntity<PRIntelligenceResponse> getAnalysisByPR(
            @RequestParam String owner,
            @RequestParam String repo,
            @RequestParam Integer prNumber) {

        PRIntelligenceResponse intelligence =
                orchestrator.getAnalysisByPR(owner, repo, prNumber);

        return ResponseEntity.ok(intelligence);
    }
}
