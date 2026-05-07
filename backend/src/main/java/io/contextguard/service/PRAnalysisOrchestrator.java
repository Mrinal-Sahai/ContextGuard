package io.contextguard.service;

import io.contextguard.analysis.flow.DiagramService;
import io.contextguard.client.AIProvider;
import io.contextguard.dto.*;
import io.contextguard.dto.PRIdentifier;
import io.contextguard.engine.DifficultyScoringEngine;
import io.contextguard.exception.PRNotFoundException;
import io.contextguard.model.PRAnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class PRAnalysisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PRAnalysisOrchestrator.class);

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
            AIGenerationService aiService,
            DifficultyScoringEngine difficultyEngine,
            BlastRadiusAnalyzer blastRadiusAnalyzer,
            DiagramService asyncDiagramService) {

        this.cacheService = cacheService;
        this.githubService = githubService;
        this.diffAnalyzer = diffAnalyzer;
        this.riskEngine = riskEngine;
        this.aiService = aiService;
        this.difficultyEngine = difficultyEngine;
        this.blastRadiusAnalyzer = blastRadiusAnalyzer;
        this.diagramService = asyncDiagramService;
    }

    public PRAnalysisResponse analyzeOrRetrieve(PRAnalysisRequest request, String sessionGithubToken, String analyzedBy) {

        PRIdentifier prId = parsePRUrl(request.getPrUrl());

        if (request.getAiProvider() == null) {
            request.setAiProvider(AIProvider.GEMINI);
        }

        // Resolve effective token: request body token > session (OAuth) token > server env var
        String effectiveToken = firstNonBlank(request.getGithubToken(), sessionGithubToken);
        log.debug("[orchestrator] Effective token present: {}", effectiveToken != null);

        PRAnalysisResult cached = cacheService.findByPR(
                prId.getOwner(), prId.getRepo(), prId.getPrNumber());

        if (cached != null) {
            PRMetadata freshMeta = githubService.fetchPRMetadata(prId, effectiveToken);
            if (freshMeta.getHeadSha() != null &&
                        freshMeta.getHeadSha().equals(cached.getHeadSha())) {
                log.info("Cache hit (SHA match) for {}/{} PR#{}", prId.getOwner(), prId.getRepo(), prId.getPrNumber());
                return new PRAnalysisResponse(cached.getId(), true, "Analysis retrieved from cache");
            }
            log.info("Cache stale (SHA mismatch) for {}/{} PR#{} — re-analysing",
                    prId.getOwner(), prId.getRepo(), prId.getPrNumber());
        }

        PRMetadata metadata = githubService.fetchPRMetadata(prId, effectiveToken);
        log.info("Fetched PR metadata: {}", metadata);

        List<GitHubFile> ghFiles = githubService.fetchDiffFiles(prId, effectiveToken);
        log.info("Fetched {} files for analysis", ghFiles.size());

        log.info("Starting analysis pipeline");
        PRIntelligenceResponse intelligence = executeAnalysisPipeline(prId, request.getAiProvider(), metadata, ghFiles, effectiveToken);

        PRAnalysisResult result = cacheService.save(prId, intelligence, metadata.getHeadSha(), analyzedBy);

        List<String> changedFileList = intelligence.getMetrics().getFileChanges().stream()
                                               .map(FileChangeSummary::getFilename)
                                               .toList();

        log.info("Starting diagram and AI summary generation");
        diagramService.generateDiagram(
                result,
                intelligence,
                metadata,
                effectiveToken,
                prId,
                changedFileList,
                request.getAiProvider(),
                ghFiles,
                request.getDiagramMaxParticipants(),
                request.getDiagramMaxArrows()
        );
        log.info("Diagram and AI summary generation complete — returning response");

        return new PRAnalysisResponse(result.getId(), false, "Analysis completed");
    }

    private PRIntelligenceResponse executeAnalysisPipeline(
            PRIdentifier prId,
            AIProvider provider,
            PRMetadata metadata,
            List<GitHubFile> files,
            String githubToken) {

        DiffMetrics metrics = diffAnalyzer.analyzeDiff(files, prId, metadata);
        log.info("Diff metrics: {}", metrics);

        RiskAssessment risk = riskEngine.assessRisk(metadata, metrics);
        log.info("Risk assessment: {}", risk);

        DifficultyAssessment difficulty = difficultyEngine.assessDifficulty(metadata, metrics);
        log.info("Difficulty assessment: {}", difficulty);

        BlastRadiusAssessment blastRadius = blastRadiusAnalyzer.analyze(metrics);
        log.info("Blast radius: {}", blastRadius);

        // Merge conflict status — derived from metadata fields + compare API if conflicts detected
        MergeConflictStatus mergeConflictStatus = buildMergeConflictStatus(prId, metadata, files, githubToken);

        // FIX: Use deterministic UUID derived from owner+repo+prNumber+headSha so
        // that re-analysis of the same commit produces the same analysisId.
        UUID analysisId = deriveAnalysisId(prId, metadata.getHeadSha());

        return PRIntelligenceResponse.builder()
                       .analysisId(analysisId)
                       .metadata(metadata)
                       .metrics(metrics)
                       .risk(risk)
                       .narrative(null)
                       .difficulty(difficulty)
                       .blastRadius(blastRadius)
                       .mergeConflictStatus(mergeConflictStatus)
                       .analyzedAt(Instant.now())
                       .build();
    }

    public PRIntelligenceResponse getAnalysisById(UUID analysisId) {
        PRAnalysisResult result = cacheService.findById(analysisId);
        PRIntelligenceResponse res = result.toResponse();
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

    private MergeConflictStatus buildMergeConflictStatus(
            PRIdentifier prId, PRMetadata metadata,
            List<GitHubFile> prFiles, String githubToken) {
        try {
            Boolean mergeable = metadata.getMergeable();
            String mergeableState = metadata.getMergeableState() != null
                    ? metadata.getMergeableState() : "unknown";
            boolean hasConflicts = Boolean.FALSE.equals(mergeable) || "dirty".equals(mergeableState);

            List<String> conflictingFiles = List.of();
            if (hasConflicts && !prFiles.isEmpty()
                    && metadata.getHeadSha() != null && metadata.getBaseBranch() != null) {
                List<String> baseChangedFiles = githubService.getFilesChangedOnBase(
                        prId.getOwner(), prId.getRepo(),
                        metadata.getHeadSha(), metadata.getBaseBranch(), githubToken);
                java.util.Set<String> prFileSet = prFiles.stream()
                        .map(GitHubFile::getFilename)
                        .collect(java.util.stream.Collectors.toSet());
                conflictingFiles = baseChangedFiles.stream()
                        .filter(prFileSet::contains)
                        .toList();
            }

            return MergeConflictStatus.builder()
                    .mergeable(mergeable)
                    .mergeableState(mergeableState)
                    .hasConflicts(hasConflicts)
                    .conflictFileCount(conflictingFiles.size())
                    .conflictingFiles(conflictingFiles)
                    .build();
        } catch (Exception e) {
            log.warn("[orchestrator] Failed to build merge conflict status: {}", e.getMessage());
            return MergeConflictStatus.builder()
                    .mergeable(null).mergeableState("unknown")
                    .hasConflicts(false).conflictFileCount(0).conflictingFiles(List.of())
                    .build();
        }
    }

    /**
     * Derive a deterministic UUID from the PR's immutable identity tuple:
     * owner + repo + prNumber + headSha.
     *
     * Using SHA-256 → UUID v5-style (name-based) ensures:
     * - Same commit always produces the same analysisId
     * - Different commits always produce different analysisIds
     * - No external state required
     *
     * FIX: Previously UUID.randomUUID() was used, so two analyses of the
     * same commit (e.g., after a server restart) produced different IDs,
     * breaking idempotency and audit trails.
     */
    private UUID deriveAnalysisId(PRIdentifier prId, String headSha) {
        try {
            String input = prId.getOwner() + "/" + prId.getRepo() + "#" + prId.getPrNumber() + "@" + headSha;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            // Use first 16 bytes to form a UUID (variant bits set per RFC 4122 §4.4)
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x40); // version 4 marker
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // variant marker
            return UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Failed to derive deterministic UUID, falling back to random: {}", e.getMessage());
            return UUID.randomUUID();
        }
    }

    /**
     * Parse GitHub PR URL to extract owner, repo, and PR number.
     * Expected format: https://github.com/{owner}/{repo}/pull/{number}
     */
    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) return s;
        }
        return null;
    }

    private PRIdentifier parsePRUrl(String prUrl) {
        String pattern = "https://github\\.com/([^/]+)/([^/]+)/pull/(\\d+)";
        java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher matcher = regex.matcher(prUrl);

        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid GitHub PR URL: " + prUrl);
        }

        return new PRIdentifier(
                matcher.group(1),
                matcher.group(2),
                Integer.parseInt(matcher.group(3))
        );
    }
}