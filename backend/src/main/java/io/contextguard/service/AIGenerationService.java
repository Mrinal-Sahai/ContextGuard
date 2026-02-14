package io.contextguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contextguard.analysis.flow.CallGraphDiff;
import io.contextguard.client.AIClient;
import io.contextguard.client.AIProvider;
import io.contextguard.client.AIRouter;
import io.contextguard.dto.*;
import io.contextguard.engine.DiffParser;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ENHANCED AI NARRATIVE GENERATION
 *
 * IMPROVEMENTS:
 * 1. Include method-level changes for high-risk files
 * 2. Add code snippets for critical changes
 * 3. Include call graph insights
 * 4. Provide test coverage analysis
 * 5. Cross-file impact correlation
 * 6. Behavioral change inference from method signatures
 *
 * PROMPT ENGINEERING STRATEGY:
 * - Give AI concrete evidence, not vague descriptions
 * - Include "before/after" context
 * - Specify output structure strictly
 * - Constrain hallucination with data-only approach
 */
@Service
public class AIGenerationService {

    private final AIRouter aiRouter;
    private final DiffParser diffParser;
    private final String enhancedPromptTemplate;

    public AIGenerationService(AIRouter aiRouter, DiffParser diffParser) {
        this.aiRouter = aiRouter;
        this.diffParser = diffParser;
        this.enhancedPromptTemplate = loadEnhancedPromptTemplate();
    }

    public AIGeneratedNarrative generateSummary(
            List<GitHubFile> files,
            PRMetadata metadata,
            DiffMetrics metrics,
            RiskAssessment risk,
            DifficultyAssessment difficulty,
            BlastRadiusAssessment blastRadius,
            CallGraphDiff callGraph,
            AIProvider provider) {

        String prompt = buildEnhancedPrompt(
                metadata, metrics, risk, difficulty, blastRadius, files, callGraph
        );

        try {
            AIClient client = aiRouter.getClient(provider);
            String aiResponse = client.generateSummary(prompt);
            String cleaned = cleanJson(aiResponse);
            String json = extractJsonObject(cleaned);

            return parseJsonResponse(json);


        } catch (Exception e) {
            return generateFallbackSummary(metadata, metrics);
        }
    }

    private String cleanJson(String response) {
        return response
                       .replaceAll("```json", "")
                       .replaceAll("```", "")
                       .trim();
    }
    private String extractJsonObject(String text) {
        int start = text.indexOf("{");
        if (start == -1) {
            throw new IllegalArgumentException("No JSON object found in AI response");
        }

        int braceCount = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') braceCount++;
            if (c == '}') braceCount--;

            if (braceCount == 0) {
                return text.substring(start, i + 1);
            }
        }

        throw new IllegalArgumentException("Malformed JSON object in AI response");
    }



    /**
     * Build enhanced prompt with rich contextual data.
     */
    private String buildEnhancedPrompt(
            PRMetadata metadata,
            DiffMetrics metrics,
            RiskAssessment risk,
            DifficultyAssessment difficulty,
            BlastRadiusAssessment blastRadius,
            List<GitHubFile> files,
            CallGraphDiff callGraph) {

        // Select top files by review priority
        List<FileChangeSummary> priorityFiles = selectPriorityFiles(metrics.getFileChanges(), 8);

        // Build evidence sections
        String fileEvidence = buildDetailedFileEvidence(priorityFiles, files);
        String methodChangesEvidence = buildMethodChangesEvidence(priorityFiles);
        String callGraphInsights = buildCallGraphInsights(callGraph, priorityFiles);
        String crossFileImpacts = buildCrossFileImpactAnalysis(priorityFiles, callGraph);
        String testCoverageAnalysis = buildTestCoverageAnalysis(metrics, files);

        return String.format(
                enhancedPromptTemplate,
                // Basic context
                safe(metadata.getTitle()),
                safe(metadata.getBody()),
                metadata.getBaseBranch(),
                metadata.getHeadBranch(),
                metadata.getAuthor(),

                // Quantitative metrics
                metrics.getTotalFilesChanged(),
                metrics.getLinesAdded(),
                metrics.getLinesDeleted(),
                metrics.getNetLinesChanged(),
                metrics.getComplexityDelta(),

                // Risk & difficulty
                risk.getLevel(),
                String.format("%.1f%%", risk.getOverallScore() * 100),
                difficulty.getLevel(),
                difficulty.getEstimatedReviewMinutes(),

                // Blast radius
                blastRadius.getScope(),
                blastRadius.getAffectedModules(),
                String.join(", ", blastRadius.getImpactedAreas()),

                // File-level evidence
                formatFileTypes(metrics.getFileTypeDistribution()),
                fileEvidence,

                // Method-level evidence
                methodChangesEvidence,

                // Call graph insights
                callGraphInsights,

                // Cross-file impacts
                crossFileImpacts,

                // Test coverage
                testCoverageAnalysis,

                // Critical files
                formatCriticalFiles(metrics.getCriticalFiles())
        );
    }

    /**
     * Build detailed file evidence with code snippets.
     */
    private String buildDetailedFileEvidence(List<FileChangeSummary> files, List<GitHubFile> ghFiles) {
        StringBuilder sb = new StringBuilder();
        Map<String, GitHubFile> ghFileMap = ghFiles.stream()
                                                    .collect(Collectors.toMap(GitHubFile::getFilename, Function.identity(), (a, b) -> a));

        for (FileChangeSummary f : files) {
            GitHubFile ghFile = ghFileMap.get(f.getFilename());
            if (ghFile == null) continue;

            sb.append("\n═══ FILE: ").append(f.getFilename()).append(" ═══\n");
            sb.append("Change Type: ").append(f.getChangeType()).append("\n");
            sb.append("Risk Level: ").append(f.getRiskLevel()).append("\n");
            sb.append("Lines: +").append(f.getLinesAdded()).append(" / -").append(f.getLinesDeleted()).append("\n");
            sb.append("Complexity: ").append(f.getTotalComplexityBefore()).append(" → ")
                    .append(f.getTotalComplexityAfter()).append(" (Δ").append(f.getComplexityDelta()).append(")\n");

            // Risk signals
            if (f.getCriticalDetectionResult() != null && !f.getCriticalDetectionResult().getReasons().isEmpty()) {
                sb.append("⚠️  Risk Signals:\n");
                for (String reason : f.getCriticalDetectionResult().getReasons()) {
                    sb.append("   • ").append(reason).append("\n");
                }
            }

            // Code snippets for HIGH/CRITICAL files
            if (f.getRiskLevel() == RiskLevel.HIGH || f.getRiskLevel() == RiskLevel.CRITICAL) {
                List<String> addedLines = summarizeCodeLines(diffParser.extractAddedLines(ghFile.getPatch()), 15);
                List<String> deletedLines = summarizeCodeLines(diffParser.extractDeletedLines(ghFile.getPatch()), 10);

                if (!addedLines.isEmpty()) {
                    sb.append("\n📝 Key Additions:\n");
                    addedLines.forEach(line -> sb.append("  + ").append(line).append("\n"));
                }

                if (!deletedLines.isEmpty()) {
                    sb.append("\n🗑️  Key Deletions:\n");
                    deletedLines.forEach(line -> sb.append("  - ").append(line).append("\n"));
                }
            }

            // Reason/explanation
            if (f.getReason() != null && !f.getReason().isBlank()) {
                sb.append("\n💡 Analysis: ").append(f.getReason()).append("\n");
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Build method-level changes evidence.
     */
    private String buildMethodChangesEvidence(List<FileChangeSummary> files) {
        StringBuilder sb = new StringBuilder();

        for (FileChangeSummary file : files) {
            if (file.getMethodChanges() == null || file.getMethodChanges().isEmpty()) continue;

            List<MethodChange> significantChanges = file.getMethodChanges().stream()
                                                            .filter(m -> m.getChangeType() != MethodChange.MethodChangeType.UNCHANGED)
                                                            .filter(m -> Math.abs(m.getComplexityDelta()) > 2 ||
                                                                                 m.getChangeType() == MethodChange.MethodChangeType.ADDED ||
                                                                                 m.getChangeType() == MethodChange.MethodChangeType.DELETED)
                                                            .limit(5)
                                                            .collect(Collectors.toList());

            if (significantChanges.isEmpty()) continue;

            sb.append("\n📐 Methods Changed in ").append(file.getFilename()).append(":\n");
            for (MethodChange method : significantChanges) {
                sb.append("  ").append(formatMethodChange(method)).append("\n");
            }
        }

        return sb.isEmpty() ? "No significant method-level changes detected." : sb.toString();
    }

    /**
     * Build call graph insights.
     */
    private String buildCallGraphInsights(CallGraphDiff callGraph, List<FileChangeSummary> files) {
        if (callGraph == null || callGraph.getMetrics() == null) {
            return "Call graph analysis not available.";
        }

        StringBuilder sb = new StringBuilder();
        CallGraphDiff.GraphMetrics metrics = callGraph.getMetrics();

        sb.append("\n📊 Call Graph Analysis:\n");
        sb.append("  • Total Nodes: ").append(metrics.getTotalNodes()).append("\n");
        sb.append("  • Total Edges: ").append(metrics.getTotalEdges()).append("\n");
        sb.append("  • Max Call Depth: ").append(metrics.getMaxDepth()).append("\n");
        sb.append("  • Avg Method Complexity: ").append(String.format("%.1f", metrics.getAvgComplexity())).append("\n");

        if (metrics.getHotspots() != null && !metrics.getHotspots().isEmpty()) {
            sb.append("\n🔥 High-Centrality Methods (called by many):\n");
            metrics.getHotspots().stream()
                    .limit(5)
                    .forEach(hotspot -> sb.append("  • ").append(simplifyMethodName(hotspot)).append("\n"));
        }

        // Call distribution insights
        if (metrics.getCallDistribution() != null && !metrics.getCallDistribution().isEmpty()) {
            sb.append("\n📞 Most Called Methods:\n");
            metrics.getCallDistribution().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(5)
                    .forEach(entry -> sb.append("  • ")
                                              .append(simplifyMethodName(entry.getKey()))
                                              .append(" (").append(entry.getValue()).append(" calls)\n"));
        }

        // New dependencies
        long newEdges = callGraph.getEdgesAdded() != null ? callGraph.getEdgesAdded().size() : 0;
        if (newEdges > 0) {
            sb.append("\n🆕 New Dependencies: ").append(newEdges).append(" new method calls introduced\n");
        }

        return sb.toString();
    }

    /**
     * Analyze cross-file impacts.
     */
    private String buildCrossFileImpactAnalysis(List<FileChangeSummary> files, CallGraphDiff callGraph) {
        if (callGraph == null) {
            return "Cross-file analysis not available.";
        }

        StringBuilder sb = new StringBuilder();

        // Find files that are interconnected
        Map<String, Set<String>> fileDependencies = new HashMap<>();

        if (callGraph.getEdgesAdded() != null) {
            for (var edge : callGraph.getEdgesAdded()) {
                // Extract file from node ID (simplified)
                String fromFile = extractFileFromNodeId(edge.getFrom(), files);
                String toFile = extractFileFromNodeId(edge.getTo(), files);

                if (fromFile != null && toFile != null && !fromFile.equals(toFile)) {
                    fileDependencies.computeIfAbsent(fromFile, k -> new HashSet<>()).add(toFile);
                }
            }
        }

        if (!fileDependencies.isEmpty()) {
            sb.append("\n🔗 Cross-File Dependencies Introduced:\n");
            fileDependencies.entrySet().stream()
                    .limit(5)
                    .forEach(entry -> {
                        sb.append("  • ").append(simplifyFilePath(entry.getKey()))
                                .append(" now calls methods in:\n");
                        entry.getValue().forEach(target ->
                                                         sb.append("    → ").append(simplifyFilePath(target)).append("\n"));
                    });
        } else {
            sb.append("\nNo significant cross-file dependencies introduced.");
        }

        return sb.toString();
    }

    /**
     * Analyze test coverage.
     */
    private String buildTestCoverageAnalysis(DiffMetrics metrics, List<GitHubFile> files) {
        StringBuilder sb = new StringBuilder();

        long testFiles = files.stream()
                                 .filter(f -> isTestFile(f.getFilename()))
                                 .count();

        long prodFiles = files.size() - testFiles;

        sb.append("\n🧪 Test Coverage Analysis:\n");
        sb.append("  • Production Files: ").append(prodFiles).append("\n");
        sb.append("  • Test Files: ").append(testFiles).append("\n");

        if (testFiles == 0 && prodFiles > 0) {
            sb.append("  ⚠️  WARNING: No test files modified. Consider adding tests.\n");
        } else if (testFiles > 0 && prodFiles > 0) {
            double ratio = (double) testFiles / prodFiles;
            if (ratio < 0.5) {
                sb.append("  ⚠️  Low test coverage: Only ")
                        .append(String.format("%.1f", ratio * 100))
                        .append("% test-to-production ratio\n");
            } else {
                sb.append("  ✅ Good test coverage: ")
                        .append(String.format("%.1f", ratio * 100))
                        .append("% test-to-production ratio\n");
            }
        }

        // Check for test files matching production files
        List<String> prodFileNames = files.stream()
                                             .filter(f -> !isTestFile(f.getFilename()))
                                             .map(f -> extractBaseName(f.getFilename()))
                                             .collect(Collectors.toList());

        List<String> testFileNames = files.stream()
                                             .filter(f -> isTestFile(f.getFilename()))
                                             .map(f -> extractBaseName(f.getFilename()).replace("Test", "").replace("Spec", ""))
                                             .collect(Collectors.toList());

        long matchingTests = prodFileNames.stream()
                                     .filter(testFileNames::contains)
                                     .count();

        if (matchingTests > 0) {
            sb.append("  ✅ ").append(matchingTests).append(" production files have corresponding test changes\n");
        }

        return sb.toString();
    }

    // ============================================================================
    // HELPER METHODS
    // ============================================================================

    private List<FileChangeSummary> selectPriorityFiles(List<FileChangeSummary> files, int limit) {
        return files.stream()
                       .filter(f -> !isTestFile(f.getFilename())) // Prioritize production code
                       .sorted(Comparator
                                       .comparing(FileChangeSummary::getRiskLevel).reversed()
                                       .thenComparing(f -> Math.abs(f.getComplexityDelta()), Comparator.reverseOrder())
                                       .thenComparing(f -> f.getLinesAdded() + f.getLinesDeleted(), Comparator.reverseOrder()))
                       .limit(limit)
                       .collect(Collectors.toList());
    }

    private List<String> summarizeCodeLines(List<String> lines, int limit) {
        return lines.stream()
                       .map(String::trim)
                       .filter(l -> !l.isBlank())
                       .filter(l -> l.length() > 5) // Skip trivial lines
                       .filter(l -> !l.matches("[{}();]+")) // Skip braces
                       .filter(l -> !l.startsWith("//") && !l.startsWith("/*")) // Skip comments
                       .filter(l -> !l.startsWith("import ") && !l.startsWith("package ")) // Skip imports
                       .limit(limit)
                       .collect(Collectors.toList());
    }

    private String formatMethodChange(MethodChange method) {
        StringBuilder sb = new StringBuilder();

        sb.append(method.getChangeType()).append(": ")
                .append(method.getMethodName());

        if (method.getComplexityDelta() != 0) {
            sb.append(" [Complexity: ")
                    .append(method.getComplexityBefore())
                    .append(" → ")
                    .append(method.getComplexityAfter())
                    .append("]");
        }

        if (method.getChangeDescription() != null) {
            sb.append(" - ").append(method.getChangeDescription());
        }

        return sb.toString();
    }

    private String simplifyMethodName(String fqn) {
        String[] parts = fqn.split("\\.");
        if (parts.length < 2) return fqn;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private String simplifyFilePath(String path) {
        String[] parts = path.split("/");
        if (parts.length < 3) return path;
        return ".../" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    private String extractFileFromNodeId(String nodeId, List<FileChangeSummary> files) {
        // Try to match node ID prefix with file paths
        for (FileChangeSummary file : files) {
            String baseName = extractBaseName(file.getFilename());
            if (nodeId.toLowerCase().contains(baseName.toLowerCase())) {
                return file.getFilename();
            }
        }
        return null;
    }

    private String extractBaseName(String path) {
        String[] parts = path.split("/");
        String fileName = parts[parts.length - 1];
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private boolean isTestFile(String filename) {
        String lower = filename.toLowerCase();
        return lower.contains("/test/") ||
                       lower.endsWith("test.java") ||
                       lower.endsWith("spec.js") ||
                       lower.endsWith("_test.py");
    }

    private String formatFileTypes(Map<String, Integer> distribution) {
        return distribution.entrySet().stream()
                       .map(e -> e.getKey() + " (" + e.getValue() + ")")
                       .collect(Collectors.joining(", "));
    }

    private String formatCriticalFiles(List<String> files) {
        return files.isEmpty() ? "None" : String.join(", ", files);
    }

    private String safe(String s) {
        return s == null || s.isBlank() ? "Not provided" : s;
    }



    private AIGeneratedNarrative generateFallbackSummary(PRMetadata metadata, DiffMetrics metrics) {
        return AIGeneratedNarrative.builder()
                       .overview("Analysis summary temporarily unavailable. Review metrics and file changes for details.")
                       .structuralImpact("See file changes section.")
                       .behavioralChanges("See method-level changes.")
                       .riskInterpretation("See risk assessment section.")
                       .reviewFocus("Focus on high-risk files and complexity increases.")
                       .checklist("- Review high-risk files\n- Verify test coverage\n- Check public API changes")
                       .confidence("MEDIUM - Generated from template")
                       .generatedAt(java.time.Instant.now())
                       .build();
    }

    private AIGeneratedNarrative parseJsonResponse(String response) throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        JsonNode root = mapper.readTree(response);

        return AIGeneratedNarrative.builder()
                       .overview(readFlexible(root, "OVERVIEW"))
                       .structuralImpact(readFlexible(root, "STRUCTURAL_IMPACT"))
                       .behavioralChanges(readFlexible(root, "BEHAVIORAL_CHANGES"))
                       .riskInterpretation(readFlexible(root, "RISK_INTERPRETATION"))
                       .reviewFocus(readFlexible(root, "REVIEW_FOCUS"))
                       .checklist(readFlexible(root, "CHECKLIST"))
                       .confidence(readFlexible(root, "CONFIDENCE"))
                       .generatedAt(Instant.now())
                       .build();
    }
    private String readFlexible(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("Missing required AI field: " + field);
        }

        if (value.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : value) {
                sb.append("- ").append(item.asText()).append("\n");
            }
            return sb.toString().trim();
        }

        return value.asText();
    }




    private String loadEnhancedPromptTemplate() {
        return """
You are a senior software engineer performing code review. Generate a precise, actionable PR summary.

PR CONTEXT
Title: %s
Description: %s
Branch: %s → %s
Author: %s


QUANTITATIVE METRICS
Files Changed: %d
Lines Added: %d
Lines Deleted: %d
Net Change: %d
Complexity Delta: %d

Risk Level: %s (Score: %s)
Difficulty: %s (Est. Review Time: %d minutes)

Blast Radius: %s
Affected Modules: %d
Impacted Areas: %s

File Types: %s

FILE-LEVEL EVIDENCE

%s


METHOD-LEVEL CHANGES

%s


CALL GRAPH INSIGHTS

%s


CROSS-FILE IMPACT ANALYSIS

%s


TEST COVERAGE ANALYSIS

%s

Critical Files Detected: %s


GENERATE ANALYSIS

Based ONLY on the evidence above, generate:

OVERVIEW:
[3-5 sentences explaining the PR's purpose and scope]

STRUCTURAL_IMPACT:
[Bullet points on architectural/codebase changes]
- Focus on what components are affected
- Mention if this is localized or system-wide
- Note any public API changes

BEHAVIORAL_CHANGES:
[Concrete behavioral changes inferred from evidence]
- Use method names and code snippets as proof
- If unclear, state "Not determinable from provided data"
- Focus on user-facing or critical path changes

RISK_INTERPRETATION:
[Explain WHY the risk level makes sense]
- Reference specific files and complexity changes
- Mention test coverage gaps if any
- Note blast radius concerns

REVIEW_FOCUS:
[Specific areas reviewers should examine]
- Name actual files and methods
- Don't give generic advice
- Prioritize by risk/impact

CHECKLIST:
[Operational verification steps]
- Concrete, actionable items
- Based on changes detected
- Include test verification if applicable

CONFIDENCE:
HIGH / MEDIUM / LOW - [Brief reason based on data completeness]


STRICT RULES

1. Use ONLY the data provided - no speculation
2. Reference file names and method names explicitly
3. NO code suggestions or improvements
4. NO generic advice ("ensure tests pass")
5. Keep under 500 words total
6. If evidence is unclear, say so
7. Return ONLY valid JSON in this exact format:
                
                   {
                     "OVERVIEW": "...",
                     "STRUCTURAL_IMPACT": "...",
                     ...
                   }

Generate the analysis now.
""";
    }
}