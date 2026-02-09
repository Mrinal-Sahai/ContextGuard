package io.contextguard.service;

import io.contextguard.dto.PRIdentifier;
import io.contextguard.dto.PRIntelligenceResponse;
import io.contextguard.model.PRAnalysisResult;
import io.contextguard.repository.PRAnalysisRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Simple caching layer using database persistence.
 *
 * Cache Key: {owner}/{repo}/PR#{number}
 * TTL: None (analysis is immutable for a given PR state)
 *
 * Note: If PR is updated (new commits), frontend must trigger re-analysis.
 * This implementation does NOT auto-detect PR updates.
 */
@Service
public class CacheService {

    private final PRAnalysisRepository repository;

    public CacheService(PRAnalysisRepository repository) {
        this.repository = repository;
    }

    public PRAnalysisResult findByPR(String owner, String repo, Integer prNumber) {
        return repository.findByOwnerAndRepoAndPrNumber(owner, repo, prNumber)
                       .orElse(null);
    }

    public PRAnalysisResult findById(UUID analysisId) {
        return repository.findById(analysisId)
                       .orElseThrow(() -> new RuntimeException("Analysis not found: " + analysisId));
    }

    @Transactional
    public PRAnalysisResult save(PRIdentifier prId, PRIntelligenceResponse intelligence) {

        PRAnalysisResult result = new PRAnalysisResult();
        result.setId(UUID.randomUUID());
        result.setOwner(prId.getOwner());
        result.setRepo(prId.getRepo());
        result.setPrNumber(prId.getPrNumber());
        result.setIntelligence(intelligence); // Serialize to JSON
        result.setAnalyzedAt(java.time.Instant.now());

        return repository.save(result);
    }
}
