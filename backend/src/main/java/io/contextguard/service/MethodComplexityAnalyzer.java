package io.contextguard.service;

import io.contextguard.analysis.flow.ASTParserService;
import io.contextguard.analysis.flow.FlowNode;
import io.contextguard.dto.FileChangeSummary;
import io.contextguard.dto.MethodChange;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes method-level complexity changes using AST-derived data.
 *
 * ─────────────────────────────────────────────────────────────
 * BUG-C5 FIX — parseFileIfExists() called twice with same args
 * ─────────────────────────────────────────────────────────────
 * BEFORE (broken):
 *   Map<String, FlowNode> baseMethods = parseFileIfExists(filename);
 *   Map<String, FlowNode> headMethods = parseFileIfExists(filename);
 *   // Both use same filename → base == head → complexityDelta always 0
 *
 * AFTER (fixed):
 *   parseFileIfExists(repoName, filename, baseSha)  ← base commit
 *   parseFileIfExists(repoName, filename, headSha)  ← head commit
 *   // Different SHAs → actual diff → real complexityDelta
 *
 * enrichWithMethodComplexity() now requires repoName, baseSha, headSha
 * so the caller (PRAnalysisOrchestrator) must pass those values through.
 * They are already available in PRMetadata.
 */
@Service
public class MethodComplexityAnalyzer {

    private final ASTParserService astParser;

    public MethodComplexityAnalyzer(ASTParserService astParser) {
        this.astParser = astParser;
    }

    /**
     * Enrich file changes with method-level complexity analysis.
     *
     * @param fileChanges Initial file changes from diff analysis
     * @param repoName    Full repo name (owner/repo) for GitHub API calls
     * @param baseSha     Git SHA of the base commit (before the PR)
     * @param headSha     Git SHA of the head commit (after the PR)
     * @return Enriched file changes with accurate method-level complexity
     */
    public List<FileChangeSummary> enrichWithMethodComplexity(
            List<FileChangeSummary> fileChanges,
            String repoName,
            String baseSha,
            String headSha,
            String token) {

        List<FileChangeSummary> enriched = new ArrayList<>();

        for (FileChangeSummary fileChange : fileChanges) {
            try {
                FileChangeSummary enrichedFile = analyzeFile(fileChange, repoName, baseSha, headSha, token);
                enriched.add(enrichedFile);
            } catch (Exception e) {
                // Fall back to heuristic values already set by DiffMetadataAnalyzer
                enriched.add(fileChange);
            }
        }

        return enriched;
    }

    /**
     * Analyze a single file for method-level changes.
     *
     * BUG-C5 FIX: Both base and head now pass their respective SHAs.
     */
    private FileChangeSummary analyzeFile(
            FileChangeSummary fileChange,
            String repoName,
            String baseSha,
            String headSha,
            String token) {

        String filename = fileChange.getFilename();

        // BUG-C5 FIX: Different SHAs for base vs head
        Map<String, FlowNode> baseMethods = parseFileIfExists(repoName, filename, baseSha, token);
        Map<String, FlowNode> headMethods = parseFileIfExists(repoName, filename, headSha, token);

        List<MethodChange> methodChanges = computeMethodChanges(baseMethods, headMethods);

        int totalComplexityBefore = baseMethods.values().stream()
                                            .mapToInt(FlowNode::getCyclomaticComplexity)
                                            .sum();

        int totalComplexityAfter = headMethods.values().stream()
                                           .mapToInt(FlowNode::getCyclomaticComplexity)
                                           .sum();

        int complexityDelta = totalComplexityAfter - totalComplexityBefore;

        fileChange.setMethodChanges(methodChanges);
        fileChange.setTotalComplexityBefore(totalComplexityBefore);
        fileChange.setTotalComplexityAfter(totalComplexityAfter);
        fileChange.setComplexityDelta(complexityDelta);   // ← overwrites heuristic with AST value
        fileChange.setReason(generateComplexityExplanation(methodChanges, complexityDelta));

        return fileChange;
    }

    /**
     * Parse a single file at a specific Git SHA and extract its methods.
     *
     * BUG-C5 FIX: Now accepts (repoName, filename, ref) — different SHAs
     * for base and head are now distinguishable.
     *
     * Returns empty map on any failure so the caller falls back to heuristics.
     */
    private Map<String, FlowNode> parseFileIfExists(String repoName, String filename, String ref, String token) {
        try {
            ASTParserService.ParsedCallGraph graph =
                    astParser.parseDirectoryFromGithub(repoName, ref, List.of(filename), token);

            return graph.nodes.entrySet().stream()
                           .filter(entry -> filename.equals(entry.getValue().getFilePath()))
                           .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * Compute method-level changes between base and head.
     */
    private List<MethodChange> computeMethodChanges(
            Map<String, FlowNode> baseMethods,
            Map<String, FlowNode> headMethods) {

        List<MethodChange> changes = new ArrayList<>();

        Set<String> allMethodIds = new HashSet<>();
        allMethodIds.addAll(baseMethods.keySet());
        allMethodIds.addAll(headMethods.keySet());

        for (String methodId : allMethodIds) {
            FlowNode baseMethod = baseMethods.get(methodId);
            FlowNode headMethod = headMethods.get(methodId);
            changes.add(createMethodChange(methodId, baseMethod, headMethod));
        }

        changes.sort((a, b) -> Integer.compare(
                Math.abs(b.getComplexityDelta()),
                Math.abs(a.getComplexityDelta())));

        return changes;
    }

    private MethodChange createMethodChange(String methodId, FlowNode baseMethod, FlowNode headMethod) {

        MethodChange.MethodChangeType changeType;
        int complexityBefore = 0;
        int complexityAfter  = 0;
        String methodName;
        String methodSignature = extractSignature(methodId);
        int startLine = 0, endLine = 0;
        String returnType = "";
        Set<String> annotations = Collections.emptySet();

        if (baseMethod == null && headMethod != null) {
            changeType       = MethodChange.MethodChangeType.ADDED;
            complexityAfter  = headMethod.getCyclomaticComplexity();
            methodName       = headMethod.getLabel();
            startLine        = headMethod.getStartLine();
            endLine          = headMethod.getEndLine();
            returnType       = headMethod.getReturnType();
            annotations      = headMethod.getAnnotations();

        } else if (baseMethod != null && headMethod == null) {
            changeType       = MethodChange.MethodChangeType.DELETED;
            complexityBefore = baseMethod.getCyclomaticComplexity();
            methodName       = baseMethod.getLabel();
            startLine        = baseMethod.getStartLine();
            endLine          = baseMethod.getEndLine();
            returnType       = baseMethod.getReturnType();
            annotations      = baseMethod.getAnnotations();

        } else if (baseMethod != null) {
            complexityBefore = baseMethod.getCyclomaticComplexity();
            complexityAfter  = headMethod.getCyclomaticComplexity();
            methodName       = headMethod.getLabel();
            startLine        = headMethod.getStartLine();
            endLine          = headMethod.getEndLine();
            returnType       = headMethod.getReturnType();
            annotations      = headMethod.getAnnotations();

            boolean complexityChanged   = complexityBefore != complexityAfter;
            boolean annotationsChanged  = !Objects.equals(baseMethod.getAnnotations(), headMethod.getAnnotations());
            boolean locChanged          = (baseMethod.getEndLine() - baseMethod.getStartLine()) !=
                                                  (headMethod.getEndLine() - headMethod.getStartLine());

            changeType = (complexityChanged || annotationsChanged || locChanged)
                                 ? MethodChange.MethodChangeType.MODIFIED
                                 : MethodChange.MethodChangeType.UNCHANGED;
        } else {
            throw new IllegalStateException("Both base and head methods are null for: " + methodId);
        }

        int complexityDelta = complexityAfter - complexityBefore;
        String description  = generateChangeDescription(changeType, complexityBefore, complexityAfter);

        return MethodChange.builder()
                       .methodName(methodName)
                       .methodSignature(methodSignature)
                       .changeType(changeType)
                       .complexityBefore(complexityBefore)
                       .complexityAfter(complexityAfter)
                       .complexityDelta(complexityDelta)
                       .startLine(startLine)
                       .endLine(endLine)
                       .linesChanged(endLine - startLine + 1)
                       .returnType(returnType)
                       .annotations(annotations)
                       .changeDescription(description)
                       .build();
    }

    private String extractSignature(String methodId) {
        String[] parts = methodId.split("\\.");
        return parts[parts.length - 1] + "(...)";
    }

    private String generateChangeDescription(MethodChange.MethodChangeType changeType,
                                             int complexityBefore, int complexityAfter) {
        switch (changeType) {
            case ADDED:
                return String.format("New method with complexity %d", complexityAfter);
            case DELETED:
                return String.format("Method removed (had complexity %d)", complexityBefore);
            case MODIFIED:
                int delta = complexityAfter - complexityBefore;
                if (delta > 0)
                    return String.format("Complexity increased from %d to %d (+%d)",
                            complexityBefore, complexityAfter, delta);
                else if (delta < 0)
                    return String.format("Complexity decreased from %d to %d (%d)",
                            complexityBefore, complexityAfter, delta);
                else
                    return "Method modified but complexity unchanged";
            case UNCHANGED:
                return String.format("No changes (complexity: %d)", complexityAfter);
            default:
                return "Unknown change type";
        }
    }

    private String generateComplexityExplanation(List<MethodChange> methodChanges, int complexityDelta) {
        long addedCount    = methodChanges.stream().filter(m -> m.getChangeType() == MethodChange.MethodChangeType.ADDED).count();
        long deletedCount  = methodChanges.stream().filter(m -> m.getChangeType() == MethodChange.MethodChangeType.DELETED).count();
        long modifiedCount = methodChanges.stream().filter(m -> m.getChangeType() == MethodChange.MethodChangeType.MODIFIED).count();

        StringBuilder sb = new StringBuilder();
        if      (complexityDelta > 0) sb.append(String.format("Complexity increased by %d", complexityDelta));
        else if (complexityDelta < 0) sb.append(String.format("Complexity decreased by %d", Math.abs(complexityDelta)));
        else                          sb.append("No net complexity change");

        List<String> parts = new ArrayList<>();
        if (addedCount    > 0) parts.add(addedCount    + " added");
        if (deletedCount  > 0) parts.add(deletedCount  + " deleted");
        if (modifiedCount > 0) parts.add(modifiedCount + " modified");
        if (!parts.isEmpty()) sb.append(" (").append(String.join(", ", parts)).append(" methods)");

        return sb.toString();
    }
}