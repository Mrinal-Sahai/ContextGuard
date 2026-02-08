package io.contextguard.analysis.flow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Enhanced call graph differential with metrics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallGraphDiff {

    private List<FlowNode> nodesAdded;
    private List<FlowNode> nodesRemoved;
    private List<FlowNode> nodesModified;
    private List<FlowNode> nodesUnchanged;

    private List<FlowEdge> edgesAdded;
    private List<FlowEdge> edgesModified;
    private List<FlowEdge> edgesRemoved;
    private List<FlowEdge> edgesUnchanged;

    private GraphMetrics metrics;

    private String graphType;              // "METHOD_LEVEL" or "MODULE_LEVEL"
    private List<String> languagesDetected; // ["Java", "JavaScript", "Python"]
    private String verificationStatus;     // "FULL_AST_ANALYSIS" or "PARTIAL_HEURISTIC"
    private String verificationNotes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphMetrics {
        private int totalNodes;
        private int totalEdges;
        private int maxDepth;               // Longest call chain
        private double avgComplexity;       // Average cyclomatic complexity
        private List<String> hotspots;      // Nodes with highest centrality
        private Map<String, Integer> callDistribution; // Method -> call count
    }
}