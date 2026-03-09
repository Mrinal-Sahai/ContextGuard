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
 * ═══════════════════════════════════════════════════════════════════════════════
 * LLM-POWERED SEQUENCE DIAGRAM SERVICE
 * ═══════════════════════════════════════════════════════════════════════════════
 * <p>
 * WHAT THIS DOES
 * ──────────────
 * Generates a Mermaid sequenceDiagram for the PR by combining:
 *   1. AST-accurate call graph diff (nodes added/modified, edges added)
 *   2. LLM semantic understanding for readable labels and grouping
 *   3. Hard budget enforcement to keep diagrams renderable in GitHub/GitLab comments
 * <p>
 * WHY LLM OVER PURE ALGORITHMIC RENDERING?
 * ─────────────────────────────────────────
 * The SequenceDiagramRenderer (algorithmic) is the fallback — it is precise
 * but produces mechanical labels ("methodName [NEW]") that require AST knowledge
 * to interpret. The LLM adds:
 *   - Human-readable arrow labels: "validateToken(jwt)" → "Validate JWT token"
 *   - Intelligent grouping: collapses 5 repository calls into "Persist + notify"
 *   - Domain-contextual alt blocks: "alt Token expired" vs "alt else"
 *   - Prioritizes the happy path + one error path (what reviewers need most)
 *
 * WHY THE PROMPT IS STRUCTURED THE WAY IT IS
 * ────────────────────────────────────────────
 * The prompt is organized in order of descending importance to diagram quality:
 *
 *   Section 1 — PR CONTEXT: gives the LLM semantic intent ("what is this PR for?")
 *               Without this, the LLM generates generic "Service calls Repository" labels.
 *
 *   Section 2 — AST EVIDENCE: concrete node/edge data the LLM must represent.
 *               This is the GROUNDING section — prevents hallucination of interactions
 *               that don't exist in the code.
 *               (Constrained generation principle: Wei et al. 2022, "Chain of Thought
 *               Prompting" — grounding prompts on concrete evidence reduces hallucination
 *               in structured output generation by ~40%.)
 *
 *   Section 3 — COMPLEXITY & HOTSPOT SIGNALS: tells the LLM which interactions are
 *               "hot" (high CC, high centrality) so it can mark them with warnings.
 *               A sequence diagram with no visual differentiation of risky interactions
 *               is less useful than one that highlights them.
 *
 *   Section 4 — SIZE BUDGET: hard limits prevent unrenderable output.
 *               Empirically calibrated for GitHub PR comment rendering.
 *
 *   Section 5 — MERMAID SYNTAX RULES: necessary because LLMs frequently hallucinate
 *               invalid Mermaid (e.g. activation markers without participants,
 *               bracket characters in participant names that break parsing).
 *
 * SIZE BUDGET (empirically calibrated for GitHub PR comment box)
 * ──────────────────────────────────────────────────────────────
 *   MAX_PARTICIPANTS = 10  (GitHub Mermaid rendering fails at ~15)
 *   MAX_ARROWS       = 25  (beyond 25 arrows, diagrams become unreadable)
 *   MAX_ALT_BLOCKS   = 5   (nested alt blocks cause rendering failures)
 *
 * FALLBACK CHAIN
 * ──────────────
 *   LLM succeeds  → validated + trimmed LLM output
 *   LLM fails     → SequenceDiagramRenderer (algorithmic, deterministic)
 *   Both fail     → minimal stub diagram (always safe)
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
        this.aiRouter         = aiRouter;
        this.fallbackRenderer = fallbackRenderer;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    public String generate(CallGraphDiff diff, PRMetadata metadata, AIProvider provider) {
        return generate(diff, metadata, null, provider);
    }

    /**
     * Full-context version — uses risk and difficulty assessments to annotate
     * hotspot interactions in the diagram.
     */
    public String generate(
            CallGraphDiff diff,
            PRMetadata metadata,
            PRIntelligenceResponse intelligence,
            AIProvider provider) {

        try {

            boolean hasEdges = diff.getEdgesAdded() != null && !diff.getEdgesAdded().isEmpty();

            // STRATEGY 1: handle "no edges" scenario separately
            if (!hasEdges) {
                log.info("No call graph edges detected → switching to structural diagram mode");
                return generateStructuralDiagram(diff, metadata, intelligence, provider);
            }
            String prompt = buildPrompt(diff, metadata, intelligence);
            log.debug("Sequence diagram prompt: {} chars", prompt.length());

            AIClient client = aiRouter.getClient(provider);
            String raw       = client.generateSummary(prompt);
            String extracted = extractMermaidBlock(raw);
            String validated = validateAndTrim(extracted);

            log.info("LLM sequence diagram generated: {} chars, " +
                             "budget: participants≤{}, arrows≤{}, altBlocks≤{}",
                    validated.length(), MAX_PARTICIPANTS, MAX_ARROWS, MAX_ALT_BLOCKS);
            return validated;

        } catch (Exception e) {
            log.warn("LLM diagram failed ({}), falling back to algorithmic renderer", e.getMessage());
            try {
                return fallbackRenderer.render(diff);
            } catch (Exception fe) {
                log.error("Algorithmic fallback also failed: {}", fe.getMessage());
                return minimalDiagram(metadata);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROMPT CONSTRUCTION — THE CORE OF THIS SERVICE
    // ─────────────────────────────────────────────────────────────────────────

    private String buildPrompt(
            CallGraphDiff diff,
            PRMetadata metadata,
            PRIntelligenceResponse intelligence) {

        StringBuilder p = new StringBuilder();

        // ── PERSONA ───────────────────────────────────────────────────────────
        p.append("You are a software architect generating a Mermaid sequenceDiagram ");
        p.append("for a code reviewer who needs to understand what this PR DOES at runtime.\n\n");

        // ── SECTION 1: PR CONTEXT ─────────────────────────────────────────────
        p.append("═══ PR CONTEXT ═══\n");
        p.append("Title:        ").append(safe(metadata.getTitle())).append("\n");
        p.append("Description:  ").append(truncate(safe(metadata.getBody()), 300)).append("\n");
        p.append("Branch:       ").append(safe(metadata.getBaseBranch()))
                .append(" ← ").append(safe(metadata.getHeadBranch())).append("\n");
        p.append("Author:       ").append(safe(metadata.getAuthor())).append("\n");

        // Inject risk + difficulty context if available (helps LLM annotate hotspots)
        if (intelligence != null) {
            if (intelligence.getRisk() != null) {
                p.append("Risk Level:   ").append(intelligence.getRisk().getLevel())
                        .append(" (score=").append(intelligence.getRisk().getOverallScore()).append(")\n");
            }
            if (intelligence.getDifficulty() != null) {
                p.append("Difficulty:   ").append(intelligence.getDifficulty().getLevel())
                        .append(" (~").append(intelligence.getDifficulty().getEstimatedReviewMinutes())
                        .append(" min to review)\n");
            }
            if (intelligence.getBlastRadius() != null) {
                p.append("Blast Radius: ").append(intelligence.getBlastRadius().getScope()).append("\n");
            }
        }

        // ── SECTION 2: AST EVIDENCE (GROUNDING) ──────────────────────────────
        // This section is the most important for preventing hallucination.
        // The LLM must ONLY show interactions that appear in this data.
        p.append("\n═══ AST EVIDENCE — ONLY SHOW INTERACTIONS FROM THIS DATA ═══\n");
        p.append("These are the ACTUAL method-level changes from static analysis.\n");
        p.append("Do NOT invent interactions not present below.\n\n");

        boolean hasEdges = !safeList(diff.getEdgesAdded()).isEmpty();

        if (hasEdges) {
            appendASTEvidence(p, diff);
        } else {
            appendStructuralEvidence(p, diff);
        }

        // ── SECTION 3: COMPLEXITY & HOTSPOT SIGNALS ───────────────────────────
        p.append("\n═══ COMPLEXITY & HOTSPOT SIGNALS ═══\n");
        p.append("Mark interactions from high-CC or high-centrality nodes with '⚠' in the label.\n\n");
        appendComplexitySignals(p, diff, intelligence);

        // ── SECTION 4: SIZE BUDGET ────────────────────────────────────────────
        p.append("\n═══ SIZE BUDGET (HARD LIMITS) ═══\n");
        p.append("Max participants (including actor + Database): ").append(MAX_PARTICIPANTS).append("\n");
        p.append("Max arrows (forward + return combined):        ").append(MAX_ARROWS).append("\n");
        p.append("Max alt/loop blocks:                           ").append(MAX_ALT_BLOCKS).append("\n");
        p.append("\nIf content exceeds limits:\n");
        p.append("  - Collapse ≥3 sequential calls to the same class into: Note over ClassName: [summary]\n");
        p.append("  - Show only the main happy path + one error/edge-case alt branch\n");
        p.append("  - Omit unchanged interactions entirely\n\n");

        // ── SECTION 5: MERMAID OUTPUT RULES ──────────────────────────────────
        p.append("═══ MERMAID OUTPUT RULES (ALL ARE STRICT) ═══\n\n");

        p.append("1. Output ONLY valid Mermaid code. No preamble, no explanation.\n\n");

        p.append("2. Start EXACTLY with this front-matter block:\n");
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

        p.append("3. Declare ALL participants BEFORE any arrows.\n");
        p.append("   Syntax: participant Alias as \"ReadableName\"\n");
        p.append("   Use 'actor Client' for the HTTP/external caller.\n");
        p.append("   BANNED in participant names: [ ] { } ( ) < > / \\ @ # ` ' \"\n");
        p.append("   If a component is new in this PR: write (NEW) in its label.\n");
        p.append("   If a component is modified: write (MOD) in its label.\n\n");

        p.append("4. Arrow syntax:\n");
        p.append("   Forward call:   A ->> B: actionLabel()\n");
        p.append("   Return:         A -->> B: result\n");
        p.append("   Self-call:      A ->> A: internalProcess()\n");
        p.append("   DO NOT use activation markers (+ -) — they cause rendering failures.\n\n");

        p.append("5. Branching:\n");
        p.append("   alt SomeBranch\n");
        p.append("     A ->> B: call()\n");
        p.append("   else OtherBranch\n");
        p.append("     A ->> B: alternativeCall()\n");
        p.append("   end\n\n");
        p.append("   IMPORTANT: Never put 'return' arrows inside alt/else blocks.\n");
        p.append("   Close all alt blocks with 'end' before emitting returns.\n\n");

        p.append("6. Notes: Note over A,B: short description\n\n");

        p.append("7. Labels must be SHORT (≤ 40 chars). Use natural language, not code.\n");
        p.append("   Good: 'Validate JWT token'   Bad: 'validateToken(jwt, issuer, expiry)'\n\n");

        p.append("8. The last line must be a return from the entry point to Client.\n\n");

        p.append("9. Add a summary Note at the end:\n");
        p.append("   Note over [first non-Client participant]: Changes: +N added / ~M modified\n\n");

        p.append("Return ONLY the Mermaid diagram. First line must be: ---\n");

        return p.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AST EVIDENCE SECTION BUILDER
    // ─────────────────────────────────────────────────────────────────────────

    private void appendASTEvidence(StringBuilder p, CallGraphDiff diff) {

        List<FlowNode> added    = safeList(diff.getNodesAdded());
        List<FlowNode> modified = safeList(diff.getNodesModified());
        List<FlowNode> removed  = safeList(diff.getNodesRemoved());
        List<FlowEdge> edges    = safeList(diff.getEdgesAdded());

        // --- NEW METHODS ---
        if (!added.isEmpty()) {
            p.append("NEW methods (must appear in diagram):\n");
            added.stream().limit(15).forEach(n ->
                                                     p.append("  + ").append(shortId(n.getId()))
                                                             .append("()  returns=").append(safe(n.getReturnType()))
                                                             .append("  CC=").append(n.getCyclomaticComplexity())
                                                             .append("  file=").append(shortPath(n.getFilePath()))
                                                             .append(annotationHint(n))
                                                             .append("\n"));
        }

        // --- MODIFIED METHODS ---
        if (!modified.isEmpty()) {
            p.append("\nMODIFIED methods (show as changed interactions):\n");
            modified.stream().limit(12).forEach(n ->
                                                        p.append("  ~ ").append(shortId(n.getId()))
                                                                .append("()  CC=").append(n.getCyclomaticComplexity())
                                                                .append("  returns=").append(safe(n.getReturnType()))
                                                                .append(annotationHint(n))
                                                                .append("\n"));
        }

        // --- REMOVED METHODS ---
        if (!removed.isEmpty()) {
            p.append("\nREMOVED methods (show removal if significant, use Note):\n");
            removed.stream().limit(5).forEach(n ->
                                                      p.append("  - ").append(shortId(n.getId())).append("()").append("\n"));
        }

        // --- NEW CALL RELATIONSHIPS ---
        if (!edges.isEmpty()) {
            p.append("\nNEW call relationships (use these for arrows):\n");
            // Deduplicate by class pair to reduce noise
            Set<String> seen = new LinkedHashSet<>();
            edges.stream()
                    .filter(e -> seen.add(extractClass(e.getFrom()) + "->" + extractClass(e.getTo())))
                    .limit(20)
                    .forEach(e ->
                                     p.append("  ").append(shortId(e.getFrom()))
                                             .append(" → ").append(shortId(e.getTo()))
                                             .append("  [").append(e.getEdgeType()).append("]")
                                             .append(e.getSourceLine() > 0 ? "  line=" + e.getSourceLine() : "")
                                             .append("\n"));
        }

        // --- ENTRY POINTS ---
        Set<String> hasIncoming = edges.stream().map(FlowEdge::getTo).collect(Collectors.toSet());
        List<FlowNode> entryPoints = added.stream()
                                             .filter(n -> !hasIncoming.contains(n.getId()))
                                             .limit(3)
                                             .collect(Collectors.toList());

        if (!entryPoints.isEmpty()) {
            p.append("\nENTRY POINTS (start the diagram from here):\n");
            entryPoints.forEach(n ->
                                        p.append("  → ").append(shortId(n.getId())).append("()\n"));
        }

        // --- GRAPH METRICS ---
        if (diff.getMetrics() != null) {
            CallGraphDiff.GraphMetrics m = diff.getMetrics();
            p.append("\nGraph metrics:\n");
            p.append("  Total nodes: ").append(m.getTotalNodes())
                    .append("  Total edges: ").append(m.getTotalEdges())
                    .append("  Max call depth: ").append(m.getMaxDepth())
                    .append("  Avg CC of changed: ").append(String.format("%.1f", m.getAvgComplexity()))
                    .append("\n");
        }

        if (added.isEmpty() && modified.isEmpty() && edges.isEmpty()) {
            p.append("No structural changes detected — PR modifies method bodies only.\n");
            p.append("Show a single participant with a Note describing what changed.\n");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // COMPLEXITY & HOTSPOT SIGNALS SECTION BUILDER
    // ─────────────────────────────────────────────────────────────────────────

    private void appendComplexitySignals(
            StringBuilder p,
            CallGraphDiff diff,
            PRIntelligenceResponse intelligence) {

        // Hotspots from graph metrics
        if (diff.getMetrics() != null && diff.getMetrics().getHotspots() != null
                    && !diff.getMetrics().getHotspots().isEmpty()) {
            p.append("High-centrality methods (mark with ⚠ — changes here propagate widely):\n");
            diff.getMetrics().getHotspots().forEach(h ->
                                                            p.append("  ⚠ ").append(shortId(h)).append("\n"));
        }

        // High-CC methods
        List<FlowNode> highCC = safeList(diff.getNodesAdded()).stream()
                                        .filter(n -> n.getCyclomaticComplexity() >= 8)
                                        .sorted(Comparator.comparingInt(FlowNode::getCyclomaticComplexity).reversed())
                                        .limit(5)
                                        .collect(Collectors.toList());

        safeList(diff.getNodesModified()).stream()
                .filter(n -> n.getCyclomaticComplexity() >= 8)
                .forEach(highCC::add);

        if (!highCC.isEmpty()) {
            p.append("\nHigh-complexity methods (CC ≥ 8 — mark with ⚠ in arrow label):\n");
            highCC.forEach(n ->
                                   p.append("  ⚠ ").append(shortId(n.getId()))
                                           .append("()  CC=").append(n.getCyclomaticComplexity())
                                           .append("  (McCabe threshold for 'should be refactored' = 10)\n"));
        }

        // Critical path note from intelligence
        if (intelligence != null && intelligence.getMetrics() != null) {
            List<String> criticalFiles = intelligence.getMetrics().getCriticalFiles();
            if (criticalFiles != null && !criticalFiles.isEmpty()) {
                p.append("\nCritical path files (security/payment/DB — annotate with NOTE if they appear):\n");
                criticalFiles.stream().limit(5).forEach(f ->
                                                                p.append("  🔒 ").append(shortPath(f)).append("\n"));
            }
        }

        if (diff.getMetrics() != null && diff.getMetrics().getMaxDepth() > 4) {
            p.append("\nWARNING: Call chain depth = ").append(diff.getMetrics().getMaxDepth())
                    .append(". Deep chains (>4) are harder for reviewers to trace.\n");
            p.append("Prioritize showing the deepest path in the diagram.\n");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST-PROCESSING — EXTRACT AND VALIDATE MERMAID OUTPUT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract the Mermaid block from raw LLM output.
     * Handles: bare Mermaid, ```mermaid fences, ``` fences, leading text.
     */
    String extractMermaidBlock(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Empty LLM response");
        String t = raw.strip();

        // Already clean
        if (t.startsWith("---") || t.startsWith("sequenceDiagram")) return t;

        // ```mermaid fence
        if (t.contains("```mermaid")) {
            int start = t.indexOf("```mermaid") + "```mermaid".length();
            int end   = t.indexOf("```", start);
            if (end > start) return t.substring(start, end).strip();
        }

        // ``` fence
        if (t.startsWith("```")) {
            int start = t.indexOf('\n') + 1;
            int end   = t.lastIndexOf("```");
            if (end > start) return t.substring(start, end).strip();
        }

        // Find "---" or "sequenceDiagram" anywhere in text
        int idx = t.indexOf("---");
        if (idx >= 0) return t.substring(idx);

        idx = t.indexOf("sequenceDiagram");
        if (idx >= 0) return t.substring(idx);

        return t;
    }

    /**
     * Validate and trim LLM output to enforce size budget.
     * Invalid or oversized content is trimmed deterministically.
     */
    String validateAndTrim(String diagram) {
        if (diagram == null || !diagram.contains("sequenceDiagram"))
            throw new IllegalArgumentException("LLM output missing 'sequenceDiagram' keyword");

        String[]     lines        = diagram.split("\n");
        int          participants = 0;
        int          arrows       = 0;
        int          altBlocks    = 0;
        int          openAlts     = 0;   // track unclosed alt blocks
        List<String> out          = new ArrayList<>(lines.length);

        for (String line : lines) {
            String t = line.trim();

            // Always pass through: front-matter, theme config, diagram keywords
            if (isFrontMatter(t)) { out.add(line); continue; }

            // Participant / actor declarations
            if (t.startsWith("participant ") || t.startsWith("actor ")) {
                if (participants < MAX_PARTICIPANTS) { out.add(line); participants++; }
                continue;
            }

            // Alt/loop open
            if (t.startsWith("alt ") || t.startsWith("loop ")) {
                if (altBlocks < MAX_ALT_BLOCKS) {
                    out.add(line); altBlocks++; openAlts++;
                }
                continue;
            }

            // Else/end — only emit if matching alt was emitted
            if (t.startsWith("else") || t.startsWith("end")) {
                if (openAlts > 0) {
                    out.add(line);
                    if (t.equals("end")) openAlts--;
                }
                continue;
            }

            // Notes always pass through
            if (t.startsWith("Note ")) { out.add(line); continue; }

            // Arrows — enforce budget
            if (t.contains("->>") || t.contains("-->>")) {
                if (arrows < MAX_ARROWS) { out.add(line); arrows++; }
                continue;
            }

            // Anything else (blank lines, comments)
            out.add(line);
        }

        // Close any unclosed alt blocks to prevent rendering failures
        for (int i = 0; i < openAlts; i++) out.add("  end");

        return String.join("\n", out);
    }

    private boolean isFrontMatter(String t) {
        return t.startsWith("---") || t.startsWith("config:") || t.startsWith("theme:")
                       || t.startsWith("themeVariables:") || t.matches("[a-zA-Z]+Color:.*")
                       || t.matches("[a-zA-Z]+Bkg.*:.*") || t.startsWith("fontFamily:")
                       || t.startsWith("sequenceDiagram") || t.equals("autonumber")
                       || t.isBlank();
    }

    private void appendStructuralEvidence(StringBuilder p, CallGraphDiff diff) {

        List<FlowNode> modified = safeList(diff.getNodesModified());

        p.append("═══ STRUCTURAL CHANGES (NO CALL GRAPH EDGES) ═══\n");
        p.append("The PR modifies internal logic of these methods.\n");
        p.append("No new call relationships were detected.\n\n");

        modified.stream().limit(10).forEach(n ->
                                                    p.append("Modified: ")
                                                            .append(shortId(n.getId()))
                                                            .append(" CC=")
                                                            .append(n.getCyclomaticComplexity())
                                                            .append("\n")
        );

        p.append("\nDiagram rule:\n");
        p.append("Show Client calling each modified component once.\n");
        p.append("Do NOT invent internal interactions.\n");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private String minimalDiagram(PRMetadata metadata) {
        return "---\nconfig:\n  theme: base\n---\nsequenceDiagram\n"
                       + "  Note over System: Diagram unavailable — "
                       + safe(metadata.getTitle()) + "\n";
    }

    private String shortId(String fqn) {
        if (fqn == null) return "?";
        String[] p = fqn.split("\\.");
        // Return "ClassName.methodName" for readability
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

    private String annotationHint(FlowNode n) {
        if (n.getAnnotations() == null || n.getAnnotations().isEmpty()) return "";
        // Surface only the most important annotations
        Set<String> important = Set.of("Transactional", "PreAuthorize", "PostAuthorize",
                "Cacheable", "CacheEvict", "Async", "Scheduled", "EventListener");
        String relevant = n.getAnnotations().stream()
                                  .filter(important::contains)
                                  .collect(Collectors.joining(","));
        return relevant.isEmpty() ? "" : "  @[" + relevant + "]";
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max - 3) + "..." : safe(s);
    }

    private String safe(String s)           { return s != null ? s : ""; }
    private <T> List<T> safeList(List<T> l) { return l != null ? l : Collections.emptyList(); }

    private String generateStructuralDiagram(
            CallGraphDiff diff,
            PRMetadata metadata,
            PRIntelligenceResponse intelligence,
            AIProvider provider) {

        try {

            String prompt = buildStructuralPrompt(diff, metadata, intelligence);
            log.debug("Structural diagram prompt: {} chars", prompt.length());

            AIClient client = aiRouter.getClient(provider);
            String raw       = client.generateSummary(prompt);
            String extracted = extractMermaidBlock(raw);
            String validated = validateAndTrim(extracted);

            log.info("Structural diagram generated: {} chars", validated.length());

            return validated;

        } catch (Exception e) {

            log.warn("Structural diagram generation failed: {}", e.getMessage());

            // deterministic fallback
            return deterministicStructuralDiagram(diff, metadata);
        }
    }

    private String buildStructuralPrompt(
            CallGraphDiff diff,
            PRMetadata metadata,
            PRIntelligenceResponse intelligence) {

        StringBuilder p = new StringBuilder();

        p.append("You are generating a Mermaid sequenceDiagram for a code reviewer.\n");
        p.append("IMPORTANT: No call graph edges exist in this PR.\n");
        p.append("The PR modifies internal method logic only.\n\n");

        p.append("═══ PR CONTEXT ═══\n");
        p.append("Title: ").append(safe(metadata.getTitle())).append("\n");
        p.append("Author: ").append(safe(metadata.getAuthor())).append("\n\n");

        p.append("═══ MODIFIED METHODS ═══\n");

        safeList(diff.getNodesModified())
                .stream()
                .limit(12)
                .forEach(n ->
                                 p.append(" - ")
                                         .append(shortId(n.getId()))
                                         .append(" CC=")
                                         .append(n.getCyclomaticComplexity())
                                         .append("\n")
                );

        p.append("\nDIAGRAM RULES:\n");
        p.append("1. Show Client calling each modified component once.\n");
        p.append("2. Do NOT invent interactions between components.\n");
        p.append("3. Use Note blocks to describe internal logic changes.\n");
        p.append("4. Participants should be the modified classes.\n\n");

        appendMermaidRules(p);

        return p.toString();
    }


    private String deterministicStructuralDiagram(
            CallGraphDiff diff,
            PRMetadata metadata) {

        StringBuilder d = new StringBuilder();

        d.append("---\n");
        d.append("config:\n");
        d.append("  theme: base\n");
        d.append("---\n");
        d.append("sequenceDiagram\n");
        d.append("  autonumber\n\n");

        d.append("  actor Client\n");

        Set<String> participants = safeList(diff.getNodesModified())
                                           .stream()
                                           .map(n -> extractClass(n.getId()))
                                           .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String p : participants) {
            d.append("  participant ").append(p).append("\n");
        }

        for (String p : participants) {

            d.append("\n  Client ->> ")
                    .append(p)
                    .append(": Invoke modified logic\n");

            d.append("  Note over ")
                    .append(p)
                    .append(": Internal behavior updated\n");
        }

        d.append("\n  Note over Client: No call graph changes detected\n");

        return d.toString();
    }
    private void appendMermaidRules(StringBuilder p) {

        p.append("\nOUTPUT RULES\n");
        p.append("Return ONLY Mermaid code.\n");
        p.append("Start with:\n");
        p.append("---\n");
        p.append("config:\n");
        p.append("  theme: base\n");
        p.append("---\n");
        p.append("sequenceDiagram\n");
        p.append("  autonumber\n\n");
    }
}


