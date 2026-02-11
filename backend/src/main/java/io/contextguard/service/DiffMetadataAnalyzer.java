package io.contextguard.service;

import io.contextguard.dto.*;
import io.contextguard.engine.ComplexityEstimator;
import io.contextguard.service.criticalpath.CriticalPathDetector;
import io.contextguard.engine.DiffParser;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
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




    private final DiffParser diffParser;
    private final ComplexityEstimator complexityEstimator;
    private final CriticalPathDetector criticalPathDetector;


    public DiffMetadataAnalyzer(
            DiffParser diffParser,
            ComplexityEstimator complexityEstimator,
            CriticalPathDetector criticalPathDetector) {

        this.diffParser = diffParser;
        this.complexityEstimator = complexityEstimator;
        this.criticalPathDetector = criticalPathDetector;
    }

    public DiffMetrics analyzeDiff(List<GitHubFile> files, PRIdentifier prId, PRMetadata metadata) {

        // 1. Calculate LOC metrics
        int totalAdditions = files.stream().mapToInt(GitHubFile::getAdditions).sum();
        int totalDeletions = files.stream().mapToInt(GitHubFile::getDeletions).sum();
        int netChange = totalAdditions - totalDeletions;


        // 2. Detect file types
        Map<String, Integer> fileTypeDistribution = files.stream().collect(Collectors.groupingBy(
                                                                    this::extractFileExtension,
                                                                    Collectors.summingInt(f -> 1)
                                                            ));

        // 3. Detect critical files
        List<CriticalDetectionResult> criticalResults = criticalPathDetector.detect(files, prId.getOwner(), prId.getRepo());
        List<String> criticalFiles = criticalResults.stream()
                                             .filter(CriticalDetectionResult::isCritical)
                                             .map(CriticalDetectionResult::getFilename)
                                             .toList();

        Map<String, CriticalDetectionResult> criticalResultMap = criticalResults.stream().collect(Collectors.toMap(
                                CriticalDetectionResult::getFilename,
                                Function.identity()));

        // 3. Parse diffs and estimate complexity delta
        List<FileChangeSummary> fileChanges = files.stream()
                                                      .map(file -> analyzeFile(file, criticalResultMap.get(file.getFilename()) ))
                                                      .toList();

        int complexityDelta = fileChanges.stream()
                                      .mapToInt(FileChangeSummary::getComplexityDelta)
                                      .sum();

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
    private FileChangeSummary analyzeFile(GitHubFile file, CriticalDetectionResult criticalDetectionResult) {

        // Parse diff hunks to extract added/deleted line content
        List<String> addedLines = diffParser.extractAddedLines(file.getPatch());
        List<String> deletedLines = diffParser.extractDeletedLines(file.getPatch());

        // Estimate complexity change (heuristic: count control structures)
        //it is later updated to be more accurate by AST Parsing.
        int complexityDelta = complexityEstimator.estimateDelta(addedLines, deletedLines);

        RiskLevel riskLevel = classifyFileRisk(file.getAdditions(), file.getDeletions(), file.getStatus(),complexityDelta, criticalDetectionResult.isCritical());


        return FileChangeSummary.builder()
                       .filename(file.getFilename())
                       .changeType(file.getStatus())
                       .linesAdded(file.getAdditions())
                       .linesDeleted(file.getDeletions())
                       .complexityDelta(complexityDelta)
                       .riskLevel(riskLevel)
                       .methodSignatures(null)
                       .beforeSnippet(null)
                       .afterSnippet(null)
                       .criticalDetectionResult(criticalDetectionResult)
                       .build();
    }

    private String extractFileExtension(GitHubFile file) {
        String filename = file.getFilename();
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "unknown";
    }

    private RiskLevel classifyFileRisk(int additions, int deletions,String changeType,
            int complexityDelta,
            boolean isCritical) {

        int absComplexity = Math.abs(complexityDelta);
        int complexityContribution = absComplexity >= 15 ? 4 : absComplexity >= 10 ? 3 : absComplexity >= 5 ? 2 : absComplexity > 0 ? 1 : 0;
        int churn = additions + deletions;
        int churnContribution = churn >= 300 ? 3 : churn >= 150 ? 2 : churn >= 50 ? 1 : 0;
        int criticalContribution = isCritical ? 3 : 0;
        int deletionContribution = ("removed").equalsIgnoreCase(changeType) || ("deleted").equalsIgnoreCase(changeType) ? 2 : 0;
        int riskScore =
                complexityContribution +
                        churnContribution +
                        criticalContribution +
                        deletionContribution;

        if (riskScore >= 7) {
            return RiskLevel.HIGH;
        }

        if (riskScore >= 4) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

}
