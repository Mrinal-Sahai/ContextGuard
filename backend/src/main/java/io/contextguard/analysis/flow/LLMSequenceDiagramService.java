package io.contextguard.analysis.flow;

import io.contextguard.client.AIClient;
import io.contextguard.client.AIProvider;
import io.contextguard.client.AIRouter;
import io.contextguard.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM-POWERED SEQUENCE DIAGRAM SERVICE
 *
 * Generates compact, readable Mermaid sequenceDiagram output by combining:
 *  1. Structured diff context (changed nodes, new edges, entry points)
 *  2. LLM semantic understanding (grouping trivial calls, choosing labels)
 *  3. Hard size-budget enforcement (validateAndTrim) to prevent unrenderable output
 *
 * SIZE BUDGET (empirically calibrated for GitHub PR comment rendering):
 *   MAX_PARTICIPANTS = 8
 *   MAX_ARROWS       = 20  (forward + return combined)
 *   MAX_ALT_BLOCKS   = 3
 *
 * FALLBACK CHAIN:
 *   LLM success  => validated + trimmed LLM output
 *   LLM failure  => SequenceDiagramRenderer (algorithmic fallback)
 *   Both fail    => minimal stub diagram
 */
@Service
public class LLMSequenceDiagramService {

    private static final Logger log = LoggerFactory.getLogger(LLMSequenceDiagramService.class);

    static final int MAX_PARTICIPANTS = 10;
    static final int MAX_ARROWS       = 25;
    static final int MAX_ALT_BLOCKS   = 5;

    private final AIRouter aiRouter;
    private final SequenceDiagramRenderer fallbackRenderer;

    public LLMSequenceDiagramService(AIRouter aiRouter, SequenceDiagramRenderer fallbackRenderer) {
        this.aiRouter = aiRouter;
        this.fallbackRenderer = fallbackRenderer;
    }

    // -------------------------------------------------------------------------
    // PUBLIC API
    // -------------------------------------------------------------------------

    public String generate(CallGraphDiff diff, PRMetadata metadata, AIProvider provider) {
        try {
            String prompt = buildPrompt(diff, metadata);
            System.out.println("LLM prompt for MERMAID DIAGRAM GENERATION:");
            System.out.println(prompt);
            AIClient client = aiRouter.getClient(provider);
            String raw = client.generateSummary(prompt);
            String extracted = extractMermaidBlock(raw);
            String validated = validateAndTrim(extracted);
            log.info("LLM sequence diagram: {} chars", validated.length());
            return validated;
        } catch (Exception e) {
            log.warn("LLM diagram failed ({}), using algorithmic fallback", e.getMessage());
            try {
                return fallbackRenderer.render(diff);
            } catch (Exception fe) {
                log.error("Fallback renderer also failed: {}", fe.getMessage());
                return minimalDiagram(metadata);
            }
        }
    }

    // -------------------------------------------------------------------------
    // PROMPT
    // -------------------------------------------------------------------------

    private String buildPrompt(CallGraphDiff diff, PRMetadata metadata) {
        String ctx = buildDiffContext(diff);
        StringBuilder p = new StringBuilder();

        p.append("You are a software architecture expert. Generate a valid Mermaid sequenceDiagram ");
        p.append("for the code change described below.\n\n");

        p.append("PR: ").append(safe(metadata.getTitle())).append("\n");
        p.append("Branch: ").append(safe(metadata.getBaseBranch()))
                .append(" -> ").append(safe(metadata.getHeadBranch())).append("\n\n");

        p.append("CALL GRAPH CHANGES:\n").append(ctx).append("\n\n");

        p.append("SIZE BUDGET (HARD LIMITS):\n");
        p.append("Max participants: ").append(MAX_PARTICIPANTS).append("\n");
        p.append("Max arrows: ").append(MAX_ARROWS).append("\n");
        p.append("Max alt blocks: ").append(MAX_ALT_BLOCKS).append("\n\n");

        p.append("If interactions exceed limits:\n");
        p.append("- Collapse helper calls into Note blocks\n");
        p.append("- Merge related calls into a single arrow\n");
        p.append("- Show only main happy path and one error alt branch\n\n");

        p.append("MERMAID OUTPUT RULES (STRICT):\n");

        p.append("1. Output ONLY Mermaid code. No explanations.\n");

        p.append("2. Start exactly with:\n");
        p.append("---\n");
        p.append("config:\n");
        p.append("  theme: base\n");
        p.append("  themeVariables:\n");
        p.append("    primaryColor: \"#EFF6FF\"\n");
        p.append("    primaryTextColor: \"#1E3A5F\"\n");
        p.append("    primaryBorderColor: \"#2E86AB\"\n");
        p.append("    activationBkgColor: \"#F0FDF4\"\n");
        p.append("    noteBkgColor: \"#FEF9C3\"\n");
        p.append("    noteTextColor: \"#78350F\"\n");
        p.append("    fontFamily: \"Arial, sans-serif\"\n");
        p.append("---\n");
        p.append("sequenceDiagram\n");
        p.append("  autonumber\n\n");

        p.append("3. Declare ALL participants before arrows.\n");
        p.append("Use syntax: participant Alias as \"ReadableName\".\n");
        p.append("Use 'actor Client' for external caller.\n");
        p.append("DO NOT use brackets [], braces {}, or special characters in names.\n");
        p.append("If a component is new or changed, write '(NEW)' or '(CHANGED)' inside the label.\n\n");

        p.append("""
                    4. Arrow syntax:
                    A ->> B: method()
                    A -->> B: return
                    
                    Avoid activation markers (+ and -).
                    Use simple arrows unless absolutely necessary.
                    
                    If an interaction spans an alt block, return after the end block.
                    Do not close interactions inside alt/else branches.
                    3""");

        p.append("5. Control flow:\n");
        p.append("Use alt / else / end for branching.\n");
        p.append("Use 'Note over A,B: text' to summarize minor operations.\n\n");

        p.append("6. Keep labels short and simple. Avoid special characters.\n\n");
        p.append("7. Beautify the diagram by adding details, colors, and arrows and it must look easily understandable and expressive, and not be too long. Prioritise important details"+"\n");


        p.append("Return ONLY the Mermaid diagram. The first line must be exactly: ---\n");

        return p.toString();
    }

    private String buildDiffContext(CallGraphDiff diff) {
        StringBuilder sb = new StringBuilder();

        List<FlowNode> added = safeList(diff.getNodesAdded());
        if (!added.isEmpty()) {
            sb.append("NEW methods:\n");
            added.stream().limit(12).forEach(n ->
                                                     sb.append("  + ").append(shortId(n.getId()))
                                                             .append("()  cc=").append(n.getCyclomaticComplexity())
                                                             .append("  returns=").append(safe(n.getReturnType()))
                                                             .append("  file=").append(shortPath(n.getFilePath()))
                                                             .append("\n"));
        }

        List<FlowNode> modified = safeList(diff.getNodesModified());
        if (!modified.isEmpty()) {
            sb.append("\nMODIFIED methods:\n");
            modified.stream().limit(10).forEach(n ->
                                                        sb.append("  ~ ").append(shortId(n.getId()))
                                                                .append("()  cc=").append(n.getCyclomaticComplexity()).append("\n"));
        }

        List<FlowEdge> addedEdges = safeList(diff.getEdgesAdded());
        if (!addedEdges.isEmpty()) {
            sb.append("\nNEW call relationships:\n");
            Set<String> seen = new LinkedHashSet<>();
            addedEdges.stream()
                    .filter(e -> seen.add(extractClass(e.getFrom()) + "->" + extractClass(e.getTo())))
                    .limit(20)
                    .forEach(e ->
                                     sb.append("  ").append(shortId(e.getFrom()))
                                             .append(" -> ").append(shortId(e.getTo()))
                                             .append("  [").append(e.getEdgeType()).append("]\n"));
        }

        Set<String> hasIncoming = addedEdges.stream().map(FlowEdge::getTo).collect(Collectors.toSet());
        added.stream()
                .filter(n -> !hasIncoming.contains(n.getId()))
                .limit(2)
                .forEach(n -> sb.append("\nENTRY POINT: ").append(shortId(n.getId())).append("\n"));

        if (diff.getMetrics() != null) {
            CallGraphDiff.GraphMetrics m = diff.getMetrics();
            sb.append("\nMetrics: ").append(m.getTotalNodes()).append(" nodes, ")
                    .append(m.getTotalEdges()).append(" edges, max depth=").append(m.getMaxDepth())
                    .append(", avg cc=").append(String.format("%.1f", m.getAvgComplexity())).append("\n");
        }

        if (sb.length() == 0) {
            sb.append("No structural changes. PR modifies method bodies only.\n");
            sb.append("Show a single participant Note describing what changed.\n");
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // POST-PROCESSING
    // -------------------------------------------------------------------------

    String extractMermaidBlock(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Empty LLM response");
        String t = raw.strip();
        if (t.startsWith("---") || t.startsWith("sequenceDiagram")) return t;
        if (t.contains("```mermaid")) {
            int start = t.indexOf("```mermaid") + 10;
            int end = t.indexOf("```", start);
            if (end > start) return t.substring(start, end).strip();
        }
        if (t.startsWith("```")) {
            int start = t.indexOf('\n') + 1;
            int end = t.lastIndexOf("```");
            if (end > start) return t.substring(start, end).strip();
        }
        return t;
    }

    String validateAndTrim(String diagram) {
        if (diagram == null || !diagram.contains("sequenceDiagram"))
            throw new IllegalArgumentException("LLM output missing sequenceDiagram");

        String[] lines = diagram.split("\n");
        int participants = 0, arrows = 0, altBlocks = 0;
        List<String> out = new ArrayList<>();

        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("---") || t.startsWith("config:") || t.startsWith("theme:")
                        || t.startsWith("themeVariables:") || t.matches("[a-zA-Z]+Color:.*")
                        || t.startsWith("fontFamily:") || t.equals("sequenceDiagram")
                        || t.equals("autonumber") || t.isBlank()) {
                out.add(line); continue;
            }
            if (t.startsWith("participant ") || t.startsWith("actor ")) {
                if (participants < MAX_PARTICIPANTS) { out.add(line); participants++; }
                continue;
            }
            if (t.startsWith("alt ") || t.startsWith("loop ")) {
                if (altBlocks < MAX_ALT_BLOCKS) { out.add(line); altBlocks++; }
                continue;
            }
            if (t.startsWith("else") || t.equals("end")) { out.add(line); continue; }
            if (t.startsWith("Note ")) { out.add(line); continue; }
            if (t.contains("->>") || t.contains("-->>")) {
                if (arrows < MAX_ARROWS) { out.add(line); arrows++; }
                continue;
            }
            out.add(line);
        }
        return String.join("\n", out);
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private String minimalDiagram(PRMetadata metadata) {
        return "---\nconfig:\n  theme: base\n---\nsequenceDiagram\n"
                       + "  Note over System: Diagram unavailable for: " + safe(metadata.getTitle()) + "\n";
    }

    private String shortId(String fqn) {
        if (fqn == null) return "?";
        String[] p = fqn.split("\\.");
        return p.length >= 2 ? p[p.length - 2] + "." + p[p.length - 1] : fqn;
    }

    private String extractClass(String fqn) {
        if (fqn == null) return "?";
        String[] p = fqn.split("\\.");
        return p.length >= 2 ? p[p.length - 2] : fqn;
    }

    private String shortPath(String path) {
        if (path == null) return "";
        String[] p = path.split("/");
        return p[p.length - 1];
    }

    private String safe(String s) { return s != null ? s : ""; }
    private <T> List<T> safeList(List<T> list) { return list != null ? list : Collections.emptyList(); }
}
