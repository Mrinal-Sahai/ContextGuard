package io.contextguard.service;


import io.contextguard.analysis.flow.ASTParserService;
import io.contextguard.analysis.flow.FlowNode;
import io.contextguard.dto.FileChangeSummary;
import io.contextguard.dto.MethodChange;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes method-level complexity changes using AST-derived data.
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
     * @return Enriched file changes with method-level details
     */
    public List<FileChangeSummary> enrichWithMethodComplexity(
            List<FileChangeSummary> fileChanges) {

        List<FileChangeSummary> enriched = new ArrayList<>();

        for (FileChangeSummary fileChange : fileChanges) {
            try {
                FileChangeSummary enrichedFile = analyzeFile(
                        fileChange
                );
                enriched.add(enrichedFile);
            } catch (Exception e) {
                enriched.add(fileChange);
            }
        }

        return enriched;
    }

    /**
     * Analyze a single file for method-level changes.
     */
    private FileChangeSummary analyzeFile(
            FileChangeSummary fileChange) {

        String filename = fileChange.getFilename();

        // Parse file in base and head
        Map<String, FlowNode> baseMethods = parseFileIfExists( filename);
        Map<String, FlowNode> headMethods = parseFileIfExists( filename);

        // Compute method-level changes
        List<MethodChange> methodChanges = computeMethodChanges(baseMethods, headMethods);

        // Compute complexity metrics
        int totalComplexityBefore = baseMethods.values().stream()
                                            .mapToInt(FlowNode::getCyclomaticComplexity)
                                            .sum();

        int totalComplexityAfter = headMethods.values().stream()
                                           .mapToInt(FlowNode::getCyclomaticComplexity)
                                           .sum();

        int complexityDelta = totalComplexityAfter - totalComplexityBefore;

        // Update file change summary
        fileChange.setMethodChanges(methodChanges);
        fileChange.setTotalComplexityBefore(totalComplexityBefore);
        fileChange.setTotalComplexityAfter(totalComplexityAfter);
        fileChange.setComplexityDelta(complexityDelta);

        // Generate explanation
        fileChange.setReason(generateComplexityExplanation(methodChanges, complexityDelta));

        return fileChange;
    }

    /**
     * Parse a single file and extract methods.
     *
     * Returns map of method ID -> FlowNode
     */
    private Map<String, FlowNode> parseFileIfExists( String filename) {

        try {
            // Parse single file
//            ASTParserService.ParsedCallGraph graph = astParser.parseDirectory(filename);
//
//            // Filter to only methods from this file
//            return graph.nodes.entrySet().stream()
//                           .filter(entry -> entry.getValue().getFilePath().equals(filename))
//                           .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            return null;

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

            MethodChange change = createMethodChange(methodId, baseMethod, headMethod);
            changes.add(change);
        }

        // Sort by complexity delta (descending)
        changes.sort((a, b) -> Integer.compare(
                Math.abs(b.getComplexityDelta()),
                Math.abs(a.getComplexityDelta())
        ));

        return changes;
    }

    /**
     * Create MethodChange from base and head FlowNodes.
     */
    private MethodChange createMethodChange(
            String methodId,
            FlowNode baseMethod,
            FlowNode headMethod) {

        MethodChange.MethodChangeType changeType;
        int complexityBefore = 0;
        int complexityAfter = 0;
        String methodName;
        String methodSignature;
        int startLine = 0;
        int endLine = 0;
        String returnType = "";
        Set<String> annotations = Collections.emptySet();

        if (baseMethod == null && headMethod != null) {
            // ADDED
            changeType = MethodChange.MethodChangeType.ADDED;
            complexityAfter = headMethod.getCyclomaticComplexity();
            methodName = headMethod.getLabel();
            methodSignature = extractSignature(methodId);
            startLine = headMethod.getStartLine();
            endLine = headMethod.getEndLine();
            returnType = headMethod.getReturnType();
            annotations = headMethod.getAnnotations();

        } else if (baseMethod != null && headMethod == null) {
            // DELETED
            changeType = MethodChange.MethodChangeType.DELETED;
            complexityBefore = baseMethod.getCyclomaticComplexity();
            methodName = baseMethod.getLabel();
            methodSignature = extractSignature(methodId);
            startLine = baseMethod.getStartLine();
            endLine = baseMethod.getEndLine();
            returnType = baseMethod.getReturnType();
            annotations = baseMethod.getAnnotations();

        } else if (baseMethod != null && headMethod != null) {
            // MODIFIED or UNCHANGED
            complexityBefore = baseMethod.getCyclomaticComplexity();
            complexityAfter = headMethod.getCyclomaticComplexity();
            methodName = headMethod.getLabel();
            methodSignature = extractSignature(methodId);
            startLine = headMethod.getStartLine();
            endLine = headMethod.getEndLine();
            returnType = headMethod.getReturnType();
            annotations = headMethod.getAnnotations();

            boolean complexityChanged = complexityBefore != complexityAfter;
            boolean annotationsChanged = !Objects.equals(baseMethod.getAnnotations(), headMethod.getAnnotations());
            boolean locChanged = (baseMethod.getEndLine() - baseMethod.getStartLine()) !=
                                         (headMethod.getEndLine() - headMethod.getStartLine());

            if (complexityChanged || annotationsChanged || locChanged) {
                changeType = MethodChange.MethodChangeType.MODIFIED;
            } else {
                changeType = MethodChange.MethodChangeType.UNCHANGED;
            }
        } else {
            throw new IllegalStateException("Both base and head methods are null");
        }

        int complexityDelta = complexityAfter - complexityBefore;
        int linesChanged = endLine - startLine + 1;
        String description = generateChangeDescription(changeType, complexityBefore, complexityAfter);

        return MethodChange.builder()
                       .methodName(methodName)
                       .methodSignature(methodSignature)
                       .changeType(changeType)
                       .complexityBefore(complexityBefore)
                       .complexityAfter(complexityAfter)
                       .complexityDelta(complexityDelta)
                       .startLine(startLine)
                       .endLine(endLine)
                       .linesChanged(linesChanged)
                       .returnType(returnType)
                       .annotations(annotations)
                       .changeDescription(description)
                       .build();
    }

    /**
     * Extract method signature from method ID.
     *
     * Example: "com.example.Service.processData" -> "processData(...)"
     */
    private String extractSignature(String methodId) {
        String[] parts = methodId.split("\\.");
        return parts[parts.length - 1] + "(...)";
    }

    /**
     * Generate human-readable change description.
     */
    private String generateChangeDescription(
            MethodChange.MethodChangeType changeType,
            int complexityBefore,
            int complexityAfter) {

        switch (changeType) {
            case ADDED:
                return String.format("New method with complexity %d", complexityAfter);
            case DELETED:
                return String.format("Method removed (had complexity %d)", complexityBefore);
            case MODIFIED:
                int delta = complexityAfter - complexityBefore;
                if (delta > 0) {
                    return String.format("Complexity increased from %d to %d (+%d)",
                            complexityBefore, complexityAfter, delta);
                } else if (delta < 0) {
                    return String.format("Complexity decreased from %d to %d (%d)",
                            complexityBefore, complexityAfter, delta);
                } else {
                    return "Method modified but complexity unchanged";
                }
            case UNCHANGED:
                return String.format("No changes (complexity: %d)", complexityAfter);
            default:
                return "Unknown change type";
        }
    }

    /**
     * Generate file-level complexity explanation.
     */
    private String generateComplexityExplanation(
            List<MethodChange> methodChanges,
            int complexityDelta) {

        long addedCount = methodChanges.stream()
                                  .filter(m -> m.getChangeType() == MethodChange.MethodChangeType.ADDED)
                                  .count();

        long deletedCount = methodChanges.stream()
                                    .filter(m -> m.getChangeType() == MethodChange.MethodChangeType.DELETED)
                                    .count();

        long modifiedCount = methodChanges.stream()
                                     .filter(m -> m.getChangeType() == MethodChange.MethodChangeType.MODIFIED)
                                     .count();

        StringBuilder explanation = new StringBuilder();

        if (complexityDelta > 0) {
            explanation.append(String.format("Complexity increased by %d", complexityDelta));
        } else if (complexityDelta < 0) {
            explanation.append(String.format("Complexity decreased by %d", Math.abs(complexityDelta)));
        } else {
            explanation.append("No net complexity change");
        }

        if (addedCount > 0 || deletedCount > 0 || modifiedCount > 0) {
            explanation.append(" (");
            List<String> parts = new ArrayList<>();
            if (addedCount > 0) parts.add(addedCount + " added");
            if (deletedCount > 0) parts.add(deletedCount + " deleted");
            if (modifiedCount > 0) parts.add(modifiedCount + " modified");
            explanation.append(String.join(", ", parts));
            explanation.append(" methods)");
        }

        return explanation.toString();
    }
}
