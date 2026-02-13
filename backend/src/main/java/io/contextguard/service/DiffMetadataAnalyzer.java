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
 * FILE-LEVEL RISK CLASSIFICATION ENGINE
 *
 * PURPOSE:
 * Classifies individual file changes based on structural change patterns
 * without interpreting business logic semantics.
 *
 * RISK DIMENSIONS (File-Level):
 *
 * 1. Change Magnitude (Churn)
 *    → Total lines modified (additions + deletions)
 *    → Represents regression surface area
 *
 * 2. Structural Complexity Delta
 *    → Increase in control structures (if/loops/etc.)
 *    → Proxy for increased cognitive load and defect probability
 *
 * 3. Critical Path Impact
 *    → Whether file belongs to business-critical or infra-sensitive paths
 *    → Represents high business impact if failure occurs
 *
 * 4. Destructive Change Signal
 *    → File deletion/removal
 *    → Represents breaking-change probability
 *
 * Output:
 * Each file is assigned a categorical RiskLevel:
 * LOW / MEDIUM / HIGH / CRITICAL
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

    private RiskLevel classifyFileRisk(int additions,
                                       int deletions,
                                       String changeType,
                                       int complexityDelta,
                                       boolean isCriticalPath) {

        int churn = additions + deletions;
        int absComplexity = Math.abs(complexityDelta);

        //  Change Magnitude Contribution (Surface Area Risk)
        int magnitudeScore =
                churn >= 400 ? 4 :
                        churn >= 200 ? 3 :
                                churn >= 80  ? 2 :
                                        churn >= 30  ? 1 : 0;

        //  Structural Complexity Contribution
        int complexityScore =
                absComplexity >= 20 ? 4 :
                        absComplexity >= 12 ? 3 :
                                absComplexity >= 6  ? 2 :
                                        absComplexity > 0   ? 1 : 0;

        //  Business Critical Impact
        int criticalScore = isCriticalPath ? 3 : 0;

        // Destructive Change Risk
        int destructiveScore =
                ("removed".equalsIgnoreCase(changeType)
                         || "deleted".equalsIgnoreCase(changeType)) ? 2 : 0;

        int totalScore =
                magnitudeScore +
                        complexityScore +
                        criticalScore +
                        destructiveScore;

        // Thresholds calibrated to allow CRITICAL classification
        if (totalScore >= 9) return RiskLevel.CRITICAL;
        if (totalScore >= 6) return RiskLevel.HIGH;
        if (totalScore >= 3) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

}
