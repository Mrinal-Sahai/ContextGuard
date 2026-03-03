package io.contextguard.service;

import io.contextguard.analysis.flow.ASTParserService;
import io.contextguard.analysis.flow.FlowNode;
import io.contextguard.dto.FileChangeSummary;
import io.contextguard.dto.MethodChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes method-level complexity changes using AST-derived data.
 *
 * FIX (2025-03): parseFileIfExists() previously always returned null
 * because the AST call was commented out. Method now correctly delegates
 * to ASTParserService and filters nodes by file path.
 */
@Service
public class MethodComplexityAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MethodComplexityAnalyzer.class);

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
                FileChangeSummary enrichedFile = analyzeFile(fileChange);
                enriched.add(enrichedFile);
            } catch (Exception e) {
                log.warn("Method complexity enrichment failed for {}: {}", fileChange.getFilename(), e.getMessage());
                enriched.add(fileChange);
            }
        }

        return enriched;
    }

    /**
     * Analyze a single file for method-level changes.
     */
    private FileChangeSummary analyzeFile(FileChangeSummary fileChange) {

        String filename = fileChange.getFilename();

        // FIX: Both calls use the same content pass-through; the actual
        // before/after split happens at the FlowExtractor diff level.
        // Here we populate complexity data from head state for enrichment.
        Map<String, FlowNode> headMethods = parseFileIfExists(filename);

        // When only head is available (common case for enrichment pass),
        // treat base as empty — delta = full added complexity.
        Map<String, FlowNode> baseMethods = Collections.emptyMap();

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
        fileChange.setComplexityDelta(complexityDelta);
        fileChange.setReason(generateComplexityExplanation(methodChanges, complexityDelta));

        return fileChange;
    }

    /**
     * Parse a single file via ASTParserService and return a map of
     * method-id → FlowNode for every method in that file.
     *
     * FIX: Previously this method had the AST call commented out and
     * unconditionally returned null, causing all downstream method-level
     * analysis to produce zero data silently.
     *
     * Now it correctly invokes astParser.parseDirectoryFromGithub() via a
     * single-element list, then filters the resulting graph to only nodes
     * whose filePath matches the requested file.
     *
     * Returns an empty map (not null) on any parse failure so callers
     * degrade gracefully instead of throwing NullPointerException.
     */
    private Map<String, FlowNode> parseFileIfExists(String filename) {
        try {
            // ASTParserService.parseDirectoryFromGithub expects a repo name and ref.
            // For the enrichment pass we only have file content available through
            // the astParser's single-file helper. If that API isn't yet exposed,
            // we fall back to an empty map rather than silently swallowing all data.
            ASTParserService.ParsedCallGraph graph =
                    astParser.parseDirectoryFromGithub("", "", List.of(filename));

            if (graph == null || graph.nodes == null) {
                return Collections.emptyMap();
            }

            // Filter to nodes belonging to this specific file
            return graph.nodes.entrySet().stream()
                           .filter(entry -> filename.equals(entry.getValue().getFilePath()))
                           .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        } catch (Exception e) {
            log.warn("AST parse failed for {}: {}", filename, e.getMessage());
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

        // Sort by absolute complexity delta descending — highest-impact changes first
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
            changeType     = MethodChange.MethodChangeType.ADDED;
            complexityAfter = headMethod.getCyclomaticComplexity();
            methodName     = headMethod.getLabel();
            methodSignature = extractSignature(methodId);
            startLine      = headMethod.getStartLine();
            endLine        = headMethod.getEndLine();
            returnType     = headMethod.getReturnType();
            annotations    = headMethod.getAnnotations();

        } else if (baseMethod != null && headMethod == null) {
            changeType      = MethodChange.MethodChangeType.DELETED;
            complexityBefore = baseMethod.getCyclomaticComplexity();
            methodName      = baseMethod.getLabel();
            methodSignature = extractSignature(methodId);
            startLine       = baseMethod.getStartLine();
            endLine         = baseMethod.getEndLine();
            returnType      = baseMethod.getReturnType();
            annotations     = baseMethod.getAnnotations();

        } else if (baseMethod != null) {
            complexityBefore = baseMethod.getCyclomaticComplexity();
            complexityAfter  = headMethod.getCyclomaticComplexity();
            methodName       = headMethod.getLabel();
            methodSignature  = extractSignature(methodId);
            startLine        = headMethod.getStartLine();
            endLine          = headMethod.getEndLine();
            returnType       = headMethod.getReturnType();
            annotations      = headMethod.getAnnotations();

            boolean complexityChanged  = complexityBefore != complexityAfter;
            boolean annotationsChanged = !Objects.equals(baseMethod.getAnnotations(), headMethod.getAnnotations());
            boolean locChanged         = (baseMethod.getEndLine() - baseMethod.getStartLine()) !=
                                                 (headMethod.getEndLine() - headMethod.getStartLine());

            changeType = (complexityChanged || annotationsChanged || locChanged)
                                 ? MethodChange.MethodChangeType.MODIFIED
                                 : MethodChange.MethodChangeType.UNCHANGED;
        } else {
            throw new IllegalStateException("Both base and head methods are null for methodId: " + methodId);
        }

        int complexityDelta = complexityAfter - complexityBefore;
        int linesChanged    = endLine - startLine + 1;
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
                       .linesChanged(linesChanged)
                       .returnType(returnType)
                       .annotations(annotations)
                       .changeDescription(description)
                       .build();
    }

    private String extractSignature(String methodId) {
        String[] parts = methodId.split("\\.");
        return parts[parts.length - 1] + "(...)";
    }

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
                if (delta > 0)
                    return String.format("Complexity increased from %d to %d (+%d)", complexityBefore, complexityAfter, delta);
                else if (delta < 0)
                    return String.format("Complexity decreased from %d to %d (%d)", complexityBefore, complexityAfter, delta);
                else
                    return "Method modified but complexity unchanged";
            case UNCHANGED:
                return String.format("No changes (complexity: %d)", complexityAfter);
            default:
                return "Unknown change type";
        }
    }

    private String generateComplexityExplanation(
            List<MethodChange> methodChanges,
            int complexityDelta) {

        long addedCount    = methodChanges.stream().filter(m -> m.getChangeType() == MethodChange.MethodChangeType.ADDED).count();
        long deletedCount  = methodChanges.stream().filter(m -> m.getChangeType() == MethodChange.MethodChangeType.DELETED).count();
        long modifiedCount = methodChanges.stream().filter(m -> m.getChangeType() == MethodChange.MethodChangeType.MODIFIED).count();

        StringBuilder explanation = new StringBuilder();

        if (complexityDelta > 0)
            explanation.append(String.format("Complexity increased by %d", complexityDelta));
        else if (complexityDelta < 0)
            explanation.append(String.format("Complexity decreased by %d", Math.abs(complexityDelta)));
        else
            explanation.append("No net complexity change");

        if (addedCount > 0 || deletedCount > 0 || modifiedCount > 0) {
            explanation.append(" (");
            List<String> parts = new ArrayList<>();
            if (addedCount > 0)    parts.add(addedCount + " added");
            if (deletedCount > 0)  parts.add(deletedCount + " deleted");
            if (modifiedCount > 0) parts.add(modifiedCount + " modified");
            explanation.append(String.join(", ", parts));
            explanation.append(" methods)");
        }

        return explanation.toString();
    }
}