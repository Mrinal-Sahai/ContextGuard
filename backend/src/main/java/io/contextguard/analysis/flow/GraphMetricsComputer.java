package io.contextguard.analysis.flow;

import java.util.*;

/**
 * Computes graph-level metrics for FlowNode instances.
 *
 * FIX (2025-03):
 * 1. centrality was (inDegree + outDegree) / totalNodes — approaches 0 for large graphs.
 *    Now normalised by max possible degree: 2 × (n-1), keeping scores comparable
 *    across PRs of different sizes.
 *
 * 2. computeMetrics() mutated FlowNode objects in-place without any clear contract.
 *    This is preserved for backwards compatibility but is now explicitly documented
 *    as a deliberate side-effecting call.
 *
 * 3. TreeSet used internally for deterministic ordering — impactedAreas and hotspot
 *    lists no longer have random ordering between JVM runs.
 */
public class GraphMetricsComputer {

    /**
     * Compute and apply in-degree, out-degree, and centrality to every node.
     *
     * Side effects: mutates inDegree, outDegree, and centrality fields on each FlowNode.
     *
     * Centrality formula:
     *   centrality = (inDegree + outDegree) / max(1, 2 × (totalNodes - 1))
     *
     * Using 2*(n-1) as denominator (max possible degree in a directed graph)
     * keeps scores in [0, 1] and stable across different graph sizes.
     */
    public static void computeMetrics(Map<String, FlowNode> nodes, List<FlowEdge> edges) {

        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, List<String>> incoming = new HashMap<>();

        for (FlowEdge edge : edges) {
            outgoing.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge.getTo());
            incoming.computeIfAbsent(edge.getTo(),   k -> new ArrayList<>()).add(edge.getFrom());
        }

        // FIX: normalise by max possible degree instead of total node count.
        // max possible degree in a directed graph = 2 * (n - 1)
        double maxDegree = Math.max(1.0, 2.0 * (nodes.size() - 1));

        for (FlowNode node : nodes.values()) {
            int inDegree  = incoming.getOrDefault(node.getId(), Collections.emptyList()).size();
            int outDegree = outgoing.getOrDefault(node.getId(), Collections.emptyList()).size();

            node.setInDegree(inDegree);
            node.setOutDegree(outDegree);

            // FIX: use maxDegree denominator for stable cross-graph comparison
            node.setCentrality((inDegree + outDegree) / maxDegree);
        }
    }

    /**
     * Detect cycle in the call graph using DFS with three-colour marking.
     *
     * Returns true if any cycle exists (e.g. mutual recursion).
     * Callers can use this to skip transitive traversal that would loop forever.
     */
    public static boolean hasCycle(Map<String, FlowNode> nodes, List<FlowEdge> edges) {

        Map<String, List<String>> outgoing = new HashMap<>();
        for (FlowEdge edge : edges) {
            outgoing.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge.getTo());
        }

        Set<String> white  = new HashSet<>(nodes.keySet()); // unvisited
        Set<String> grey   = new HashSet<>();                // in-stack
        Set<String> black  = new HashSet<>();                // done

        for (String start : new TreeSet<>(nodes.keySet())) { // TreeSet for determinism
            if (white.contains(start)) {
                if (dfsCycleDetect(start, outgoing, white, grey, black)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean dfsCycleDetect(
            String node,
            Map<String, List<String>> outgoing,
            Set<String> white,
            Set<String> grey,
            Set<String> black) {

        white.remove(node);
        grey.add(node);

        for (String neighbour : outgoing.getOrDefault(node, Collections.emptyList())) {
            if (black.contains(neighbour)) continue;
            if (grey.contains(neighbour)) return true; // back-edge = cycle
            if (dfsCycleDetect(neighbour, outgoing, white, grey, black)) return true;
        }

        grey.remove(node);
        black.add(node);
        return false;
    }
}