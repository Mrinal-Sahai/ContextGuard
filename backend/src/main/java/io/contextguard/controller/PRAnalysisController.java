package io.contextguard.controller;

import io.contextguard.dto.ApiResponse;
import io.contextguard.dto.PRAnalysisRequest;
import io.contextguard.dto.PRAnalysisResponse;
import io.contextguard.dto.PRIntelligenceResponse;
import io.contextguard.model.PRAnalysisResult;
import io.contextguard.model.User;
import io.contextguard.service.CacheService;
import io.contextguard.service.PRAnalysisOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pr-analysis")
@RequiredArgsConstructor
@Slf4j
public class PRAnalysisController {

    private final PRAnalysisOrchestrator orchestrator;
    private final CacheService cacheService;

    @PostMapping("/analyze")
    public ResponseEntity<ApiResponse<PRAnalysisResponse>> analyzePR(
            @Valid @RequestBody PRAnalysisRequest request,
            HttpServletRequest httpRequest) {
        try {
            // Extract authenticated user if present (GitHub OAuth session)
            User user = (User) httpRequest.getAttribute("currentUser");
            String sessionToken = user != null ? user.getAccessToken() : null;
            String analyzedBy   = user != null ? user.getLogin()      : null;

            PRAnalysisResponse response = orchestrator.analyzeOrRetrieve(request, sessionToken, analyzedBy);

            return ResponseEntity
                    .status(response.isCached() ? HttpStatus.OK : HttpStatus.CREATED)
                    .body(ApiResponse.success(response, "Analysis/Retrieval process was successful"));

        } catch (Exception e) {
            // Log the full cause chain so developers can diagnose the real problem
            Throwable root = rootCause(e);
            log.error("[analyze] Failed: {} — root cause: {}", e.getMessage(), root.getMessage(), e);

            String userMessage = e.getMessage() != null ? e.getMessage() : "Analysis failed";
            String rootMessage = root.getMessage() != null ? root.getMessage() : userMessage;
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(userMessage, rootMessage));
        }
    }

    @GetMapping("/{analysisId}")
    public ResponseEntity<PRIntelligenceResponse> getAnalysis(@PathVariable UUID analysisId) {
        PRIntelligenceResponse intelligence = orchestrator.getAnalysisById(analysisId);
        return ResponseEntity.ok(intelligence);
    }

    @GetMapping("/by-pr")
    public ResponseEntity<PRIntelligenceResponse> getAnalysisByPR(
            @RequestParam String owner,
            @RequestParam String repo,
            @RequestParam Integer prNumber) {
        PRIntelligenceResponse intelligence = orchestrator.getAnalysisByPR(owner, repo, prNumber);
        return ResponseEntity.ok(intelligence);
    }

    /** Returns the N most recently completed analyses for the history panel. */
    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> history(
            @RequestParam(defaultValue = "20") int limit) {
        List<PRAnalysisResult> recent = cacheService.findRecent(Math.min(limit, 50));
        List<Map<String, Object>> summaries = recent.stream().map(r -> {
            PRIntelligenceResponse intel = r.toResponse();
            return Map.<String, Object>of(
                    "analysisId",  r.getId().toString(),
                    "owner",       r.getOwner(),
                    "repo",        r.getRepo(),
                    "prNumber",    r.getPrNumber(),
                    "title",       intel.getMetadata() != null ? intel.getMetadata().getTitle() : "",
                    "prUrl",       intel.getMetadata() != null ? intel.getMetadata().getPrUrl() : "",
                    "riskLevel",   intel.getRisk() != null ? intel.getRisk().getLevel() : "",
                    "difficulty",  intel.getDifficulty() != null ? intel.getDifficulty().getLevel() : "",
                    "analyzedBy",  r.getAnalyzedBy() != null ? r.getAnalyzedBy() : "",
                    "analyzedAt",  r.getAnalyzedAt().toString()
            );
        }).toList();
        return ResponseEntity.ok(summaries);
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
