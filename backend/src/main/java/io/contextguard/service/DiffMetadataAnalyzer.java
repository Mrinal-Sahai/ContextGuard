package io.contextguard.service;

import io.contextguard.dto.DiffMetrics;
import io.contextguard.dto.FileChangeSummary;
import io.contextguard.dto.GitHubFile;
import io.contextguard.dto.RiskLevel;
import io.contextguard.engine.ComplexityEstimator;
import io.contextguard.engine.CriticalPathDetector;
import io.contextguard.engine.DiffParser;
import org.springframework.stereotype.Service;

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

    public DiffMetrics analyzeDiff(List<GitHubFile> files) {

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

        return FileChangeSummary.builder()
                       .filename(file.getFilename())
                       .changeType(file.getStatus())
                       .linesAdded(file.getAdditions())
                       .linesDeleted(file.getDeletions())
                       .complexityDelta(complexityDelta)
                       .riskLevel(riskLevel)
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
}
