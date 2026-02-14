package io.contextguard.analysis.flow;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REVIEWER-FOCUSED Mermaid Renderer
 *
 * DESIGN PRINCIPLES:
 * 1. Show ONLY production code changes (filter out test-to-test calls)
 * 2. Highlight critical paths (public APIs, service layers)
 * 3. Group by business domain, not package
 * 4. Show call IMPACT (who calls what changed)
 * 5. Limit to ~20 most relevant nodes
 *
 * WHAT REVIEWERS NEED:
 * - "What production code changed?"
 * - "What calls the changed code?" (blast radius)
 * - "Are public APIs affected?"
 * - "What's the call chain complexity?"
 */
@Service
public class MermaidRendererService {

    private static final int MAX_NODES_DISPLAY = 20;
    private static final int MAX_LABEL_LENGTH = 40;
    private static final double  MIN_CENTRALITY_THRESHOLD = 0.1; // Filter low-impact nodes

    public String renderMermaid(CallGraphDiff diff) {

        try {
            if (isEmptyGraph(diff)) {
                return null;
            }

            // STEP 1: Filter to reviewer-relevant nodes
            ReviewerRelevantGraph filtered = filterToReviewerRelevantNodes(diff);

            if (filtered.nodes.isEmpty()) {
                return renderFallbackMessage(diff);
            }

            StringBuilder mermaid = new StringBuilder();
            mermaid.append("%%{init: {'theme':'base', 'themeVariables': {'fontSize':'13px','fontFamily':'Arial'}}}%%\n");
            mermaid.append("graph TB\n");

            // STEP 2: Group by functional area (not package)
            Map<String, List<FlowNode>> nodesByArea = groupByFunctionalArea(filtered.nodes);

            // STEP 3: Render focused subgraphs
            for (Map.Entry<String, List<FlowNode>> entry : nodesByArea.entrySet()) {
                renderFocusedSubgraph(mermaid, entry.getKey(), entry.getValue());
            }

            // STEP 4: Render only critical edges (changed or high-impact)
            renderReviewerFocusedEdges(mermaid, filtered.edges, filtered.nodes);

            // STEP 5: Add styling with emphasis on changes
            addReviewerFocusedStyling(mermaid);

            // STEP 6: Add summary note
            addSummaryNote(mermaid, diff, filtered);
            return mermaid.toString();
        }
        catch (Exception e) {
            return renderFallbackMessage(diff);
        }
    }

    /**
     * Filter to nodes that reviewers actually care about.
     */
    private ReviewerRelevantGraph filterToReviewerRelevantNodes(CallGraphDiff diff) {
        List<FlowNode> allNodes = new ArrayList<>();
        allNodes.addAll(diff.getNodesAdded());
        allNodes.addAll(diff.getNodesModified());

        // FILTER 1: Remove pure test nodes (test-to-test calls)
        List<FlowNode> productionNodes = allNodes.stream()
                                                 .filter(node -> !isTestNode(node))
                                                 .collect(Collectors.toList());

        // FILTER 2: Add unchanged nodes ONLY if they're called by changed code (blast radius)
        Set<String> relevantUnchangedIds = findRelevantUnchangedNodes(diff, productionNodes);
        List<FlowNode> relevantUnchanged = diff.getNodesUnchanged().stream()
                                                   .filter(node -> relevantUnchangedIds.contains(node.getId()))
                                                   .filter(node -> !isTestNode(node))
                                                   .collect(Collectors.toList());

        productionNodes.addAll(relevantUnchanged);

        // FILTER 3: Prioritize by reviewer importance score
        List<FlowNode> prioritized = productionNodes.stream()
                                             .sorted(Comparator.comparingDouble(this::calculateReviewerImportance).reversed())
                                             .limit(MAX_NODES_DISPLAY)
                                             .collect(Collectors.toList());

        // Get relevant edges
        Set<String> nodeIds = prioritized.stream()
                                      .map(FlowNode::getId)
                                      .collect(Collectors.toSet());

        List<FlowEdge> allEdges = new ArrayList<>();
        allEdges.addAll(Optional.ofNullable(diff.getEdgesAdded()).orElse(Collections.emptyList()));
        allEdges.addAll(Optional.ofNullable(diff.getEdgesModified())
                        .orElse(Collections.emptyList()));

        allEdges.addAll(Optional.ofNullable(diff.getEdgesRemoved()).orElse(Collections.emptyList()));

        List<FlowEdge> relevantEdges = allEdges.stream()
                                               .filter(edge -> nodeIds.contains(edge.getFrom()) && nodeIds.contains(edge.getTo()))
                                               .collect(Collectors.toList());

        return new ReviewerRelevantGraph(prioritized, relevantEdges);
    }

    /**
     * Calculate how important a node is for review.
     */
    private double calculateReviewerImportance(FlowNode node) {
        double score = 0.0;

        // 1. Changed nodes are most important
        if (node.getStatus() == FlowNode.NodeStatus.ADDED) score += 10.0;
        if (node.getStatus() == FlowNode.NodeStatus.MODIFIED) score += 8.0;

        // 2. Public API methods critical
        if (isPublicAPI(node)) score += 5.0;

        // 3. High complexity deserves attention
        if (node.getCyclomaticComplexity() > 10) score += 3.0;
        else if (node.getCyclomaticComplexity() > 5) score += 1.0;

        // 4. High centrality = many callers = important
        score += node.getCentrality() * 10.0;

        // 5. Service/Controller layer more important than utilities
        if (isServiceOrControllerLayer(node)) score += 2.0;

        // 6. Changed nodes with many callers = blast radius risk
        if (node.getStatus() != FlowNode.NodeStatus.UNCHANGED && node.getInDegree() > 3) {
            score += node.getInDegree() * 0.5;
        }

        return score;
    }

    /**
     * Group nodes by business function, not package.
     */
    private Map<String, List<FlowNode>> groupByFunctionalArea(List<FlowNode> nodes) {
        Map<String, List<FlowNode>> grouped = new LinkedHashMap<>();

        for (FlowNode node : nodes) {
            String area = detectFunctionalArea(node);
            grouped.computeIfAbsent(area, k -> new ArrayList<>()).add(node);
        }

        // Sort groups: changed code first
        return grouped.entrySet().stream()
                       .sorted((a, b) -> {
                           long aChanged = a.getValue().stream()
                                                   .filter(n -> n.getStatus() != FlowNode.NodeStatus.UNCHANGED)
                                                   .count();
                           long bChanged = b.getValue().stream()
                                                   .filter(n -> n.getStatus() != FlowNode.NodeStatus.UNCHANGED)
                                                   .count();
                           return Long.compare(bChanged, aChanged);
                       })
                       .collect(Collectors.toMap(
                               Map.Entry::getKey,
                               Map.Entry::getValue,
                               (a, b) -> a,
                               LinkedHashMap::new
                       ));
    }

    /**
     * Detect functional area from node metadata.
     */
    private String detectFunctionalArea(FlowNode node) {
        String id = node.getId().toLowerCase();
        String file = node.getFilePath() != null ? node.getFilePath().toLowerCase() : "";

        if (id.contains("controller") || id.contains("endpoint") || file.contains("/api/")) {
            return "🌐 API Layer";
        }
        if (id.contains("service") || file.contains("/service/")) {
            return "⚙️ Business Logic";
        }
        if (id.contains("repository") || id.contains("dao") || file.contains("/repository/")) {
            return "💾 Data Access";
        }
        if (id.contains("auth") || id.contains("security")) {
            return "🔐 Authentication";
        }
        if (id.contains("config") || file.contains("/config/")) {
            return "⚙️ Configuration";
        }
        if (id.contains("util") || id.contains("helper") || file.contains("/util/")) {
            return "🔧 Utilities";
        }
        if (id.contains("model") || id.contains("entity") || id.contains("dto")) {
            return "📦 Data Models";
        }

        // Default: extract top-level package
        String pkg = extractTopLevelPackage(node.getId());
        return "📁 " + (pkg.isEmpty() ? "Core" : pkg);
    }

    /**
     * Render subgraph with reviewer-friendly formatting.
     */
    private void renderFocusedSubgraph(StringBuilder sb, String areaName, List<FlowNode> nodes) {
        String subgraphId = "sg_" + areaName.replaceAll("[^a-zA-Z0-9]", "_");

        sb.append("    subgraph ").append(subgraphId)
                .append("[\"").append(areaName).append("\"]\n");

        // Sort: changed nodes first, then by complexity
        nodes.sort(Comparator
                           .comparing((FlowNode n) -> n.getStatus() == FlowNode.NodeStatus.UNCHANGED)
                           .thenComparing(n -> -n.getCyclomaticComplexity())
        );

        for (FlowNode node : nodes) {
            renderNode(sb, node);
        }

        sb.append("    end\n");
    }

    /**
     * Render individual node with rich information.
     */
    private void renderNode(StringBuilder sb, FlowNode node) {
        String nodeId = sanitizeId(node.getId());
        String label = createReviewerFriendlyLabel(node);

        sb.append("        ").append(nodeId);

        // Shape based on type and status
        if (node.getStatus() == FlowNode.NodeStatus.ADDED) {
            sb.append("([\"➕ ").append(label).append("\"])");
        } else if (node.getStatus() == FlowNode.NodeStatus.REMOVED) {
            sb.append("([\"❌ ").append(label).append("\"])");
        } else if (node.getStatus() == FlowNode.NodeStatus.MODIFIED) {
            // Hexagon for modified
            sb.append("{{\"🔄 ").append(label).append("\"}}");
        } else if (isPublicAPI(node)) {
            // Rectangle with double border for public APIs
            sb.append("[[\"").append(label).append("\"]]");
        } else {
            sb.append("[\"").append(label).append("\"]");
        }

        // Apply styling
        sb.append(":::").append(node.getStatus().name().toLowerCase());

        // High complexity warning
        if (node.getCyclomaticComplexity() > 15) {
            sb.append(":::critical_complexity");
        } else if (node.getCyclomaticComplexity() > 10) {
            sb.append(":::high_complexity");
        }

        // Public API emphasis
        if (isPublicAPI(node)) {
            sb.append(":::public_api");
        }

        sb.append("\n");
    }

    /**
     * Create label that helps reviewers understand what the method does.
     */
    private String createReviewerFriendlyLabel(FlowNode node) {
        StringBuilder label = new StringBuilder();

        // Method name (simplified)
        String methodName = node.getLabel();
        if (methodName.length() > MAX_LABEL_LENGTH) {
            methodName = methodName.substring(0, MAX_LABEL_LENGTH - 3) + "...";
        }

        label.append(methodName);

        // Add complexity indicator if significant
        if (node.getCyclomaticComplexity() > 5) {
            label.append(" [C:").append(node.getCyclomaticComplexity()).append("]");
        }

        // Add visibility indicator
        if (isPublicAPI(node)) {
            label.append(" 🔓");
        }

        // Add annotation hints
        if (node.getAnnotations() != null && !node.getAnnotations().isEmpty()) {
            String annotations = node.getAnnotations().stream()
                                         .filter(a -> a.contains("Transactional") || a.contains("Async") ||
                                                              a.contains("Cacheable") || a.contains("Scheduled"))
                                         .map(a -> a.replace("@", ""))
                                         .collect(Collectors.joining(","));
            if (!annotations.isEmpty()) {
                label.append(" @").append(truncate(annotations, 15));
            }
        }

        return label.toString();
    }

    /**
     * Render edges with focus on review-critical relationships.
     */
    private void renderReviewerFocusedEdges(StringBuilder sb, List<FlowEdge> edges, List<FlowNode> nodes) {
        Set<String> nodeIds = nodes.stream().map(FlowNode::getId).collect(Collectors.toSet());

        // Separate edges by importance
        List<FlowEdge> addedEdges = edges.stream()
                                            .filter(e -> e.getStatus() == FlowEdge.EdgeStatus.ADDED)
                                            .collect(Collectors.toList());

        List<FlowEdge> unchangedToChanged = edges.stream()
                                                    .filter(e -> e.getStatus() == FlowEdge.EdgeStatus.UNCHANGED)
                                                    .filter(e -> {
                                                        FlowNode fromNode = findNode(nodes, e.getFrom());
                                                        FlowNode toNode = findNode(nodes, e.getTo());
                                                        return (fromNode != null && fromNode.getStatus() == FlowNode.NodeStatus.UNCHANGED) &&
                                                                       (toNode != null && toNode.getStatus() != FlowNode.NodeStatus.UNCHANGED);
                                                    })
                                                    .collect(Collectors.toList());

        // Render added edges (new calls = new behavior)
        for (FlowEdge edge : addedEdges) {
            if (nodeIds.contains(edge.getFrom()) && nodeIds.contains(edge.getTo())) {
                sb.append("    ").append(sanitizeId(edge.getFrom()))
                        .append(" ==>|\"➕ NEW\"| ")
                        .append(sanitizeId(edge.getTo()))
                        .append("\n");
            }
        }

        // Render blast radius edges (unchanged code calling changed code)
        for (FlowEdge edge : unchangedToChanged) {
            if (nodeIds.contains(edge.getFrom()) && nodeIds.contains(edge.getTo())) {
                sb.append("    ").append(sanitizeId(edge.getFrom()))
                        .append(" -.->|\"⚠️ Impacted\"| ")
                        .append(sanitizeId(edge.getTo()))
                        .append("\n");
            }
        }

        // Render other edges (simplified)
        List<FlowEdge> otherEdges = edges.stream()
                                            .filter(e -> !addedEdges.contains(e) && !unchangedToChanged.contains(e))
                                            .limit(20) // Prevent clutter
                                            .collect(Collectors.toList());

        for (FlowEdge edge : otherEdges) {
            if (nodeIds.contains(edge.getFrom()) && nodeIds.contains(edge.getTo())) {
                sb.append("    ").append(sanitizeId(edge.getFrom()))
                        .append(" --> ")
                        .append(sanitizeId(edge.getTo()))
                        .append("\n");
            }
        }
    }

    /**
     * Add reviewer-focused styling.
     */
    private void addReviewerFocusedStyling(StringBuilder sb) {
        sb.append("\n");
        // Status styles
        sb.append("    classDef added fill:#d4f4dd,stroke:#22c55e,stroke-width:3px,color:#15803d\n");
        sb.append("    classDef removed fill:#fecaca,stroke:#ef4444,stroke-width:3px,color:#991b1b\n");
        sb.append("    classDef modified fill:#fed7aa,stroke:#f97316,stroke-width:3px,color:#9a3412\n");
        sb.append("    classDef unchanged fill:#f1f5f9,stroke:#94a3b8,stroke-width:1px,color:#475569\n");

        // Complexity styles
        sb.append("    classDef high_complexity stroke:#fb923c,stroke-width:3px\n");
        sb.append("    classDef critical_complexity stroke:#dc2626,stroke-width:4px,stroke-dasharray: 5 5\n");

        // Public API emphasis
        sb.append("    classDef public_api fill:#dbeafe,stroke:#3b82f6,stroke-width:2px\n");
    }

    /**
     * Add summary note for reviewers.
     */
    private void addSummaryNote(StringBuilder sb, CallGraphDiff diff, ReviewerRelevantGraph filtered) {
        sb.append("\n    Note[\"📋 Review Focus:\n");

        long addedCount = filtered.nodes.stream()
                                  .filter(n -> n.getStatus() == FlowNode.NodeStatus.ADDED).count();
        long modifiedCount = filtered.nodes.stream()
                                     .filter(n -> n.getStatus() == FlowNode.NodeStatus.MODIFIED).count();
        long publicAPIs = filtered.nodes.stream()
                                  .filter(this::isPublicAPI).count();

        sb.append(addedCount).append(" new methods\n");
        sb.append(modifiedCount).append(" modified methods\n");
        if (publicAPIs > 0) {
            sb.append(publicAPIs).append(" public APIs affected\n");
        }

        sb.append("Showing top ").append(filtered.nodes.size()).append(" critical changes");
        sb.append("\"]\n");
        sb.append("    style Note fill:#fef3c7,stroke:#f59e0b,stroke-width:2px\n");
    }

    /**
     * Fallback message when no relevant nodes found.
     */
    private String renderFallbackMessage(CallGraphDiff diff) {
        return "%%{init: {'theme':'base'}}%%\n" +
                       "graph TB\n" +
                       "    Note[\"ℹ️ No significant call graph changes detected.\n" +
                       "This PR contains " + (diff.getNodesAdded().size() + diff.getNodesModified().size()) +
                       " changes,\nbut they don't affect production code flow.\"]\n" +
                       "    style Note fill:#e0f2fe,stroke:#0ea5e9,stroke-width:2px\n";
    }

    // ============================================================================
    // HELPER METHODS
    // ============================================================================

    private boolean isTestNode(FlowNode node) {
        String file = node.getFilePath() != null ? node.getFilePath().toLowerCase() : "";
        String id = node.getId().toLowerCase();

        return file.contains("/test/") ||
                       file.contains("_test.") ||
                       id.contains("test") ||
                       file.endsWith("test.java") ||
                       file.endsWith("spec.js");
    }

    private boolean isPublicAPI(FlowNode node) {
        if (node.getAnnotations() == null) return false;

        return node.getAnnotations().stream()
                       .anyMatch(a -> a.contains("RestController") ||
                                              a.contains("Controller") ||
                                              a.contains("RequestMapping") ||
                                              a.contains("GetMapping") ||
                                              a.contains("PostMapping") ||
                                              a.contains("Public"));
    }

    private boolean isServiceOrControllerLayer(FlowNode node) {
        String id = node.getId().toLowerCase();
        String file = node.getFilePath() != null ? node.getFilePath().toLowerCase() : "";

        return id.contains("controller") ||
                       id.contains("service") ||
                       file.contains("/controller/") ||
                       file.contains("/service/");
    }

    private Set<String> findRelevantUnchangedNodes(CallGraphDiff diff, List<FlowNode> changedNodes) {
        Set<String> changedIds = changedNodes.stream()
                                         .map(FlowNode::getId)
                                         .collect(Collectors.toSet());

        Set<String> relevantUnchanged = new HashSet<>();

        // Find unchanged nodes that CALL changed nodes (blast radius)
        for (FlowEdge edge : diff.getEdgesUnchanged()) {
            if (changedIds.contains(edge.getTo())) {
                relevantUnchanged.add(edge.getFrom());
            }
        }

        // Limit to top 5 most connected
        return relevantUnchanged.stream()
                       .limit(5)
                       .collect(Collectors.toSet());
    }

    private FlowNode findNode(List<FlowNode> nodes, String id) {
        return nodes.stream()
                       .filter(n -> n.getId().equals(id))
                       .findFirst()
                       .orElse(null);
    }

    private String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String extractTopLevelPackage(String fqn) {
        String[] parts = fqn.split("\\.");
        return parts.length > 2 ? parts[parts.length - 2] : "";
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen - 2) + ".." : s;
    }

    private boolean isEmptyGraph(CallGraphDiff diff) {
        return diff.getNodesAdded().isEmpty() &&
                       diff.getNodesRemoved().isEmpty() &&
                       diff.getNodesModified().isEmpty();
    }

    private static class ReviewerRelevantGraph {
        final List<FlowNode> nodes;
        final List<FlowEdge> edges;

        ReviewerRelevantGraph(List<FlowNode> nodes, List<FlowEdge> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }
    }
}