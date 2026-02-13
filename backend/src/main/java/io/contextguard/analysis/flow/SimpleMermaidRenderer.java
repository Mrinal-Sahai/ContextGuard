package io.contextguard.analysis.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class SimpleMermaidRenderer {
    private static final int TOP_N = 8;

    public String renderSimple(CallGraphDiff diff) {
        StringBuilder sb = new StringBuilder();
        sb.append("%%{init:{'theme':'base'}}%%\ngraph TB\n");

        // pick top N nodes by centrality (across added/modified/unchanged)
        List<FlowNode> all = new ArrayList<>();
        all.addAll(diff.getNodesAdded());
        all.addAll(diff.getNodesModified());
        all.addAll(diff.getNodesUnchanged());
        all.sort((a,b) -> Double.compare(b.getCentrality(), a.getCentrality()));
        List<FlowNode> top = all.stream().limit(TOP_N).collect(Collectors.toList());

        // make a small "Tests" cluster if many test nodes exist
        List<FlowNode> tests = top.stream()
                                       .filter(n -> n.getId().toLowerCase().contains("test"))
                                       .collect(Collectors.toList());
        if (!tests.isEmpty()) {
            sb.append("  subgraph Tests[\"📦 Tests (representative)\"]\n");
            sb.append("    TestsNode[\"➕ Tests\"]:::added\n");
            sb.append("  end\n");
        }

        // render top nodes (non-tests)
        for (FlowNode n : top) {
            if (n.getId().toLowerCase().contains("test")) continue;
            String id = sanitize(n.getId());
            String label = shortLabel(n.getLabel());
            sb.append("  ").append(id).append("[\"").append(label).append("\"]:::added\n");
        }

        // render only edges between top nodes and the TestsNode (if a test calls them)
        for (FlowEdge e : diff.getEdgesAdded()) {
            boolean fromIsTop = top.stream().anyMatch(n -> n.getId().equals(e.getFrom()));
            boolean toIsTop = top.stream().anyMatch(n -> n.getId().equals(e.getTo()));
            if (fromIsTop && toIsTop) {
                sb.append("  ").append(sanitize(e.getFrom())).append(" --> ").append(sanitize(e.getTo())).append("\n");
            } else if (top.stream().anyMatch(n -> n.getId().equals(e.getTo()))
                               && e.getFrom().toLowerCase().contains("test")) {
                sb.append("  TestsNode --> ").append(sanitize(e.getTo())).append("\n");
            }
        }

        sb.append("\n  classDef added fill:#dcfce7,stroke:#22c55e\n");
        return sb.toString();
    }

    private String sanitize(String id) { return id.replaceAll("[^A-Za-z0-9_]", "_"); }
    private String shortLabel(String l) { return l.length() > 24 ? l.substring(0,21)+"..." : l; }
}