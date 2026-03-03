package io.contextguard.analysis.flow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MERMAID RENDERER SERVICE
 *
 * Primary entry point for diagram generation.
 *
 * STRATEGY (matches CodeRabbit's approach):
 *
 * 1. DEFAULT → SequenceDiagramRenderer
 *    Generates a `sequenceDiagram` showing runtime execution flow:
 *    who calls what, in what order, with return values and alt blocks.
 *    This is what reviewers need to understand behavioral changes.
 *
 * 2. FALLBACK → compactFlowGraph()
 *    Used when the call graph has no edges (only node-level changes,
 *    e.g. internal refactor with no new call chains). Renders a
 *    compact `graph LR` showing changed nodes grouped by layer.
 *
 * DESIGN CHANGE (2025-03):
 *   Previous renderer generated `graph TB` flowcharts showing class
 *   hierarchy. Replaced with `sequenceDiagram` that shows temporal
 *   interaction — the same approach as CodeRabbit, which generates
 *   sequence diagrams "for code changes, providing reviewers with a
 *   clear visualization of the control flow."
 */
@Service
public class MermaidRendererService {

    private static final Logger log = LoggerFactory.getLogger(MermaidRendererService.class);

    private final SequenceDiagramRenderer sequenceRenderer = new SequenceDiagramRenderer();

    public String renderMermaid(CallGraphDiff diff) {
        if (diff == null) return emptyDiagram();

        try {
            boolean hasEdges = hasAnyEdges(diff);

            if (hasEdges) {
                // Primary path: proper sequenceDiagram
                return sequenceRenderer.render(diff);
            } else {
                // Fallback: node-only graph for pure refactors
                return compactFlowGraph(diff);
            }

        } catch (Exception e) {
            log.error("renderMermaid failed: {}", e.getMessage(), e);
            return emptyDiagram();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // FALLBACK RENDERER — used when no edges exist (internal refactors)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Compact flowchart for PRs that modify method bodies without
     * introducing new call chains (pure refactors, complexity changes,
     * annotation updates, etc.).
     *
     * Groups changed nodes by their functional layer (Controller / Service
     * / Repository) so reviewers can see what components were touched.
     */
    private String compactFlowGraph(CallGraphDiff diff) {
        StringBuilder sb = new StringBuilder();

        sb.append("---\n");
        sb.append("config:\n");
        sb.append("  theme: base\n");
        sb.append("  themeVariables:\n");
        sb.append("    primaryColor: \"#EFF6FF\"\n");
        sb.append("    primaryTextColor: \"#1E3A5F\"\n");
        sb.append("    primaryBorderColor: \"#2E86AB\"\n");
        sb.append("---\n");
        sb.append("graph LR\n\n");

        List<FlowNode> allChanged = new ArrayList<>();
        if (diff.getNodesAdded()    != null) allChanged.addAll(diff.getNodesAdded());
        if (diff.getNodesModified() != null) allChanged.addAll(diff.getNodesModified());

        if (allChanged.isEmpty()) return emptyDiagram();

        // Group by layer
        Map<String, List<FlowNode>> byLayer = new LinkedHashMap<>();
        for (FlowNode node : allChanged) {
            String layer = detectLayerLabel(node);
            byLayer.computeIfAbsent(layer, k -> new ArrayList<>()).add(node);
        }

        // Emit subgraphs per layer
        for (Map.Entry<String, List<FlowNode>> entry : byLayer.entrySet()) {
            String sgId = "sg_" + entry.getKey().replaceAll("[^a-zA-Z0-9]", "_");
            sb.append("    subgraph ").append(sgId)
                    .append("[\"").append(entry.getKey()).append("\"]\n");

            for (FlowNode node : entry.getValue().stream().limit(8).collect(Collectors.toList())) {
                String id    = sanitize(node.getId());
                String label = statusIcon(node.getStatus()) + " " + truncate(node.getLabel(), 30);
                String shape = node.getStatus() == FlowNode.NodeStatus.ADDED ? "([\"%s\"])" : "[\"%s\"]";
                sb.append("        ").append(id)
                        .append(String.format(shape, label))
                        .append(":::").append(node.getStatus().name().toLowerCase())
                        .append("\n");
            }
            sb.append("    end\n\n");
        }

        // Styling
        sb.append("    classDef added    fill:#d4f4dd,stroke:#22c55e,stroke-width:2px\n");
        sb.append("    classDef modified fill:#fed7aa,stroke:#f97316,stroke-width:2px\n");
        sb.append("    classDef removed  fill:#fecaca,stroke:#ef4444,stroke-width:2px\n");
        sb.append("    classDef unchanged fill:#f1f5f9,stroke:#94a3b8,stroke-width:1px\n");

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private boolean hasAnyEdges(CallGraphDiff diff) {
        return (diff.getEdgesAdded()    != null && !diff.getEdgesAdded().isEmpty()) ||
                       (diff.getEdgesModified() != null && !diff.getEdgesModified().isEmpty());
    }

    private String detectLayerLabel(FlowNode node) {
        String id   = node.getId() != null   ? node.getId().toLowerCase()   : "";
        String file = node.getFilePath() != null ? node.getFilePath().toLowerCase() : "";

        if (id.contains("controller") || file.contains("/controller/") || file.contains("/api/"))
            return "🌐 API / Controller";
        if (id.contains("repository") || id.contains("dao") || file.contains("/repository/"))
            return "💾 Repository";
        if (id.contains("service") || file.contains("/service/"))
            return "⚙️ Service";
        return "📦 Core";
    }

    private String statusIcon(FlowNode.NodeStatus status) {
        switch (status) {
            case ADDED:    return "➕";
            case MODIFIED: return "🔄";
            case REMOVED:  return "❌";
            default:       return "•";
        }
    }

    private String sanitize(String id) {
        return id != null ? id.replaceAll("[^a-zA-Z0-9_]", "_") : "node";
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 2) + ".." : s;
    }

    private String emptyDiagram() {
        return "---\nconfig:\n  theme: base\n---\n" +
                       "sequenceDiagram\n" +
                       "  Note over System: No structural changes detected in this PR\n";
    }
}