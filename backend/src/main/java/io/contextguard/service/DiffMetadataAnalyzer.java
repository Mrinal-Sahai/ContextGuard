package io.contextguard.service;

import io.contextguard.client.GitHubApiClient;
import io.contextguard.dto.*;
import io.contextguard.engine.ComplexityEstimator;
import io.contextguard.engine.CriticalPathDetector;
import io.contextguard.engine.DiffHunk;
import io.contextguard.engine.DiffParser;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Analyzes diff metadata WITHOUT interpreting code semantics.
 *
 * WHY NO CODE REASONING:
 * - Reduces scope (no AST parsing, no semantic analysis)
 * - Focuses on STRUCTURE, not LOGIC
 * - Defensible in viva: "We analyze change patterns, not business logic"
 *
 * Metrics computed:
 * - Lines of Code (added/deleted)
 * - File type distribution
 * - Complexity delta (heuristic-based)
 * - Critical path detection (keyword matching)
 */
@Service
public class DiffMetadataAnalyzer {


    private static final int MAX_SNIPPET_FILES = 5;
    private static final int SNIPPET_COMPLEXITY_THRESHOLD = 5;

    private final DiffParser diffParser;
    private final ComplexityEstimator complexityEstimator;
    private final CriticalPathDetector criticalPathDetector;
    private final CodeSnippetExtractor snippetExtractor;
    private final GitHubApiClient gitHubApiClient;

    public DiffMetadataAnalyzer(
            DiffParser diffParser,
            ComplexityEstimator complexityEstimator,
            CriticalPathDetector criticalPathDetector, CodeSnippetExtractor snippetExtractor, GitHubApiClient gitHubApiClient) {

        this.diffParser = diffParser;
        this.complexityEstimator = complexityEstimator;
        this.criticalPathDetector = criticalPathDetector;
        this.snippetExtractor = snippetExtractor;
        this.gitHubApiClient = gitHubApiClient;
    }

    public DiffMetrics analyzeDiff(List<GitHubFile> files, PRIdentifier prId, PRMetadata metadata) {

        // 1. Calculate LOC metrics
        int totalAdditions = files.stream().mapToInt(GitHubFile::getAdditions).sum();
        int totalDeletions = files.stream().mapToInt(GitHubFile::getDeletions).sum();
        int netChange = totalAdditions - totalDeletions;


        // 2. Detect file types
        Map<String, Integer> fileTypeDistribution = files.stream()
                                                            .collect(Collectors.groupingBy(
                                                                    this::extractFileExtension,
                                                                    Collectors.summingInt(f -> 1)
                                                            ));

        List<String> criticalFiles = criticalPathDetector.detect(files);

        // 3. Parse diffs and estimate complexity delta
        List<FileChangeSummary> fileChanges = files.stream()
                                                      .map(file -> analyzeFile(file, criticalFiles))
                                                      .toList();

        int complexityDelta = fileChanges.stream()
                                      .mapToInt(FileChangeSummary::getComplexityDelta)
                                      .sum();

        List<FileChangeSummary> candidates = fileChanges.stream()
                                                     .filter(f -> needsSnippetExtraction(f, criticalFiles))
                                                     .sorted(Comparator.comparing((FileChangeSummary f) -> f.getRiskLevel().ordinal()).reversed()
                                                                     .thenComparing((FileChangeSummary f) -> f.getLinesAdded(), Comparator.reverseOrder()))
                                                     .limit(MAX_SNIPPET_FILES)
                                                     .collect(Collectors.toList());



        for (FileChangeSummary candidate : candidates) {
            try {
                String owner = prId.getOwner();
                String repo = prId.getRepo();
                String baseBranch = metadata.getBaseBranch();
                String headBranch = metadata.getHeadBranch();

                // fetch entire file content (may be null when file is new/deleted)
                String baseContent = null;
                String headContent = null;

                if (!"added".equalsIgnoreCase(candidate.getChangeType())) {
                    baseContent = gitHubApiClient.getFileContent(owner, repo, candidate.getFilename(), baseBranch);
                }
                if (!"deleted".equalsIgnoreCase(candidate.getChangeType())) {
                    headContent = gitHubApiClient.getFileContent(owner, repo, candidate.getFilename(), headBranch);
                }

                GitHubFile ghFile = findPatchForFile(files, candidate.getFilename());
                List<DiffHunk> hunks = (ghFile != null && ghFile.getPatch() != null)
                                ? diffParser.parseHunks(ghFile.getPatch())
                                : List.of();

                String beforeSnippet = (baseContent != null && !hunks.isEmpty())
                                               ? snippetExtractor.extractBeforeSnippet(baseContent, hunks)
                                               : null;

                String afterSnippet = (headContent != null && !hunks.isEmpty())
                                              ? snippetExtractor.extractAfterSnippet(headContent, hunks)
                                              : null;

                candidate.setBeforeSnippet(beforeSnippet);
                candidate.setAfterSnippet(afterSnippet);

            } catch (Exception e) {
                // Conservative behavior: if anything fails, leave snippets null and continue
                // In production: log at debug level with prId and filename
            }
        }





        return DiffMetrics.builder()
                       .totalFilesChanged(files.size())
                       .linesAdded(totalAdditions)
                       .linesDeleted(totalDeletions)
                       .netLinesChanged(netChange)
                       .fileTypeDistribution(fileTypeDistribution)
                       .complexityDelta(complexityDelta)
                       .criticalFiles(criticalFiles)
                       .fileChanges(fileChanges)
                       .build();
    }

    /**
     * Analyze individual file change.
     */
    private FileChangeSummary analyzeFile(GitHubFile file, List<String> criticalFiles) {

        // Parse diff hunks to extract added/deleted line content
        List<String> addedLines = diffParser.extractAddedLines(file.getPatch());
        List<String> deletedLines = diffParser.extractDeletedLines(file.getPatch());

        // Estimate complexity change (heuristic: count control structures)
        int complexityDelta = complexityEstimator.estimateDelta(addedLines, deletedLines);
        RiskLevel riskLevel = classifyFileRisk(file, complexityDelta, criticalFiles);
        String reason = buildRiskReason(file, complexityDelta, criticalFiles);


        return FileChangeSummary.builder()
                       .filename(file.getFilename())
                       .changeType(file.getStatus())
                       .linesAdded(file.getAdditions())
                       .linesDeleted(file.getDeletions())
                       .complexityDelta(complexityDelta)
                       .riskLevel(riskLevel)
                       .reason(reason)
                       .methodSignatures(null)
                       .beforeSnippet(null)
                       .afterSnippet(null)
                       .build();
    }

    private String extractFileExtension(GitHubFile file) {
        String filename = file.getFilename();
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "unknown";
    }
    /**
     * Classify file-level risk based on multiple factors.
     *
     * HIGH: Critical file OR high complexity (>10)
     * MEDIUM: Moderate complexity (5-10)
     * LOW: Low complexity (<5)
     */
    private RiskLevel classifyFileRisk(
            GitHubFile file,
            int complexityDelta,
            List<String> criticalFiles) {

        boolean isCritical = criticalFiles.contains(file.getFilename());
        int absComplexity = Math.abs(complexityDelta);

        if (isCritical || absComplexity > 10) {
            return RiskLevel.HIGH;
        }

        if (absComplexity >= 5) {
            return RiskLevel.MEDIUM;
        }

        return RiskLevel.LOW;
    }


    private String buildRiskReason(GitHubFile file, int complexityDelta, List<String> criticalFiles) {
        StringBuilder sb = new StringBuilder();
        if (criticalFiles.contains(file.getFilename())) {
            sb.append("File matched critical path keywords. ");
        }
        if (Math.abs(complexityDelta) > 0) {
            sb.append("Complexity delta: ").append(complexityDelta).append(". ");
        }
        if ("deleted".equalsIgnoreCase(file.getStatus())) {
            sb.append("File deleted. ");
        }
        return sb.toString().trim();
    }

    private boolean needsSnippetExtraction(FileChangeSummary f, List<String> criticalFiles) {
        if (criticalFiles.contains(f.getFilename())) return true;
        if (f.getRiskLevel() != null && f.getRiskLevel().ordinal() >= RiskLevel.MEDIUM.ordinal()) return true;
        return Math.abs(f.getComplexityDelta()) >= SNIPPET_COMPLEXITY_THRESHOLD;
    }

    private GitHubFile findPatchForFile(List<GitHubFile> files, String filename) {
        return files.stream()
                       .filter(f -> f.getFilename().equals(filename))
                       .findFirst()
                       .orElse(null);
    }


}
