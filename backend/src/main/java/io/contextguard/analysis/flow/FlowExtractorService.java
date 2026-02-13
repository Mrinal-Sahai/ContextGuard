
package io.contextguard.analysis.flow;

import io.contextguard.dto.PRIdentifier;
import io.contextguard.dto.PRIntelligenceResponse;
import io.contextguard.dto.PRMetadata;
import io.contextguard.model.PRAnalysisResult;
import io.contextguard.repository.PRAnalysisRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Production-grade flow extractor with full AST parsing.
 *
 * PIPELINE:
 * 1. Clone repository
 * 2. Checkout base branch → parse AST → extract call graph (baseGraph)
 * 3. Checkout head branch → parse AST → extract call graph (headGraph)
 * 4. Compute differential: compare baseGraph vs headGraph
 * 5. Generate CallGraphDiff with accurate ADDED/REMOVED/UNCHANGED
 * 6. Cleanup repository
 */
@Service
public class FlowExtractorService {

    private static final Logger logger = LoggerFactory.getLogger(FlowExtractorService.class);

    private final GitRepositoryService gitService;
    private final ASTParserService astParser;
    private final PRAnalysisRepository repo ;

    public FlowExtractorService(GitRepositoryService gitService, ASTParserService astParser, PRAnalysisRepository repo) {
        this.gitService = gitService;
        this.astParser = astParser;
        this.repo = repo;
    }

    /**
     * Generate accurate call graph diff.
     *
     * @param prMetadata PR metadata with repo URL and branches
     * @param githubToken GitHub personal access token (can be null for public repos)
     * @return CallGraphDiff with method-level accuracy
     */
    public CallGraphDiff
    generateDiagram(PRIntelligenceResponse intelligence, PRMetadata prMetadata, String githubToken, PRIdentifier prId, List<String> files) {

            // Step 1: Parse base branch
            logger.info("Parsing base branch: {}", prMetadata.getBaseBranch());
            ASTParserService.ParsedCallGraph baseGraph = astParser.parseDirectoryFromGithub( prMetadata.getBaseRepo(),prMetadata.getBaseSha(),files);

            // Step 2: Parse head branch
            logger.info("Parsing head branch: {}", prMetadata.getHeadBranch());
            ASTParserService.ParsedCallGraph headGraph = astParser.parseDirectoryFromGithub(prMetadata.getHeadRepo(),prMetadata.getHeadSha(),files);
             intelligence.getMetrics().setComplexityDelta(calculateComplexity(baseGraph, headGraph));

        PRAnalysisResult result = repo.findByOwnerAndRepoAndPrNumber(prId.getOwner(), prId.getRepo(), prId.getPrNumber()).orElseThrow();
        result.setIntelligence(intelligence);
        result.setAnalyzedAt(Instant.now());
        repo.save(result);


        // Step 3: Compute differential
            logger.info("Computing differential: base={} nodes, head={} nodes",
                    baseGraph.nodes.size(), headGraph.nodes.size());
            CallGraphDiff diff = computeDifferential(baseGraph, headGraph);

            diff.setGraphType("METHOD_LEVEL");
            diff.setVerificationStatus("FULL_AST_ANALYSIS");
            diff.setVerificationNotes(String.format(
                    "Full AST parsing completed. Analyzed %d files across %d languages: %s",
                    baseGraph.fileCountByLanguage.values().stream().mapToInt(Integer::intValue).sum() +
                            headGraph.fileCountByLanguage.values().stream().mapToInt(Integer::intValue).sum(),
                    headGraph.languages.size(),
                    String.join(", ", headGraph.languages)
            ));

            // Set detected languages
            diff.setLanguagesDetected(new ArrayList<>(headGraph.languages));

            logger.info("Differential computed: {} added, {} removed, {} modified nodes",
                    diff.getNodesAdded().size(),
                    diff.getNodesRemoved().size(),
                    diff.getNodesModified().size());

            return diff;

    }

    /**
     * Compute differential between base and head graphs.
     *
     * ALGORITHM:
     * Nodes:
     * - In head but not base → ADDED
     * - In base but not head → REMOVED
     * - In both with different properties → MODIFIED
     * - In both unchanged → UNCHANGED
     *
     * Edges:
     * - Build edge signature sets from both graphs
     * - Compare signatures to determine ADDED/REMOVED/UNCHANGED
     */
    private CallGraphDiff computeDifferential(
            ASTParserService.ParsedCallGraph baseGraph,
            ASTParserService.ParsedCallGraph headGraph) {

        Set<String> baseNodeIds = baseGraph.nodes.keySet();
        Set<String> headNodeIds = headGraph.nodes.keySet();

        List<FlowNode> nodesAdded = headNodeIds.stream()
                                            .filter(id -> !baseNodeIds.contains(id))
                                            .map(id -> {
                                                FlowNode node = headGraph.nodes.get(id);
                                                node.setStatus(FlowNode.NodeStatus.ADDED);
                                                return node;
                                            })
                                            .collect(Collectors.toList());

        List<FlowNode> nodesRemoved = baseNodeIds.stream()
                                              .filter(id -> !headNodeIds.contains(id))
                                              .map(id -> {
                                                  FlowNode node = baseGraph.nodes.get(id);
                                                  node.setStatus(FlowNode.NodeStatus.REMOVED);
                                                  return node;
                                              })
                                              .collect(Collectors.toList());

        List<FlowNode> nodesModified = new ArrayList<>();
        List<FlowNode> nodesUnchanged = new ArrayList<>();

        for (String id : headNodeIds) {
            if (baseNodeIds.contains(id)) {
                FlowNode baseNode = baseGraph.nodes.get(id);
                FlowNode headNode = headGraph.nodes.get(id);

                if (isNodeModified(baseNode, headNode)) {
                    headNode.setStatus(FlowNode.NodeStatus.MODIFIED);
                    nodesModified.add(headNode);
                } else {
                    headNode.setStatus(FlowNode.NodeStatus.UNCHANGED);
                    nodesUnchanged.add(headNode);
                }
            }
        }

        // ==========================================
        // COMPUTE EDGE DIFFERENCES
        // ==========================================

        // Create edge signature maps for comparison
        Map<String, FlowEdge> baseEdgeMap = createEdgeSignatureMap(baseGraph.edges);
        Map<String, FlowEdge> headEdgeMap = createEdgeSignatureMap(headGraph.edges);

        Set<String> baseEdgeSigs = baseEdgeMap.keySet();
        Set<String> headEdgeSigs = headEdgeMap.keySet();

        List<FlowEdge> edgesAdded = headEdgeSigs.stream()
                                            .filter(sig -> !baseEdgeSigs.contains(sig))
                                            .map(sig -> {
                                                FlowEdge edge = headEdgeMap.get(sig);
                                                edge.setStatus(FlowEdge.EdgeStatus.ADDED);
                                                return edge;
                                            })
                                            .collect(Collectors.toList());

        List<FlowEdge> edgesRemoved = baseEdgeSigs.stream()
                                              .filter(sig -> !headEdgeSigs.contains(sig))
                                              .map(sig -> {
                                                  FlowEdge edge = baseEdgeMap.get(sig);
                                                  edge.setStatus(FlowEdge.EdgeStatus.REMOVED);
                                                  return edge;
                                              })
                                              .collect(Collectors.toList());

        List<FlowEdge> edgesUnchanged = headEdgeSigs.stream()
                                                .filter(baseEdgeSigs::contains)
                                                .map(sig -> {
                                                    FlowEdge edge = headEdgeMap.get(sig);
                                                    edge.setStatus(FlowEdge.EdgeStatus.UNCHANGED);
                                                    return edge;
                                                })
                                                .collect(Collectors.toList());

        // ==========================================
        // COMPUTE METRICS
        // ==========================================

        CallGraphDiff.GraphMetrics metrics = computeGraphMetrics(
                nodesAdded, nodesRemoved, nodesModified, nodesUnchanged,
                edgesAdded, edgesRemoved, edgesUnchanged);

        return CallGraphDiff.builder()
                       .nodesAdded(nodesAdded)
                       .nodesRemoved(nodesRemoved)
                       .nodesModified(nodesModified)
                       .nodesUnchanged(nodesUnchanged)
                       .edgesAdded(edgesAdded)
                       .edgesRemoved(edgesRemoved)
                       .edgesUnchanged(edgesUnchanged)
                       .metrics(metrics)
                       .languagesDetected(new ArrayList<>(headGraph.languages))
                       .build();
    }

    /**
     * Create edge signature map for comparison.
     *
     * Edge signature = "from -> to"
     * This allows us to compare edges across base and head graphs.
     */
    private Map<String, FlowEdge> createEdgeSignatureMap(List<FlowEdge> edges) {
        Map<String, FlowEdge> map = new HashMap<>();

        for (FlowEdge edge : edges) {
            String signature = edge.getFrom() + " -> " + edge.getTo();
            map.put(signature, edge);
        }

        return map;
    }

    /**
     * Determine if node was modified.
     *
     * A node is considered modified if:
     * - Cyclomatic complexity changed
     * - Line count changed significantly (>10%)
     * - Annotations changed
     */
    private boolean isNodeModified(FlowNode base, FlowNode head) {

        // Complexity changed
        if (base.getCyclomaticComplexity() != head.getCyclomaticComplexity()) {
            return true;
        }

        // Line count changed significantly
        int baseLoc = base.getEndLine() - base.getStartLine();
        int headLoc = head.getEndLine() - head.getStartLine();
        double changePercent = Math.abs(headLoc - baseLoc) / (double) Math.max(1, baseLoc);
        if (changePercent > 0.1) { // >10% change
            return true;
        }

        // Annotations changed
        if (!Objects.equals(base.getAnnotations(), head.getAnnotations())) {
            return true;
        }

        // Return type changed
        if (!Objects.equals(base.getReturnType(), head.getReturnType())) {
            return true;
        }

        return false;
    }

    /**
     * Compute graph-level metrics.
     */
    private CallGraphDiff.GraphMetrics computeGraphMetrics(
            List<FlowNode> added, List<FlowNode> removed,
            List<FlowNode> modified, List<FlowNode> unchanged,
            List<FlowEdge> edgesAdded, List<FlowEdge> edgesRemoved,
            List<FlowEdge> edgesUnchanged) {

        // Combine all current nodes
        List<FlowNode> allNodes = new ArrayList<>();
        allNodes.addAll(added);
        allNodes.addAll(modified);
        allNodes.addAll(unchanged);

        // Average complexity
        double avgComplexity = allNodes.stream()
                                       .mapToInt(FlowNode::getCyclomaticComplexity)
                                       .average()
                                       .orElse(0.0);

        // Find hotspots (modified nodes with highest centrality)
        List<String> hotspots = allNodes.stream()
                                        .filter(n -> n.getStatus() == FlowNode.NodeStatus.MODIFIED)
                                        .sorted((a, b) -> Double.compare(b.getCentrality(), a.getCentrality()))
                                        .limit(5)
                                        .map(FlowNode::getId)
                                        .collect(Collectors.toList());

        // Compute max call depth (simplified - just find longest path)
        int maxDepth = computeMaxCallDepth(allNodes, edgesUnchanged);

        // Call distribution (methods with most calls)
        Map<String, Integer> callDistribution = new HashMap<>();
        for (FlowNode node : allNodes) {
            callDistribution.put(node.getId(), node.getInDegree());
        }

        return CallGraphDiff.GraphMetrics.builder()
                       .totalNodes(allNodes.size())
                       .totalEdges(edgesAdded.size() + edgesRemoved.size() + edgesUnchanged.size())
                       .maxDepth(maxDepth)
                       .avgComplexity(avgComplexity)
                       .hotspots(hotspots)
                       .callDistribution(callDistribution)
                       .build();
    }

    /**
     * Compute maximum call depth (longest path in call graph).
     *
     * Uses BFS to find longest path from entry points.
     */
    private int computeMaxCallDepth(List<FlowNode> nodes, List<FlowEdge> edges) {

        // Build adjacency list
        Map<String, List<String>> graph = new HashMap<>();
        for (FlowEdge edge : edges) {
            graph.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge.getTo());
        }

        // Find entry points (nodes with no incoming edges)
        Set<String> hasIncoming = edges.stream()
                                          .map(FlowEdge::getTo)
                                          .collect(Collectors.toSet());

        List<String> entryPoints = nodes.stream()
                                           .map(FlowNode::getId)
                                           .filter(id -> !hasIncoming.contains(id))
                                           .collect(Collectors.toList());

        // BFS from each entry point
        int maxDepth = 0;
        for (String entry : entryPoints) {
            int depth = bfsMaxDepth(entry, graph);
            maxDepth = Math.max(maxDepth, depth);
        }

        return maxDepth;
    }

    /**
     * BFS to find max depth from a starting node.
     */
    private int bfsMaxDepth(String start, Map<String, List<String>> graph) {

        Queue<NodeDepth> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();

        queue.offer(new NodeDepth(start, 0));
        visited.add(start);

        int maxDepth = 0;

        while (!queue.isEmpty()) {
            NodeDepth current = queue.poll();
            maxDepth = Math.max(maxDepth, current.depth);

            List<String> neighbors = graph.getOrDefault(current.nodeId, Collections.emptyList());
            for (String neighbor : neighbors) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    queue.offer(new NodeDepth(neighbor, current.depth + 1));
                }
            }
        }

        return maxDepth;
    }

    /**
     * Helper class for BFS traversal.
     */
    private static class NodeDepth {
        String nodeId;
        int depth;

        NodeDepth(String nodeId, int depth) {
            this.nodeId = nodeId;
            this.depth = depth;
        }
    }

    /**
     * Extract repository clone URL from PR URL.
     *
     * Example: https://github.com/owner/repo/pull/123 → https://github.com/owner/repo.git
     */
    public String extractRepoUrl(String prUrl) {
        try {
            String[] parts = prUrl.split("/");
            if (parts.length >= 5) {
                return String.format("https://github.com/%s/%s.git", parts[3], parts[4]);
            }
            throw new IllegalArgumentException("Invalid PR URL format");
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to extract repository URL from: " + prUrl, e);
        }
    }

    /**
     * Create fallback diff when full analysis fails.
     */
    private CallGraphDiff createFallbackDiff(String reason) {
        logger.warn("Creating fallback diff: {}", reason);

        return CallGraphDiff.builder()
                       .graphType("MODULE_LEVEL")
                       .verificationStatus("FALLBACK_HEURISTIC")
                       .verificationNotes("Full analysis failed: " + reason + ". Using module-level heuristics.")
                       .nodesAdded(Collections.emptyList())
                       .nodesRemoved(Collections.emptyList())
                       .nodesModified(Collections.emptyList())
                       .nodesUnchanged(Collections.emptyList())
                       .edgesAdded(Collections.emptyList())
                       .edgesRemoved(Collections.emptyList())
                       .edgesUnchanged(Collections.emptyList())
                       .metrics(CallGraphDiff.GraphMetrics.builder()
                                        .totalNodes(0)
                                        .totalEdges(0)
                                        .maxDepth(0)
                                        .avgComplexity(0.0)
                                        .hotspots(Collections.emptyList())
                                        .callDistribution(Collections.emptyMap())
                                        .build())
                       .languagesDetected(Collections.emptyList())
                       .build();
    }

    private int calculateComplexity(ASTParserService.ParsedCallGraph baseGraph, ASTParserService.ParsedCallGraph  headGraph) {
        Map<String, FlowNode> baseNodes = baseGraph.nodes;
        Map<String, FlowNode> headNodes = headGraph.nodes;

        Set<String> addedIds = new HashSet<>(headNodes.keySet());
        addedIds.removeAll(baseNodes.keySet());
        Set<String> removedIds = new HashSet<>(baseNodes.keySet());
        removedIds.removeAll(headNodes.keySet());
        Set<String> commonIds = new HashSet<>(baseNodes.keySet());
        commonIds.retainAll(headNodes.keySet());

        int complexityDelta = 0;

// 1. Added nodes → full complexity
        for (String id : addedIds) {
            complexityDelta += headNodes.get(id).getCyclomaticComplexity();
        }

// 2. Removed nodes → subtract complexity
        for (String id : removedIds) {
            complexityDelta -= baseNodes.get(id).getCyclomaticComplexity();
        }

// 3. Modified nodes → delta only
        for (String id : commonIds) {
            FlowNode base = baseNodes.get(id);
            FlowNode head = headNodes.get(id);

            int delta = head.getCyclomaticComplexity()
                                - base.getCyclomaticComplexity();

            // Only count if actually changed
            if (delta != 0) {
                complexityDelta += delta;
            }
        }
        return complexityDelta;
    }

}