package io.contextguard.analysis.flow;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Enhanced Mermaid renderer with subgraph support and detailed styling.
 */
@Service
public class MermaidRendererService {

    private static final int MAX_NODES_DISPLAY = 50;
    private static final int MAX_LABEL_LENGTH = 30;

    /**
     * Render CallGraphDiff as interactive Mermaid diagram.
     *
     * FEATURES:
     * - Subgraphs for added/modified/removed nodes
     * - Color-coded nodes and edges
     * - Truncated labels for readability
     * - Tooltip-ready IDs
     */
    public String renderMermaid(CallGraphDiff diff) {

        if (isEmptyGraph(diff)) {
            return null;
        }

        StringBuilder mermaid = new StringBuilder();
        mermaid.append("%%{init: {'theme':'base', 'themeVariables': { 'fontSize':'14px'}}}%%\n");
        mermaid.append("graph TB\n");

        // Group nodes by package/module for better layout
        Map<String, List<FlowNode>> nodesByPackage = groupByPackage(diff);

        // Render subgraphs for each package
        for (Map.Entry<String, List<FlowNode>> entry : nodesByPackage.entrySet()) {
            renderSubgraph(mermaid, entry.getKey(), entry.getValue());
        }

        // Render edges
        renderEdges(mermaid, diff.getEdgesAdded(), "ADDED");
        renderEdges(mermaid, diff.getEdgesModified(), "MODIFIED");
        renderEdges(mermaid, diff.getEdgesUnchanged(), "");

        // Add comprehensive styling
        addStyling(mermaid);

        return mermaid.toString();
    }

    /**
     * Group nodes by package/module.
     */
    private Map<String, List<FlowNode>> groupByPackage(CallGraphDiff diff) {

        Map<String, List<FlowNode>> grouped = new LinkedHashMap<>();

        List<FlowNode> allNodes = new ArrayList<>();
        allNodes.addAll(diff.getNodesAdded());
        allNodes.addAll(diff.getNodesModified());
        allNodes.addAll(diff.getNodesUnchanged());

        // Limit to top N nodes if too many
        if (allNodes.size() > MAX_NODES_DISPLAY) {
            allNodes = allNodes.stream()
                               .sorted((a, b) -> Double.compare(b.getCentrality(), a.getCentrality()))
                               .limit(MAX_NODES_DISPLAY)
                               .collect(Collectors.toList());
        }

        for (FlowNode node : allNodes) {
            String pkg = extractPackage(node.getId());
            grouped.computeIfAbsent(pkg, k -> new ArrayList<>()).add(node);
        }

        return grouped;
    }

    /**
     * Render subgraph for a package.
     */
    private void renderSubgraph(StringBuilder sb, String packageName, List<FlowNode> nodes) {

        String subgraphId = "sg_" + packageName.replaceAll("[^a-zA-Z0-9]", "_");

        sb.append("    subgraph ").append(subgraphId)
                .append("[\"📦 ").append(packageName).append("\"]\n");

        for (FlowNode node : nodes) {
            String nodeId = sanitizeId(node.getId());
            String label = truncateLabel(node.getLabel());

            sb.append("        ").append(nodeId);

            // Shape based on status
            switch (node.getStatus()) {
                case ADDED:
                    sb.append("[\"➕ ").append(label).append("\"]");
                    break;
                case REMOVED:
                    sb.append("[\"❌ ").append(label).append("\"]");
                    break;
                case MODIFIED:
                    sb.append("((\"🔄 ").append(label).append("\"))");
                    break;
                default:
                    sb.append("[\"").append(label).append("\"]");
            }

            // Apply class
            sb.append(":::").append(node.getStatus().name().toLowerCase());

            // Add complexity annotation
            if (node.getCyclomaticComplexity() > 10) {
                sb.append(":::highcomplexity");
            }

            sb.append("\n");
        }

        sb.append("    end\n");
    }

    /**
     * Render edges with labels.
     */
    private void renderEdges(StringBuilder sb, List<FlowEdge> edges, String label) {

        for (FlowEdge edge : edges) {
            String from = sanitizeId(edge.getFrom());
            String to = sanitizeId(edge.getTo());

            sb.append("    ").append(from);

            if ("ADDED".equals(label)) {
                sb.append(" ==>|➕ ").append(edge.getEdgeType()).append("| ");
            } else if ("MODIFIED".equals(label)) {
                sb.append(" -.->|🔄| ");
            } else {
                sb.append(" --> ");
            }

            sb.append(to).append("\n");
        }
    }

    /**
     * Add comprehensive styling.
     */
    private void addStyling(StringBuilder sb) {
        sb.append("\n");
        sb.append("    classDef added fill:#dcfce7,stroke:#22c55e,stroke-width:3px,color:#15803d\n");
        sb.append("    classDef removed fill:#fecaca,stroke:#ef4444,stroke-width:3px,color:#991b1b\n");
        sb.append("    classDef modified fill:#fef3c7,stroke:#eab308,stroke-width:3px,color:#854d0e\n");
        sb.append("    classDef unchanged fill:#f8fafc,stroke:#cbd5e1,stroke-width:2px,color:#475569\n");
        sb.append("    classDef highcomplexity stroke:#dc2626,stroke-width:4px\n");
    }

    /**
     * Extract package name from fully qualified ID.
     */
    private String extractPackage(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot > 0) {
            String pkg = fqn.substring(0, lastDot);
            int secondLastDot = pkg.lastIndexOf('.');
            return secondLastDot > 0 ? pkg.substring(secondLastDot + 1) : pkg;
        }
        return "default";
    }

    /**
     * Sanitize ID for Mermaid (no dots, spaces).
     */
    private String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * Truncate label to max length.
     */
    private String truncateLabel(String label) {
        if (label.length() > MAX_LABEL_LENGTH) {
            return label.substring(0, MAX_LABEL_LENGTH - 3) + "...";
        }
        return label;
    }

    private boolean isEmptyGraph(CallGraphDiff diff) {
        return diff.getNodesAdded().isEmpty() &&
                       diff.getNodesRemoved().isEmpty() &&
                       diff.getNodesModified().isEmpty() &&
                       diff.getNodesUnchanged().isEmpty();
    }
}
