package io.contextguard.service;

import io.contextguard.analysis.flow.DiagramService;
import io.contextguard.client.AIProvider;
import io.contextguard.dto.*;
import io.contextguard.dto.PRIdentifier;
import io.contextguard.engine.DifficultyScoringEngine;
import io.contextguard.exception.PRNotFoundException;
import io.contextguard.model.PRAnalysisResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PRAnalysisOrchestrator {

    private final CacheService cacheService;
    private final GitHubIngestionService githubService;
    private final DiffMetadataAnalyzer diffAnalyzer;
    private final RiskScoringEngine riskEngine;
    private final AIGenerationService aiService;
    private final DifficultyScoringEngine difficultyEngine;
    private final BlastRadiusAnalyzer blastRadiusAnalyzer;
    private final DiagramService diagramService;

    public PRAnalysisOrchestrator(
            CacheService cacheService,
            GitHubIngestionService githubService,
            DiffMetadataAnalyzer diffAnalyzer,
            RiskScoringEngine riskEngine,
            AIGenerationService aiService, DifficultyScoringEngine difficultyEngine, BlastRadiusAnalyzer blastRadiusAnalyzer, DiagramService asyncDiagramService) {

        this.cacheService = cacheService;
        this.githubService = githubService;
        this.diffAnalyzer = diffAnalyzer;
        this.riskEngine = riskEngine;
        this.aiService = aiService;
        this.difficultyEngine = difficultyEngine;
        this.blastRadiusAnalyzer = blastRadiusAnalyzer;
        this.diagramService = asyncDiagramService;
    }

    public PRAnalysisResponse analyzeOrRetrieve(PRAnalysisRequest request, String githubToken) {

        PRIdentifier prId = parsePRUrl(request.getPrUrl());

        PRAnalysisResult cached = cacheService.findByPR(
                prId.getOwner(), prId.getRepo(), prId.getPrNumber());

        if (cached != null) {
            return new PRAnalysisResponse(
                    cached.getId(),
                    true,
                    "Analysis retrieved from cache"
            );
        }
        if(request.getAiProvider() == null) {
            request.setAiProvider(AIProvider.GEMINI);
        }

        PRMetadata metadata = githubService.fetchPRMetadata(prId);
        System.out.println("Fetched PR metadata: " + metadata);

        List<GitHubFile> ghFiles = githubService.fetchDiffFiles(prId);
        System.out.println("No. of fetched files: " + ghFiles.size());

        System.out.println("Started analysis pipeline");
        PRIntelligenceResponse intelligence = executeAnalysisPipeline(prId, request.getAiProvider(), metadata, ghFiles);

        PRAnalysisResult result = cacheService.save(prId, intelligence);
        List<String> changedFileList=intelligence.getMetrics().getFileChanges().stream().map(FileChangeSummary::getFilename).toList();
        System.out.println("Starting to generate diagram and Ai summary");
        diagramService.generateDiagram(
                result.getId(),
                intelligence,
                metadata,
                githubToken,
                prId,
                changedFileList,
                request.getAiProvider(),
                ghFiles
        );
        System.out.println("Completed  to generate diagram and Ai summary, Returning response");



        return new PRAnalysisResponse(
                result.getId(),
                false,
                "Analysis completed"
        );
    }

    private PRIntelligenceResponse executeAnalysisPipeline(PRIdentifier prId, AIProvider provider,PRMetadata metadata, List<GitHubFile> files) {


        DiffMetrics metrics = diffAnalyzer.analyzeDiff(files, prId, metadata);
        System.out.println("Diff metrics Analysis done: "+metrics);


        RiskAssessment risk = riskEngine.assessRisk(metadata, metrics);
        System.out.println("Risk Analysis done: "+risk);

        DifficultyAssessment difficulty = difficultyEngine.assessDifficulty(metadata, metrics);
        System.out.println("Difficulty Analysis done: "+difficulty);

        BlastRadiusAssessment blastRadius = blastRadiusAnalyzer.analyze(metrics);
        System.out.println("Blast Radius Analysis done: "+ blastRadius);

        // FIX: Use builder pattern instead of constructor
        return PRIntelligenceResponse.builder()
                       .analysisId(UUID.randomUUID())
                       .metadata(metadata)
                       .metrics(metrics)
                       .risk(risk)
                       .narrative(null)
                       .difficulty(difficulty)
                       .blastRadius(blastRadius)
                       .analyzedAt(Instant.now())
                       .build();
    }

    public PRIntelligenceResponse getAnalysisById(UUID analysisId) {
        PRAnalysisResult result = cacheService.findById(analysisId);
        PRIntelligenceResponse res=result.toResponse();
        res.setMermaidDiagram(result.getMermaidDiagram());
        res.setDiagramVerificationNotes(result.getDiagramVerificationNotes());
        res.setDiagramMetrics(result.getDiagramMetrics());
        return res;
    }

    public PRIntelligenceResponse getAnalysisByPR(String owner, String repo, Integer prNumber) {
        PRAnalysisResult result = cacheService.findByPR(owner, repo, prNumber);
        if (result == null) {
            throw new PRNotFoundException(
                    String.format("No analysis found for %s/%s#%d", owner, repo, prNumber));
        }
        return result.toResponse();
    }

    /**
     * Parse GitHub PR URL to extract owner, repo, and PR number.
     *
     * Expected format: https://github.com/{owner}/{repo}/pull/{number}
     * Example: https://github.com/spring-projects/spring-boot/pull/12345
     */
    private PRIdentifier parsePRUrl(String prUrl) {

        String pattern = "https://github\\.com/([^/]+)/([^/]+)/pull/(\\d+)";
        java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher matcher = regex.matcher(prUrl);

        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid GitHub PR URL: " + prUrl);
        }

        String owner = matcher.group(1);
        String repo = matcher.group(2);
        Integer prNumber = Integer.parseInt(matcher.group(3));

        return new PRIdentifier(owner, repo, prNumber);
    }
}