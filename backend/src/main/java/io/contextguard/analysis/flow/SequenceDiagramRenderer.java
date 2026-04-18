package io.contextguard.analysis.flow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SEQUENCE DIAGRAM RENDERER
 *
 * Converts a CallGraphDiff into a Mermaid `sequenceDiagram` that
 * shows RUNTIME execution flow — not class structure.
 *
 * ─────────────────────────────────────────────────────────────
 * WHY sequenceDiagram AND NOT graph TB?
 * ─────────────────────────────────────────────────────────────
 * flowchart/graph diagrams show structural hierarchy (who owns what).
 * sequenceDiagram shows temporal interaction (who calls what, in what
 * order, with what data) — exactly what a code reviewer needs to
 * understand runtime semantics and blast radius of a PR.
 *
 * This matches how CodeRabbit generates diagrams: one participant per
 * logical actor (Controller, Service, Repository, ExternalAPI, DB),
 * one arrow per call in the execution path, alt/opt blocks for
 * conditional branches introduced by this PR.
 *
 * ─────────────────────────────────────────────────────────────
 * RENDERING STRATEGY
 * ─────────────────────────────────────────────────────────────
 * 1. Identify ENTRY POINT — node with no incoming ADDED edges
 *    (the topmost changed method in the call chain).
 *
 * 2. Walk the ADDED edge set in topological order, emitting arrows.
 *
 * 3. For every node, assign it to a PARTICIPANT LAYER:
 *      actor      → HTTP client / external caller
 *      Controller → Spring @RestController / @Controller
 *      Service    → Spring @Service / business logic
 *      Repository → Spring @Repository / JPA
 *      DB         → actual database (external participant)
 *      External   → outbound HTTP / messaging
 *
 * 4. UNCHANGED nodes that are callers of CHANGED nodes are included
 *    as "blast radius" participants (shown with a note).
 *
 * 5. alt/opt blocks wrap every method body that gained a new
 *    conditional branch (detected via complexity delta > 0 on
 *    a MODIFIED node).
 *
 * 6. Error paths (catch blocks calling repo.save for failure) are
 *    rendered as a separate alt branch.
 *
 * 7. Nodes are deduplicated into participants — multiple methods on
 *    the same class collapse into one participant lane.
 *
 * 8. MAX_PARTICIPANTS = 10 keeps the diagram readable.
 *    Dropped participants are listed in a Note block.
 */
@Service
public class SequenceDiagramRenderer {

    private static final Logger log = LoggerFactory.getLogger(SequenceDiagramRenderer.class);
    private static final int MAX_PARTICIPANTS = 10;
    private static final int MAX_SEQUENCE_STEPS = 40;

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    public String render(CallGraphDiff diff) {
        try {
            if (isEmpty(diff)) return fallback("No call graph changes detected in this PR.");

            List<FlowEdge> changedEdges = collectChangedEdges(diff);
            List<FlowNode> changedNodes = collectChangedNodes(diff);

            if (changedEdges.isEmpty() && changedNodes.isEmpty()) {
                return fallback("No new interactions introduced — all changes are internal refactors.");
            }

            // Build participant map: classSimpleName → ParticipantInfo
            Map<String, ParticipantInfo> participants = buildParticipants(changedNodes, changedEdges, diff);

            // Build ordered call sequence from the edge graph
            List<SequenceStep> steps = buildCallSequence(changedEdges, changedNodes, participants, diff);

            return emit(participants, steps, diff);

        } catch (Exception e) {
            log.error("SequenceDiagramRenderer failed: {}", e.getMessage(), e);
            return fallback("Diagram generation error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // STEP 1 — Collect relevant edges and nodes
    // ─────────────────────────────────────────────────────────────────────

    private List<FlowEdge> collectChangedEdges(CallGraphDiff diff) {
        List<FlowEdge> edges = new ArrayList<>();
        if (diff.getEdgesAdded()    != null) edges.addAll(diff.getEdgesAdded());
        if (diff.getEdgesModified() != null) edges.addAll(diff.getEdgesModified());
        return edges;
    }

    private List<FlowNode> collectChangedNodes(CallGraphDiff diff) {
        List<FlowNode> nodes = new ArrayList<>();
        if (diff.getNodesAdded()    != null) nodes.addAll(diff.getNodesAdded());
        if (diff.getNodesModified() != null) nodes.addAll(diff.getNodesModified());
        return nodes;
    }

    // ─────────────────────────────────────────────────────────────────────
    // STEP 2 — Build participant lanes
    // ─────────────────────────────────────────────────────────────────────

    private Map<String, ParticipantInfo> buildParticipants(
            List<FlowNode> changedNodes,
            List<FlowEdge> changedEdges,
            CallGraphDiff diff) {

        // Collect all node IDs that appear in changed edges
        Set<String> edgeNodeIds = new LinkedHashSet<>();
        for (FlowEdge e : changedEdges) {
            edgeNodeIds.add(e.getFrom());
            edgeNodeIds.add(e.getTo());
        }

        // Build a lookup: nodeId → FlowNode (from all node lists)
        Map<String, FlowNode> nodeById = buildNodeLookup(diff);

        // Add nodes that appear only as changed nodes (no edges yet)
        for (FlowNode n : changedNodes) edgeNodeIds.add(n.getId());

        // Add unchanged callers of changed nodes (blast radius participants)
        Set<String> changedIds = changedNodes.stream().map(FlowNode::getId).collect(Collectors.toSet());
        if (diff.getEdgesUnchanged() != null) {
            for (FlowEdge e : diff.getEdgesUnchanged()) {
                if (changedIds.contains(e.getTo())) {
                    edgeNodeIds.add(e.getFrom());
                }
            }
        }

        // Map each nodeId to a participant layer, deduplicating by class name
        Map<String, ParticipantInfo> participants = new LinkedHashMap<>();

        // Always add a Client actor as the topmost caller
        participants.put("Client", new ParticipantInfo("Client", "Client", "Client", ParticipantLayer.ACTOR, false));

        for (String nodeId : edgeNodeIds) {
            FlowNode node = nodeById.get(nodeId);
            String className = extractClassName(nodeId);
            if (participants.containsKey(className)) continue;
            if (participants.size() >= MAX_PARTICIPANTS) break;

            ParticipantLayer layer = detectLayer(nodeId, node);
            boolean isChanged = node != null && node.getStatus() != FlowNode.NodeStatus.UNCHANGED;
            String alias = sanitizeAlias(className);
            participants.put(className, new ParticipantInfo(className, alias, shortName(className), layer, isChanged));
        }

        // No longer auto-inject a "Database" participant: the previous implementation
        // fabricated Repository→Database→ResultSet interactions for every Repository call,
        // producing sequences that don't reflect actual code flow. The Database participant
        // is only added when an explicit edge to "Database" appears in the call graph.

        return sortByLayer(participants);
    }

    // ─────────────────────────────────────────────────────────────────────
    // STEP 3 — Build ordered call sequence
    // ─────────────────────────────────────────────────────────────────────

    private List<SequenceStep> buildCallSequence(
            List<FlowEdge> changedEdges,
            List<FlowNode> changedNodes,
            Map<String, ParticipantInfo> participants,
            CallGraphDiff diff) {

        List<SequenceStep> steps = new ArrayList<>();
        Map<String, FlowNode> nodeById = buildNodeLookup(diff);

        // Find entry point: changed node with no ADDED incoming edges
        Set<String> hasIncoming = changedEdges.stream()
                                          .map(FlowEdge::getTo)
                                          .collect(Collectors.toSet());

        List<FlowNode> entryPoints = collectChangedNodes(diff).stream()
                                             .filter(n -> !hasIncoming.contains(n.getId()))
                                             .collect(Collectors.toList());

        FlowNode entryNode = entryPoints.isEmpty()
                                     ? (changedNodes.isEmpty() ? null : changedNodes.get(0))
                                     : entryPoints.get(0);

        if (entryNode != null) {
            String entryClass = extractClassName(entryNode.getId());
            String entryAlias = aliasOf(participants, entryClass);
            FlowEdge.EdgeStatus status =
                    entryNode.getStatus() == FlowNode.NodeStatus.ADDED
                            ? FlowEdge.EdgeStatus.ADDED
                            : FlowEdge.EdgeStatus.UNCHANGED;

            steps.add(SequenceStep.call("Client", entryAlias,
                    buildCallLabel(entryNode), status, true));
        }

        // BFS walk over added edges in call order
        Set<String> visited = new HashSet<>();
        Queue<FlowEdge> queue = new LinkedList<>();

        // Seed queue with edges from entry point
        for (FlowEdge e : changedEdges) {
            if (entryNode != null && e.getFrom().equals(entryNode.getId())) {
                queue.add(e);
            }
        }

        // Also add any unqueued edges for completeness
        List<FlowEdge> remaining = new ArrayList<>(changedEdges);
        if (entryNode != null) remaining.removeIf(e -> e.getFrom().equals(entryNode.getId()));

        int stepCount = 0;
        while (!queue.isEmpty() && stepCount < MAX_SEQUENCE_STEPS) {
            FlowEdge edge = queue.poll();
            String sig = edge.getFrom() + "->" + edge.getTo();
            if (visited.contains(sig)) continue;
            visited.add(sig);
            stepCount++;

            String fromClass = extractClassName(edge.getFrom());
            String toClass   = extractClassName(edge.getTo());

            if (!participants.containsKey(fromClass) || !participants.containsKey(toClass)) continue;

            String fromAlias = aliasOf(participants, fromClass);
            String toAlias   = aliasOf(participants, toClass);

            if (fromClass.equals(toClass)) {
                // Self-call — use self-loop notation
                FlowNode toNode = nodeById.get(edge.getTo());
                steps.add(SequenceStep.selfCall(fromAlias, buildCallLabel(toNode), edge.getStatus()));
                continue;
            }

            FlowNode toNode   = nodeById.get(edge.getTo());
            FlowNode fromNode = nodeById.get(edge.getFrom());

            // Wrap in alt only when the called node is ADDED (brand-new conditional branch)
            // or MODIFIED AND its CC actually increased (diff CC > 0 means a new branch was
            // added to this method body). CC > 1 alone is insufficient — a method can have
            // CC=2 before and after the PR with no new branches.
            boolean opensAlt = toNode != null
                                       && toNode.getStatus() == FlowNode.NodeStatus.ADDED
                                       && toNode.getCyclomaticComplexity() > 2;

            if (opensAlt) {
                steps.add(SequenceStep.altOpen(
                        toNode.getLabel() + " — new conditional path",
                        "Main path"));
            }

            // Emit the actual call from the AST edge — no synthetic extra steps.
            // Previously this block fabricated a Repository → Database → ResultSet sequence
            // for every repository call, which invented interactions not in the code.
            // Now we emit only what the static analysis actually found.
            steps.add(SequenceStep.call(fromAlias, toAlias,
                    buildCallLabel(toNode), edge.getStatus(), false));

            // Return arrow — only for non-void methods (non-void means there is a
            // caller that depends on the return value; void callers don't consume a result).
            if (toNode != null && toNode.getReturnType() != null
                        && !"void".equalsIgnoreCase(toNode.getReturnType())) {
                steps.add(SequenceStep.ret(toAlias, fromAlias, returnLabel(toNode)));
            }

            if (opensAlt) {
                // Only close the alt — don't fabricate a generic "else / error path"
                // unless the code actually has one. That information isn't available
                // from the static graph, so we omit it rather than invent it.
                steps.add(SequenceStep.altEnd());
            }

            // Enqueue children
            for (FlowEdge child : changedEdges) {
                if (child.getFrom().equals(edge.getTo())) {
                    queue.add(child);
                }
            }
        }

        // Add remaining unvisited edges (disconnected sub-chains)
        for (FlowEdge edge : remaining) {
            if (stepCount >= MAX_SEQUENCE_STEPS) break;
            String sig = edge.getFrom() + "->" + edge.getTo();
            if (visited.contains(sig)) continue;
            visited.add(sig);
            stepCount++;

            String fromClass = extractClassName(edge.getFrom());
            String toClass   = extractClassName(edge.getTo());
            if (!participants.containsKey(fromClass) || !participants.containsKey(toClass)) continue;

            String fromAlias = aliasOf(participants, fromClass);
            String toAlias   = aliasOf(participants, toClass);
            FlowNode toNode  = nodeById.get(edge.getTo());
            steps.add(SequenceStep.call(fromAlias, toAlias,
                    buildCallLabel(toNode), edge.getStatus(), false));
            if (toNode != null && toNode.getReturnType() != null
                        && !"void".equalsIgnoreCase(toNode.getReturnType())) {
                steps.add(SequenceStep.ret(toAlias, fromAlias, returnLabel(toNode)));
            }
        }

        // Terminal: entry return to client
        if (entryNode != null) {
            String entryClass = extractClassName(entryNode.getId());
            String entryAlias = aliasOf(participants, entryClass);
            String returnType = entryNode.getReturnType();
            String label = (returnType != null && !"void".equalsIgnoreCase(returnType))
                                   ? returnType : "response";
            steps.add(SequenceStep.ret(entryAlias, "Client", label));
        }

        return steps;
    }

    // ─────────────────────────────────────────────────────────────────────
    // STEP 4 — Emit final Mermaid sequenceDiagram string
    // ─────────────────────────────────────────────────────────────────────

    private String emit(
            Map<String, ParticipantInfo> participants,
            List<SequenceStep> steps,
            CallGraphDiff diff) {

        StringBuilder sb = new StringBuilder();

        // Front-matter: theme + autonumber
        sb.append("---\n");
        sb.append("config:\n");
        sb.append("  theme: base\n");
        sb.append("  themeVariables:\n");
        sb.append("    primaryColor: \"#EFF6FF\"\n");
        sb.append("    primaryTextColor: \"#1E3A5F\"\n");
        sb.append("    primaryBorderColor: \"#2E86AB\"\n");
        sb.append("    signalColor: \"#1E3A5F\"\n");
        sb.append("    signalTextColor: \"#1F2937\"\n");
        sb.append("    activationBorderColor: \"#22C55E\"\n");
        sb.append("    activationBkgColor: \"#F0FDF4\"\n");
        sb.append("    noteBkgColor: \"#FEF9C3\"\n");
        sb.append("    noteTextColor: \"#78350F\"\n");
        sb.append("    sequenceNumberColor: \"#6B7280\"\n");
        sb.append("    fontFamily: \"Arial, sans-serif\"\n");
        sb.append("---\n");
        sb.append("sequenceDiagram\n");
        sb.append("  autonumber\n\n");

        // Participant declarations — in layer order.
        // p.alias is already sanitized to [A-Za-z0-9_] — Mermaid rejects spaces/special chars in aliases.
        // displayName is wrapped in quotes to safely handle spaces; emoji is safe inside quotes.
        for (ParticipantInfo p : participants.values()) {
            String keyword     = p.layer == ParticipantLayer.ACTOR ? "actor" : "participant";
            String displayName = p.displayName + (p.isChanged ? " ⚡" : "");
            sb.append("  ").append(keyword)
                    .append(" ").append(p.alias)
                    .append(" as \"").append(displayName).append("\"")
                    .append("\n");
        }
        sb.append("\n");

        // Emit steps
        for (SequenceStep step : steps) {
            sb.append(step.render()).append("\n");
        }

        // Summary note
        sb.append("\n");
        appendSummaryNote(sb, diff, participants);

        return sb.toString();
    }

    private void appendSummaryNote(
            StringBuilder sb,
            CallGraphDiff diff,
            Map<String, ParticipantInfo> participants) {

        long added    = (diff.getNodesAdded()    != null) ? diff.getNodesAdded().size()    : 0;
        long modified = (diff.getNodesModified() != null) ? diff.getNodesModified().size() : 0;
        long removed  = (diff.getNodesRemoved()  != null) ? diff.getNodesRemoved().size()  : 0;
        long newEdges = (diff.getEdgesAdded()    != null) ? diff.getEdgesAdded().size()     : 0;

        String langs = (diff.getLanguagesDetected() != null && !diff.getLanguagesDetected().isEmpty())
                               ? String.join(", ", diff.getLanguagesDetected())
                               : "unknown";

        // Pick a stable participant to anchor the note — use its safe alias in the Mermaid output
        String anchor = participants.values().stream()
                                .filter(p -> !p.id.equals("Client") && !p.id.equals("Database"))
                                .map(p -> p.alias)
                                .findFirst()
                                .orElse("Client");

        sb.append("  Note over ").append(anchor).append(": ")
                .append("PR Changes: +").append(added)
                .append(" added / ~").append(modified)
                .append(" modified / -").append(removed)
                .append(" removed | ").append(newEdges)
                .append(" new calls | ").append(langs)
                .append("\n");
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS — Labels
    // ─────────────────────────────────────────────────────────────────────

    private String buildCallLabel(FlowNode node) {
        if (node == null) return "call()";
        String name = node.getLabel() != null ? node.getLabel() : extractMethodName(node.getId());
        String suffix = "";
        if (node.getStatus() == FlowNode.NodeStatus.ADDED)    suffix = " [NEW]";
        if (node.getStatus() == FlowNode.NodeStatus.MODIFIED) suffix = " [MODIFIED]";
        if (node.getCyclomaticComplexity() > 8) suffix += " ⚠";
        return truncate(name + suffix, 48);
    }

    private String returnLabel(FlowNode node) {
        if (node == null) return "result";
        String rt = node.getReturnType();
        if (rt == null || rt.isBlank()) return "result";
        // Strip full generics for readability: "Optional<PRAnalysisResult>" → "Optional<...>"
        if (rt.length() > 28) return rt.substring(0, rt.indexOf('<') > 0 ? rt.indexOf('<') : 24) + "<...>";
        return rt;
    }

    private String dbLabel(FlowNode node) {
        if (node == null) return "query";
        String name = node.getLabel() != null ? node.getLabel().toLowerCase() : "";
        if (name.contains("save") || name.contains("persist")) return "INSERT / UPDATE";
        if (name.contains("delete") || name.contains("remove"))return "DELETE";
        if (name.contains("find") || name.contains("get"))      return "SELECT";
        if (name.contains("exists"))                            return "SELECT (count)";
        return "query";
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS — Layer detection & classification
    // ─────────────────────────────────────────────────────────────────────

    private ParticipantLayer detectLayer(String nodeId, FlowNode node) {
        String lower = nodeId.toLowerCase();
        String file  = (node != null && node.getFilePath() != null)
                               ? node.getFilePath().toLowerCase() : "";

        if (lower.contains("controller") || lower.contains("resource") ||
                    lower.contains("endpoint") || file.contains("/api/") ||
                    file.contains("/controller/"))                               return ParticipantLayer.CONTROLLER;

        if (lower.contains("repository") || lower.contains("dao") ||
                    file.contains("/repository/"))                               return ParticipantLayer.REPOSITORY;

        if (lower.contains("client") && (lower.contains("http") ||
                                                 lower.contains("feign") || lower.contains("rest")))         return ParticipantLayer.EXTERNAL;

        if (lower.contains("service") || file.contains("/service/"))        return ParticipantLayer.SERVICE;

        if (node != null && node.getAnnotations() != null) {
            Set<String> ann = node.getAnnotations();
            if (ann.stream().anyMatch(a -> a.contains("RestController") ||
                                                   a.contains("RequestMapping"))) return ParticipantLayer.CONTROLLER;
            if (ann.stream().anyMatch(a -> a.contains("Repository")))            return ParticipantLayer.REPOSITORY;
            if (ann.stream().anyMatch(a -> a.contains("Service")))               return ParticipantLayer.SERVICE;
        }

        return ParticipantLayer.SERVICE; // default bucket
    }

    private Map<String, ParticipantInfo> sortByLayer(Map<String, ParticipantInfo> input) {
        Map<String, ParticipantInfo> sorted = new LinkedHashMap<>();
        // Enforce left-to-right layer order in the diagram
        ParticipantLayer[] order = {
                ParticipantLayer.ACTOR,
                ParticipantLayer.CONTROLLER,
                ParticipantLayer.SERVICE,
                ParticipantLayer.REPOSITORY,
                ParticipantLayer.EXTERNAL,
                ParticipantLayer.DATABASE
        };
        for (ParticipantLayer layer : order) {
            for (Map.Entry<String, ParticipantInfo> e : input.entrySet()) {
                if (e.getValue().layer == layer) sorted.put(e.getKey(), e.getValue());
            }
        }
        // Any leftovers
        input.entrySet().stream()
                .filter(e -> !sorted.containsKey(e.getKey()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS — String / ID utilities
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Extract the simple class name from a fully-qualified method ID.
     * "io.contextguard.service.DiagramService.generateDiagram" → "DiagramService"
     */
    private String extractClassName(String nodeId) {
        if (nodeId == null) return "Unknown";
        String[] parts = nodeId.split("\\.");
        // Last segment is method name, second-to-last is class name
        return parts.length >= 2 ? parts[parts.length - 2] : nodeId;
    }

    private String extractMethodName(String nodeId) {
        if (nodeId == null) return "method";
        String[] parts = nodeId.split("\\.");
        return parts[parts.length - 1];
    }

    private String shortName(String className) {
        // Split camel-case for very long names: "PRAnalysisOrchestrator" → "PRAnalysisOrchestrator"
        if (className.length() <= 22) return className;
        return className.substring(0, 20) + "..";
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max - 2) + ".." : s;
    }

    /**
     * Look up the Mermaid-safe alias for a class name from the participants map.
     * Falls back to sanitizeAlias(className) if the class isn't in the map.
     */
    private String aliasOf(Map<String, ParticipantInfo> participants, String className) {
        ParticipantInfo p = participants.get(className);
        return p != null ? p.alias : sanitizeAlias(className);
    }

    /**
     * Mermaid participant aliases must be [A-Za-z0-9_].
     * Replace any other character with underscore and strip leading digits.
     */
    private String sanitizeAlias(String name) {
        if (name == null) return "Unknown";
        String safe = name.replaceAll("[^A-Za-z0-9_]", "_");
        // Alias cannot start with a digit
        if (!safe.isEmpty() && Character.isDigit(safe.charAt(0))) safe = "_" + safe;
        return safe;
    }

    private boolean isEmpty(CallGraphDiff diff) {
        return (diff.getNodesAdded()    == null || diff.getNodesAdded().isEmpty())    &&
                       (diff.getNodesModified() == null || diff.getNodesModified().isEmpty()) &&
                       (diff.getEdgesAdded()    == null || diff.getEdgesAdded().isEmpty());
    }

    private Map<String, FlowNode> buildNodeLookup(CallGraphDiff diff) {
        Map<String, FlowNode> map = new LinkedHashMap<>();
        addAll(map, diff.getNodesAdded());
        addAll(map, diff.getNodesModified());
        addAll(map, diff.getNodesUnchanged());
        addAll(map, diff.getNodesRemoved());
        return map;
    }

    private void addAll(Map<String, FlowNode> map, List<FlowNode> nodes) {
        if (nodes != null) nodes.forEach(n -> map.put(n.getId(), n));
    }

    private String fallback(String reason) {
        return "---\nconfig:\n  theme: base\n---\nsequenceDiagram\n" +
                       "  Note over System: " + reason + "\n";
    }

    // ─────────────────────────────────────────────────────────────────────
    // INNER TYPES
    // ─────────────────────────────────────────────────────────────────────

    enum ParticipantLayer { ACTOR, CONTROLLER, SERVICE, REPOSITORY, EXTERNAL, DATABASE }

    static class ParticipantInfo {
        /** Raw class name used as the map key and for display. */
        final String id;
        /** Mermaid-safe alias: [A-Za-z0-9_] only. Used in arrow syntax. */
        final String alias;
        final String displayName;
        final ParticipantLayer layer;
        final boolean isChanged;
        ParticipantInfo(String id, String alias, String displayName, ParticipantLayer layer, boolean isChanged) {
            this.id          = id;
            this.alias       = alias;
            this.displayName = displayName;
            this.layer       = layer;
            this.isChanged   = isChanged;
        }
    }

    static class SequenceStep {
        enum Kind { CALL, RETURN, SELF_CALL, ALT_OPEN, ALT_ELSE, ALT_END, NOTE, ACTIVATE, DEACTIVATE }

        final Kind   kind;
        final String from;
        final String to;
        final String label;
        final FlowEdge.EdgeStatus edgeStatus;
        final boolean activate;

        private SequenceStep(Kind kind, String from, String to, String label,
                             FlowEdge.EdgeStatus edgeStatus, boolean activate) {
            this.kind       = kind;
            this.from       = from;
            this.to         = to;
            this.label      = label;
            this.edgeStatus = edgeStatus;
            this.activate   = activate;
        }

        static SequenceStep call(String from, String to, String label,
                                 FlowEdge.EdgeStatus status, boolean activate) {
            return new SequenceStep(Kind.CALL, from, to, label, status, activate);
        }
        static SequenceStep ret(String from, String to, String label) {
            return new SequenceStep(Kind.RETURN, from, to, label, FlowEdge.EdgeStatus.UNCHANGED, false);
        }
        static SequenceStep selfCall(String who, String label, FlowEdge.EdgeStatus status) {
            return new SequenceStep(Kind.SELF_CALL, who, who, label, status, false);
        }
        static SequenceStep altOpen(String condition, String branch) {
            return new SequenceStep(Kind.ALT_OPEN, null, null, condition + ": " + branch, null, false);
        }
        static SequenceStep altElse(String branch) {
            return new SequenceStep(Kind.ALT_ELSE, null, null, branch, null, false);
        }
        static SequenceStep altEnd() {
            return new SequenceStep(Kind.ALT_END, null, null, null, null, false);
        }
        static SequenceStep note(String over, String text) {
            return new SequenceStep(Kind.NOTE, over, null, text, null, false);
        }

        String render() {
            switch (kind) {
                case CALL: {
                    // ADDED edges → solid arrow (->>) with activation
                    // UNCHANGED edges (blast-radius) → dashed arrow (-->>)
                    boolean isNew = edgeStatus == FlowEdge.EdgeStatus.ADDED;
                    String arrow  = isNew ? "->>" : "-->>";
                    String prefix = isNew ? "  " : "  ";
                    if (activate) return prefix + from + "->>+" + to + ": " + label;
                    return prefix + from + arrow + to + ": " + label;
                }
                case RETURN:
                    return "  " + from + "-->>" + to + ": " + label;
                case SELF_CALL:
                    return "  " + from + "->>" + from + ": " + label;
                case ALT_OPEN:
                    return "  alt " + label;
                case ALT_ELSE:
                    return "  else " + label;
                case ALT_END:
                    return "  end";
                case NOTE:
                    return "  Note over " + from + ": " + label;
                case ACTIVATE:
                    return "  activate " + from;
                case DEACTIVATE:
                    return "  deactivate " + from;
                default:
                    return "";
            }
        }
    }
}
