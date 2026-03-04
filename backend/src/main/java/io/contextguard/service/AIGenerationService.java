package io.contextguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contextguard.analysis.flow.CallGraphDiff;
import io.contextguard.analysis.flow.FlowEdge;
import io.contextguard.analysis.flow.FlowNode;
import io.contextguard.client.AIClient;
import io.contextguard.client.AIProvider;
import io.contextguard.client.AIRouter;
import io.contextguard.dto.*;
import io.contextguard.engine.DiffParser;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI NARRATIVE GENERATION SERVICE
 *
 * CHANGED (2025-03):
 *
 * 1. Prompt now uses a CONSTRAINED ACTOR LIST derived directly from
 *    CallGraphDiff.nodesAdded + nodesModified. The AI is instructed:
 *      "Only reference methods/classes that appear in CHANGED_NODES below.
 *       Do NOT invent calls not present in CALL_GRAPH."
 *    This reduces hallucination risk significantly.
 *
 * 2. SEQUENCE_DIAGRAM_CONTEXT section added to the prompt. The AI receives
 *    the actual sequence steps (entry point → call chain) so the narrative
 *    can reference specific flow steps ("As shown in the sequence diagram,
 *    analyzeOrRetrieve() first checks the cache before calling GitHub...").
 *
 * 3. NEGATIVE EXAMPLES injected: the prompt includes the list of unchanged
 *    nodes so the AI knows what NOT to reference.
 *
 * 4. Two-pass JSON extraction: first extract valid JSON object, then parse,
 *    so malformed AI output (markdown fences, preamble text) is stripped.
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
                metadata, metrics, risk, difficulty, blastRadius, files, callGraph);

        try {
            AIClient client = aiRouter.getClient(provider);
//            AIClient client=aiRouter.getClient(AIProvider.GEMINI);
            String aiResponse = client.generateSummary(prompt);
            String json = extractJsonObject(cleanJson(aiResponse));
            return parseJsonResponse(json);
        } catch (Exception e) {
            return generateFallbackSummary(metadata, metrics);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PROMPT BUILDER
    // ─────────────────────────────────────────────────────────────────────

    private String buildEnhancedPrompt(
            PRMetadata metadata,
            DiffMetrics metrics,
            RiskAssessment risk,
            DifficultyAssessment difficulty,
            BlastRadiusAssessment blastRadius,
            List<GitHubFile> files,
            CallGraphDiff callGraph) {

        List<FileChangeSummary> priorityFiles = selectPriorityFiles(metrics.getFileChanges(), 8);

        String fileEvidence        = buildDetailedFileEvidence(priorityFiles, files);
        String methodEvidence      = buildMethodChangesEvidence(priorityFiles);
        String callGraphInsights   = buildCallGraphInsights(callGraph);

        // NEW: constrained actor list for hallucination prevention
        String changedNodeList     = buildChangedNodeList(callGraph);
        String callChainSummary    = buildCallChainSummary(callGraph);
        String unchangedNodeWarning = buildUnchangedNodeWarning(callGraph);

        String crossFileImpacts    = buildCrossFileImpactAnalysis(priorityFiles, callGraph);
        String testCoverageAnalysis = buildTestCoverageAnalysis(metrics, files);

        return String.format(
                enhancedPromptTemplate,
                safe(metadata.getTitle()),
                safe(metadata.getBody()),
                metadata.getBaseBranch(),
                metadata.getHeadBranch(),
                metadata.getAuthor(),
                metrics.getTotalFilesChanged(),
                metrics.getLinesAdded(),
                metrics.getLinesDeleted(),
                metrics.getNetLinesChanged(),
                metrics.getComplexityDelta(),
                risk.getLevel(),
                String.format("%.1f%%", risk.getOverallScore() * 100),
                difficulty.getLevel(),
                difficulty.getEstimatedReviewMinutes(),
                blastRadius.getScope(),
                blastRadius.getAffectedModules(),
                String.join(", ", blastRadius.getImpactedAreas()),
                formatFileTypes(metrics.getFileTypeDistribution()),
                changedNodeList,         // NEW: constrained actor list
                callChainSummary,        // NEW: sequence diagram steps
                unchangedNodeWarning,    // NEW: negative examples
                fileEvidence,
                methodEvidence,
                callGraphInsights,
                crossFileImpacts,
                testCoverageAnalysis,
                formatCriticalFiles(metrics.getCriticalFiles())
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // NEW: Constrained actor list — prevents hallucination
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build the list of CHANGED nodes to give the AI an explicit whitelist.
     * The AI prompt instructs it to ONLY reference these in BEHAVIORAL_CHANGES.
     */
    private String buildChangedNodeList(CallGraphDiff callGraph) {
        if (callGraph == null) return "No call graph available.";

        StringBuilder sb = new StringBuilder();

        List<FlowNode> added = callGraph.getNodesAdded() != null
                                       ? callGraph.getNodesAdded() : Collections.emptyList();
        List<FlowNode> modified = callGraph.getNodesModified() != null
                                          ? callGraph.getNodesModified() : Collections.emptyList();

        if (added.isEmpty() && modified.isEmpty()) return "No method-level changes detected.";

        if (!added.isEmpty()) {
            sb.append("ADDED methods (new behavior):\n");
            added.stream().limit(15).forEach(n ->
                                                     sb.append("  + ").append(simplifyId(n.getId()))
                                                             .append(" [complexity=").append(n.getCyclomaticComplexity()).append("]\n"));
        }
        if (!modified.isEmpty()) {
            sb.append("MODIFIED methods (changed behavior):\n");
            modified.stream().limit(15).forEach(n ->
                                                        sb.append("  ~ ").append(simplifyId(n.getId()))
                                                                .append(" [complexity=").append(n.getCyclomaticComplexity()).append("]\n"));
        }

        return sb.toString();
    }

    /**
     * Build the call chain from added edges as a numbered sequence.
     * This is the same logic the SequenceDiagramRenderer uses, summarized as text.
     * Gives the AI a "script" of what the sequence diagram shows.
     */
    private String buildCallChainSummary(CallGraphDiff callGraph) {
        if (callGraph == null || callGraph.getEdgesAdded() == null
                    || callGraph.getEdgesAdded().isEmpty()) {
            return "No new call chains introduced in this PR.";
        }

        StringBuilder sb = new StringBuilder("New call chain (sequence diagram flow):\n");
        int step = 1;

        // Find entry point: added node with no incoming added edges
        Set<String> hasIncoming = callGraph.getEdgesAdded().stream()
                                          .map(FlowEdge::getTo)
                                          .collect(Collectors.toSet());

        Optional<FlowNode> entryOpt = callGraph.getNodesAdded() != null
                                              ? callGraph.getNodesAdded().stream()
                                                        .filter(n -> !hasIncoming.contains(n.getId()))
                                                        .findFirst()
                                              : Optional.empty();

        if (entryOpt.isPresent()) {
            sb.append("  ").append(step++).append(". [ENTRY] ").append(simplifyId(entryOpt.get().getId())).append("\n");
        }

        // Walk edges in declared order (approximate BFS)
        Set<String> visited = new LinkedHashSet<>();
        for (FlowEdge edge : callGraph.getEdgesAdded().stream().limit(20).collect(Collectors.toList())) {
            String sig = edge.getFrom() + "→" + edge.getTo();
            if (visited.contains(sig)) continue;
            visited.add(sig);
            sb.append("  ").append(step++).append(". ")
                    .append(simplifyId(edge.getFrom()))
                    .append(" → ")
                    .append(simplifyId(edge.getTo()))
                    .append("\n");
        }

        return sb.toString();
    }

    /**
     * Build the UNCHANGED node list as negative examples for the AI.
     * Prompt: "Do NOT reference these — they were not changed by this PR."
     */
    private String buildUnchangedNodeWarning(CallGraphDiff callGraph) {
        if (callGraph == null || callGraph.getNodesUnchanged() == null
                    || callGraph.getNodesUnchanged().isEmpty()) {
            return "N/A";
        }
        return "DO NOT reference these unchanged methods:\n" +
                       callGraph.getNodesUnchanged().stream()
                               .limit(10)
                               .map(n -> "  - " + simplifyId(n.getId()))
                               .collect(Collectors.joining("\n"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // EXISTING evidence builders (unchanged from previous version)
    // ─────────────────────────────────────────────────────────────────────

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
            sb.append("Complexity: ").append(f.getTotalComplexityBefore())
                    .append(" → ").append(f.getTotalComplexityAfter())
                    .append(" (Δ").append(f.getComplexityDelta()).append(")\n");

            if (f.getCriticalDetectionResult() != null
                        && !f.getCriticalDetectionResult().getReasons().isEmpty()) {
                sb.append("⚠️  Risk Signals:\n");
                f.getCriticalDetectionResult().getReasons().forEach(r -> sb.append("   • ").append(r).append("\n"));
            }

            if (f.getRiskLevel() == RiskLevel.HIGH || f.getRiskLevel() == RiskLevel.CRITICAL) {
                List<String> addedLines = summarizeCodeLines(diffParser.extractAddedLines(ghFile.getPatch()), 15);
                if (!addedLines.isEmpty()) {
                    sb.append("\n📝 Key Additions:\n");
                    addedLines.forEach(l -> sb.append("  + ").append(l).append("\n"));
                }
            }

            if (f.getReason() != null && !f.getReason().isBlank()) {
                sb.append("\n💡 Analysis: ").append(f.getReason()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildMethodChangesEvidence(List<FileChangeSummary> files) {
        StringBuilder sb = new StringBuilder();
        for (FileChangeSummary file : files) {
            if (file.getMethodChanges() == null || file.getMethodChanges().isEmpty()) continue;
            List<MethodChange> significant = file.getMethodChanges().stream()
                                                     .filter(m -> m.getChangeType() != MethodChange.MethodChangeType.UNCHANGED)
                                                     .filter(m -> Math.abs(m.getComplexityDelta()) > 2
                                                                          || m.getChangeType() == MethodChange.MethodChangeType.ADDED
                                                                          || m.getChangeType() == MethodChange.MethodChangeType.DELETED)
                                                     .limit(5)
                                                     .collect(Collectors.toList());
            if (significant.isEmpty()) continue;
            sb.append("\n📐 Methods Changed in ").append(file.getFilename()).append(":\n");
            significant.forEach(m -> sb.append("  ").append(formatMethodChange(m)).append("\n"));
        }
        return sb.isEmpty() ? "No significant method-level changes detected." : sb.toString();
    }

    private String buildCallGraphInsights(CallGraphDiff callGraph) {
        if (callGraph == null || callGraph.getMetrics() == null) return "Call graph analysis not available.";
        StringBuilder sb = new StringBuilder();
        CallGraphDiff.GraphMetrics m = callGraph.getMetrics();
        sb.append("\n📊 Call Graph Metrics:\n");
        sb.append("  • Nodes: ").append(m.getTotalNodes()).append("\n");
        sb.append("  • Edges: ").append(m.getTotalEdges()).append("\n");
        sb.append("  • Max Depth: ").append(m.getMaxDepth()).append("\n");
        sb.append("  • Avg Complexity: ").append(String.format("%.1f", m.getAvgComplexity())).append("\n");
        if (m.getHotspots() != null && !m.getHotspots().isEmpty()) {
            sb.append("  • Hotspots: ")
                    .append(m.getHotspots().stream().limit(3).map(this::simplifyId).collect(Collectors.joining(", ")))
                    .append("\n");
        }
        return sb.toString();
    }

    private String buildCrossFileImpactAnalysis(List<FileChangeSummary> files, CallGraphDiff callGraph) {
        if (callGraph == null || callGraph.getEdgesAdded() == null) return "Cross-file analysis not available.";
        Map<String, Set<String>> fileDeps = new LinkedHashMap<>();
        for (FlowEdge edge : callGraph.getEdgesAdded()) {
            String from = extractFileFromNodeId(edge.getFrom(), files);
            String to   = extractFileFromNodeId(edge.getTo(), files);
            if (from != null && to != null && !from.equals(to)) {
                fileDeps.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
            }
        }
        if (fileDeps.isEmpty()) return "No significant cross-file dependencies introduced.";
        StringBuilder sb = new StringBuilder("\n🔗 New Cross-File Dependencies:\n");
        fileDeps.entrySet().stream().limit(5).forEach(e -> {
            sb.append("  • ").append(simplifyPath(e.getKey())).append(" → ");
            sb.append(e.getValue().stream().map(this::simplifyPath).collect(Collectors.joining(", ")));
            sb.append("\n");
        });
        return sb.toString();
    }

    private String buildTestCoverageAnalysis(DiffMetrics metrics, List<GitHubFile> files) {
        long testFiles = files.stream().filter(f -> isTestFile(f.getFilename())).count();
        long prodFiles = files.size() - testFiles;
        StringBuilder sb = new StringBuilder("\n🧪 Test Coverage:\n");
        sb.append("  • Production: ").append(prodFiles).append("  • Test: ").append(testFiles).append("\n");
        if (testFiles == 0 && prodFiles > 0) sb.append("  ⚠️ No test files modified.\n");
        else if (testFiles > 0 && prodFiles > 0) {
            double ratio = (double) testFiles / prodFiles;
            sb.append(ratio >= 0.5 ? "  ✅ " : "  ⚠️  ")
                    .append(String.format("%.0f%%", ratio * 100)).append(" test-to-production ratio.\n");
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    // UTILITIES
    // ─────────────────────────────────────────────────────────────────────

    private List<FileChangeSummary> selectPriorityFiles(List<FileChangeSummary> files, int limit) {
        return files.stream()
                       .filter(f -> !isTestFile(f.getFilename()))
                       .sorted(Comparator.comparing(FileChangeSummary::getRiskLevel).reversed()
                                       .thenComparing(f -> Math.abs(f.getComplexityDelta()), Comparator.reverseOrder()))
                       .limit(limit)
                       .collect(Collectors.toList());
    }

    private List<String> summarizeCodeLines(List<String> lines, int limit) {
        return lines.stream().map(String::trim).filter(l -> !l.isBlank() && l.length() > 5)
                       .filter(l -> !l.matches("[{}();]+"))
                       .filter(l -> !l.startsWith("//") && !l.startsWith("/*"))
                       .filter(l -> !l.startsWith("import ") && !l.startsWith("package "))
                       .limit(limit).collect(Collectors.toList());
    }

    private String formatMethodChange(MethodChange m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getChangeType()).append(": ").append(m.getMethodName());
        if (m.getComplexityDelta() != 0)
            sb.append(" [").append(m.getComplexityBefore()).append("→").append(m.getComplexityAfter()).append("]");
        if (m.getChangeDescription() != null) sb.append(" - ").append(m.getChangeDescription());
        return sb.toString();
    }

    private String simplifyId(String fqn) {
        if (fqn == null) return "";
        String[] parts = fqn.split("\\.");
        if (parts.length < 2) return fqn;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private String simplifyPath(String path) {
        if (path == null) return "";
        String[] parts = path.split("/");
        return parts.length < 3 ? path : "…/" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    private String extractFileFromNodeId(String nodeId, List<FileChangeSummary> files) {
        for (FileChangeSummary f : files) {
            if (nodeId.toLowerCase().contains(extractBaseName(f.getFilename()).toLowerCase()))
                return f.getFilename();
        }
        return null;
    }

    private String extractBaseName(String path) {
        String[] p = path.split("/");
        String fn = p[p.length - 1];
        int dot = fn.lastIndexOf('.');
        return dot > 0 ? fn.substring(0, dot) : fn;
    }

    private boolean isTestFile(String filename) {
        String l = filename.toLowerCase();
        return l.contains("/test/") || l.endsWith("test.java") || l.endsWith("spec.js") || l.endsWith("_test.py");
    }

    private String formatFileTypes(Map<String, Integer> d) {
        return d.entrySet().stream().map(e -> e.getKey() + "(" + e.getValue() + ")")
                       .collect(Collectors.joining(", "));
    }

    private String formatCriticalFiles(List<String> files) {
        return files.isEmpty() ? "None" : String.join(", ", files);
    }

    private String safe(String s) { return s == null || s.isBlank() ? "Not provided" : s; }

    private String cleanJson(String r) { return r.replaceAll("```json", "").replaceAll("```", "").trim(); }

    private String extractJsonObject(String text) {
        int start = text.indexOf("{");
        if (start == -1) throw new IllegalArgumentException("No JSON in AI response");
        int count = 0;
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '{') count++;
            if (text.charAt(i) == '}') count--;
            if (count == 0) return text.substring(start, i + 1);
        }
        throw new IllegalArgumentException("Malformed JSON in AI response");
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
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) throw new IllegalStateException("Missing AI field: " + field);
        if (v.isArray()) {
            StringBuilder sb = new StringBuilder();
            v.forEach(item -> sb.append("- ").append(item.asText()).append("\n"));
            return sb.toString().trim();
        }
        return v.asText();
    }

    private AIGeneratedNarrative generateFallbackSummary(PRMetadata metadata, DiffMetrics metrics) {
        return AIGeneratedNarrative.builder()
                       .overview("Analysis summary temporarily unavailable.")
                       .structuralImpact("See file changes section.")
                       .behavioralChanges("See method-level changes.")
                       .riskInterpretation("See risk assessment section.")
                       .reviewFocus("Focus on high-risk files and complexity increases.")
                       .checklist("- Review high-risk files\n- Verify test coverage\n- Check public API changes")
                       .confidence("MEDIUM - Generated from template")
                       .generatedAt(Instant.now())
                       .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PROMPT TEMPLATE
    // ─────────────────────────────────────────────────────────────────────

    private String loadEnhancedPromptTemplate() {
        return """
You are a senior software engineer performing code review.
Generate a precise, actionable PR summary grounded ONLY in the evidence below.

 
PR CONTEXT
 
Title:    %s
Description: %s
Branch:   %s → %s
Author:   %s

 
QUANTITATIVE METRICS
 
Files Changed:     %d
Lines Added:       %d
Lines Deleted:     %d
Net Change:        %d
Complexity Delta:  %d

Risk Level:        %s (Score: %s)
Difficulty:        %s (Est. Review Time: %d minutes)
Blast Radius:      %s
Affected Modules:  %d
Impacted Areas:    %s
File Types:        %s

 
CHANGED METHODS — USE ONLY THESE IN BEHAVIORAL_CHANGES
(Any method not listed here was NOT changed by this PR)
 
%s

 
SEQUENCE DIAGRAM FLOW
(Runtime execution path introduced by this PR)
 
%s

 
NEGATIVE EXAMPLES — DO NOT REFERENCE THESE
 
%s

 
FILE-LEVEL EVIDENCE
 
%s

 
METHOD-LEVEL CHANGES
 
%s

 
CALL GRAPH INSIGHTS
 
%s

 
CROSS-FILE IMPACT
 
%s

 
TEST COVERAGE
 
%s

Critical Files Detected: %s

 
GENERATE ANALYSIS
 

OVERVIEW:
[3-5 sentences: PR purpose, scope, entry point of the changed flow]

STRUCTURAL_IMPACT:
[Bullet points: which components are affected, layer-level changes, API surface impact]

BEHAVIORAL_CHANGES:
[Concrete behavioral changes — use ONLY methods from the CHANGED METHODS list above.
 Reference the SEQUENCE DIAGRAM FLOW steps where relevant.
 If a change is unclear from the evidence, write "Not determinable from provided data."]

RISK_INTERPRETATION:
[Why this risk level? Reference specific files and complexity changes. Note test gaps.]

REVIEW_FOCUS:
[Specific files and methods reviewers must inspect, ordered by risk/impact]

CHECKLIST:
[Actionable verification steps based on the actual changes detected]

CONFIDENCE:
HIGH / MEDIUM / LOW — [one-line reason]

STRICT RULES:
1. ONLY reference methods from the CHANGED METHODS list — zero exceptions
2. DO NOT reference any method from the NEGATIVE EXAMPLES list
3. Reference the sequence diagram flow steps by number when possible
4. No generic advice ("ensure tests pass", "add documentation")
5. Keep total response under 600 words
6. Return ONLY this JSON (no markdown fences, no preamble):

{
  "OVERVIEW": "...",
  "STRUCTURAL_IMPACT": "...",
  "BEHAVIORAL_CHANGES": "...",
  "RISK_INTERPRETATION": "...",
  "REVIEW_FOCUS": "...",
  "CHECKLIST": "...",
  "CONFIDENCE": "..."
}
""";
    }
}