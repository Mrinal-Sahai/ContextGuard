
package io.contextguard.analysis.flow;

import java.util.*;

public class GraphMetricsComputer {

    public static void computeMetrics(Map<String, FlowNode> nodes, List<FlowEdge> edges) {

        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, List<String>> incoming = new HashMap<>();

        for (FlowEdge edge : edges) {
            outgoing.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge.getTo());
            incoming.computeIfAbsent(edge.getTo(), k -> new ArrayList<>()).add(edge.getFrom());
        }

        for (FlowNode node : nodes.values()) {
            int inDegree = incoming.getOrDefault(node.getId(), Collections.emptyList()).size();
            int outDegree = outgoing.getOrDefault(node.getId(), Collections.emptyList()).size();

            node.setInDegree(inDegree);
            node.setOutDegree(outDegree);
            node.setCentrality((double)(inDegree + outDegree) / Math.max(1, nodes.size()));
        }
    }
}