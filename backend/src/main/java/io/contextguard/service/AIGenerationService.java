package io.contextguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contextguard.analysis.flow.CallGraphDiff;
import io.contextguard.analysis.flow.FlowEdge;
import io.contextguard.analysis.flow.FlowNode;
import io.contextguard.client.AIClient;
import io.contextguard.client.AIProvider;
import io.contextguard.client.AIRouter;
import io.contextguard.dto.*;
import io.contextguard.engine.DiffParser;
import io.contextguard.engine.DifficultyScoringEngine;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * AI NARRATIVE GENERATION SERVICE — AST-ENRICHED VERSION
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * WHAT CHANGED FROM THE PREVIOUS VERSION
 * ─────────────────────────────────────────
 * The previous prompt had the right structure but was missing the most
 * actionable data that AST parsing now provides. Specifically:
 *
 * 1. ANNOTATION CHANGES were invisible.
 *    @Transactional removed, @PreAuthorize added, @Cacheable changed —
 *    these are major behavioral changes that zero lines of diff may show.
 *    Now surfaced explicitly in the BEHAVIORAL_SIGNALS section.
 *
 * 2. REMOVED PUBLIC METHODS were not flagged.
 *    A removed non-void method = a breaking API change. This is a CHECKLIST
 *    item the reviewer must verify (are all callers updated?).
 *    Now surfaced from DiffMetrics.removedPublicMethods (fed by FlowExtractorService).
 *
 * 3. HOTSPOT METHODS were buried in call graph metrics as raw IDs.
 *    Reviewers don't know what "io.contextguard.service.X.method" centrality
 *    means. Now translated into reviewer-facing language:
 *    "X.method is called by N other methods — a change here has wide blast radius."
 *
 * 4. CALL DEPTH was a number with no context.
 *    Max depth = 6 means nothing to a reviewer. Now translated:
 *    "The deepest call chain introduced is 6 hops: A→B→C→D→E→F.
 *     Reviewers must trace 6 method boundaries to fully understand this PR."
 *
 * 5. RISK BREAKDOWN was just a level (HIGH) with a score (0.63).
 *    Now the PRIMARY DRIVER is extracted from the breakdown and injected
 *    into the prompt so the AI can explain WHY it's high in RISK_INTERPRETATION.
 *
 * 6. POST-AST RESCORING: generateSummary() now accepts post-AST risk and
 *    difficulty assessments. If the caller passes updated assessments (after
 *    FlowExtractorService has fed back AST metrics), those replace the
 *    heuristic values in the prompt.
 *
 * WHY THE PROMPT IS STRUCTURED IN THIS ORDER
 * ────────────────────────────────────────────
 * LLM attention is not uniform across a prompt. Research (Liu et al. 2023,
 * "Lost in the Middle") showed that LLMs pay most attention to content at the
 * START and END of a prompt, and systematically under-attend to content in the
 * middle. Our prompt structure accounts for this:
 *
 *   START  → PR context + quantitative metrics (always attended)
 *   MIDDLE → Evidence (file, method, call graph, annotations)
 *   END    → Output schema + strict rules (always attended)
 *
 * The most important constraints (ONLY reference CHANGED_NODES, return JSON only)
 * appear in BOTH the persona statement at the top AND in STRICT_RULES at the
 * bottom, exploiting this primacy + recency bias.
 */
@Service
public class AIGenerationService {

    private final AIRouter aiRouter;
    private final DiffParser diffParser;
    private final RiskScoringEngine riskScoringEngine;
    private final DifficultyScoringEngine difficultyEngine;

    public AIGenerationService(
            AIRouter aiRouter,
            DiffParser diffParser,
            RiskScoringEngine riskScoringEngine,
            DifficultyScoringEngine difficultyEngine) {
        this.aiRouter          = aiRouter;
        this.diffParser        = diffParser;
        this.riskScoringEngine = riskScoringEngine;
        this.difficultyEngine  = difficultyEngine;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIMARY ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate AI narrative. Call this AFTER FlowExtractorService has fed back
     * AST metrics so that risk and difficulty reflect accurate complexity data.
     */
    public AIGeneratedNarrative generateSummary(
            List<GitHubFile> files,
            PRMetadata metadata,
            DiffMetrics metrics,
            RiskAssessment risk,
            DifficultyAssessment difficulty,
            BlastRadiusAssessment blastRadius,
            CallGraphDiff callGraph,
            AIProvider provider) {

        // POST-AST RESCORING:
        // If AST has fed back accurate complexityDelta into metrics,
        // recompute risk and difficulty with the better data.
        // This ensures the AI narrative describes the accurate scores, not heuristic ones.
        RiskAssessment      finalRisk       = rescore(metrics, risk, metadata);
        DifficultyAssessment finalDifficulty = rescoreDifficulty(metrics, difficulty, metadata);

        String prompt = buildPrompt(
                metadata, metrics, finalRisk, finalDifficulty, blastRadius, files, callGraph);

        try {
            AIClient client   = aiRouter.getClient(provider);
            String aiResponse = client.generateSummary(prompt);
            String json       = extractJsonObject(cleanJson(aiResponse));
            return parseJsonResponse(json);
        } catch (Exception e) {
            return fallbackNarrative(metadata, metrics);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST-AST RESCORING
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Re-run RiskScoringEngine if AST has updated DiffMetrics.complexityDelta.
     * Returns the original assessment unchanged if metrics are still heuristic.
     *
     * Why: RiskScoringEngine ran early (before AST) using a diff-line complexity
     * estimate. After FlowExtractorService.feedbackASTMetricsIntoDiffMetrics(),
     * DiffMetrics has accurate AST values. Re-running costs microseconds (no I/O).
     */
    private RiskAssessment rescore(DiffMetrics metrics, RiskAssessment original, PRMetadata metadata) {
        try {
            return riskScoringEngine.assessRisk(metadata, metrics);
        } catch (Exception e) {
            return original; // fall back gracefully
        }
    }

    private DifficultyAssessment rescoreDifficulty(
            DiffMetrics metrics, DifficultyAssessment original, PRMetadata metadata) {
        try {
            return difficultyEngine.assessDifficulty(metadata, metrics);
        } catch (Exception e) {
            return original;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROMPT CONSTRUCTION
    // ─────────────────────────────────────────────────────────────────────────

    private String buildPrompt(
            PRMetadata metadata,
            DiffMetrics metrics,
            RiskAssessment risk,
            DifficultyAssessment difficulty,
            BlastRadiusAssessment blastRadius,
            List<GitHubFile> files,
            CallGraphDiff callGraph) {

        List<FileChangeSummary> priorityFiles = selectPriorityFiles(metrics.getFileChanges(), 8);

        StringBuilder p = new StringBuilder();

        // ── PERSONA (primacy: always attended by LLM) ─────────────────────────
        p.append("You are a senior software engineer writing a precise, actionable PR review summary.\n");
        p.append("Base your output STRICTLY on the evidence provided below.\n");
        p.append("DO NOT invent behavior, calls, or risks not present in the data.\n");
        p.append("ONLY reference methods listed in CHANGED_METHODS. Never reference UNCHANGED_METHODS.\n\n");

        // ── SECTION 1: PR IDENTITY ─────────────────────────────────────────────
        p.append("\nPR CONTEXT\n");
        p.append("Title:       ").append(safe(metadata.getTitle())).append("\n");
//        p.append("Description: ").append(truncate(safe(metadata.getBody()), 400)).append("\n");
        p.append("Branch:      ").append(safe(metadata.getBaseBranch()))
                .append(" ← ").append(safe(metadata.getHeadBranch())).append("\n");
        p.append("Author:      ").append(safe(metadata.getAuthor())).append("\n\n");

        // ── SECTION 2: QUANTITATIVE METRICS (post-AST accurate) ───────────────
        p.append("\nQUANTITATIVE METRICS (AST-accurate after static analysis)\n");
        p.append("Files changed:          ").append(metrics.getTotalFilesChanged()).append("\n");
        p.append("Lines added:            ").append(metrics.getLinesAdded()).append("\n");
        p.append("Lines deleted:          ").append(metrics.getLinesDeleted()).append("\n");
        p.append("Net change:             ").append(metrics.getNetLinesChanged()).append("\n");
        p.append("Complexity delta (AST): ").append(metrics.getComplexityDelta())
                .append(complexityInterpretation(metrics.getComplexityDelta())).append("\n");

        // NEW: AST-derived metrics from FlowExtractorService feedback
        if (metrics.getMaxCallDepth() > 0) {
            p.append("Max call depth:         ").append(metrics.getMaxCallDepth())
                    .append(callDepthInterpretation(metrics.getMaxCallDepth())).append("\n");
        }
        if (metrics.getAvgChangedMethodCC() > 0) {
            p.append("Avg CC of changed methods: ").append(
                            String.format("%.1f", metrics.getAvgChangedMethodCC()))
                    .append(avgCCInterpretation(metrics.getAvgChangedMethodCC())).append("\n");
        }
        if (metrics.getRemovedPublicMethods() > 0) {
            p.append("⚠ Removed public methods: ").append(metrics.getRemovedPublicMethods())
                    .append(" — verify all callers are updated\n");
        }
        if (metrics.getAddedPublicMethods() > 0) {
            p.append("New public methods:     ").append(metrics.getAddedPublicMethods())
                    .append(" — new API surface, ensure documented\n");
        }

        p.append("\n");

        // ── SECTION 3: RISK + DIFFICULTY (with primary driver explanation) ─────
        p.append("\nRISK & DIFFICULTY ASSESSMENT\n");
        p.append("Risk level:       ").append(risk.getLevel())
                .append(" (score: ").append(String.format("%.1f%%", risk.getOverallScore() * 100)).append(")\n");

        // Inject the primary driver so AI can explain WHY in RISK_INTERPRETATION
        if (risk.getBreakdown() != null) {
            p.append("Primary driver:   ").append(extractPrimaryRiskDriver(risk)).append("\n");
        }
        if (risk.getReviewerGuidance() != null) {
            p.append("Reviewer action:  ").append(risk.getReviewerGuidance()).append("\n");
        }

        p.append("\nDifficulty level:   ").append(difficulty.getLevel())
                .append(" (~").append(difficulty.getEstimatedReviewMinutes()).append(" min)\n");
        if (difficulty.getReviewerGuidance() != null) {
            p.append("Reviewer guidance:  ").append(difficulty.getReviewerGuidance()).append("\n");
        }

        p.append("\nBlast radius:     ").append(blastRadius.getScope())
                .append(" (").append(blastRadius.getAffectedModules()).append(" modules)\n");
        if (blastRadius.getAffectedLayers() != null && !blastRadius.getAffectedLayers().isEmpty()) {
            p.append("Layers crossed:   ").append(String.join(" → ", blastRadius.getAffectedLayers())).append("\n");
        }
        if (blastRadius.getAffectedDomains() != null && !blastRadius.getAffectedDomains().isEmpty()) {
            p.append("Domains affected: ").append(String.join(", ", blastRadius.getAffectedDomains())).append("\n");
        }
        if (blastRadius.getReviewerGuidance() != null) {
            p.append("Blast action:     ").append(blastRadius.getReviewerGuidance()).append("\n");
        }
        p.append("\n");

        // ── SECTION 4: CHANGED METHODS (constrained actor list) ───────────────
        // This is the anti-hallucination anchor. The AI may ONLY reference these.
        p.append("\nCHANGED_METHODS — ONLY REFERENCE THESE IN BEHAVIORAL_CHANGES\n");
        p.append(buildChangedMethodList(callGraph, metrics)).append("\n");

        // ── SECTION 5: BEHAVIORAL SIGNALS (annotations, hotspots, removals) ───
        // NEW: the richest section, fully absent from old prompt
        p.append("\nBEHAVIORAL_SIGNALS (AST-derived — reference in BEHAVIORAL_CHANGES)\n");
        p.append(buildBehavioralSignals(callGraph, metrics)).append("\n");

        // ── SECTION 6: CALL CHAIN (runtime flow) ──────────────────────────────
        p.append("\nCALL_CHAIN (runtime execution order introduced by this PR)\n");
        p.append(buildCallChainNarrative(callGraph)).append("\n");

        // ── SECTION 7: FILE EVIDENCE ───────────────────────────────────────────
        p.append("\nFILE_EVIDENCE\n");
        p.append(buildDetailedFileEvidence(priorityFiles, files)).append("\n");

        // ── SECTION 8: METHOD-LEVEL CHANGES ───────────────────────────────────
        p.append("\nMETHOD_LEVEL_CHANGES\n");
        p.append(buildMethodChangesEvidence(priorityFiles)).append("\n");

        // ── SECTION 9: CROSS-FILE DEPENDENCIES ────────────────────────────────
        p.append("\nCROSS_FILE_DEPENDENCIES (new call relationships between files)\n");
        p.append(buildCrossFileImpactAnalysis(priorityFiles, callGraph)).append("\n");

        // ── SECTION 10: TEST COVERAGE ──────────────────────────────────────────
        p.append("\nTEST_COVERAGE\n");
        p.append(buildTestCoverageAnalysis(metrics, files)).append("\n");

        // ── SECTION 11: NEGATIVE EXAMPLES (recency: always attended) ──────────
        p.append("\nUNCHANGED_METHODS — DO NOT REFERENCE THESE\n");
        p.append(buildUnchangedMethodWarning(callGraph)).append("\n");

        // ── OUTPUT SCHEMA + STRICT RULES (recency: always attended) ───────────
        p.append("REQUIRED OUTPUT\n");
        p.append(buildOutputSchema());

        return p.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEW: CHANGED METHOD LIST (replaces buildChangedNodeList)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the constrained actor list with richer context than before.
     * Old: "  + ClassName.method [complexity=4]"
     * New: "  + ClassName.method() returns=PRAnalysisResponse  CC=4  @[Transactional]
     *            → called by: OtherClass.callerMethod"
     *
     * The "called by" context helps the AI write accurate BEHAVIORAL_CHANGES:
     * it knows that a change to X immediately affects Y.
     */
    private String buildChangedMethodList(CallGraphDiff callGraph, DiffMetrics metrics) {
        if (callGraph == null) return "Call graph unavailable — use file evidence only.\n";

        StringBuilder sb = new StringBuilder();

        // Build reverse-edge map: nodeId → list of calling node IDs
        Map<String, List<String>> calledBy = new HashMap<>();
        if (callGraph.getEdgesAdded() != null) {
            for (FlowEdge e : callGraph.getEdgesAdded()) {
                calledBy.computeIfAbsent(e.getTo(), k -> new ArrayList<>()).add(e.getFrom());
            }
        }

        List<FlowNode> added    = safeList(callGraph.getNodesAdded());
        List<FlowNode> modified = safeList(callGraph.getNodesModified());
        List<FlowNode> removed  = safeList(callGraph.getNodesRemoved());

        if (!added.isEmpty()) {
            sb.append("ADDED (new behavior):\n");
            added.stream().limit(15).forEach(n -> {
                sb.append("  + ").append(simplifyId(n.getId())).append("()");
                appendNodeMeta(sb, n);
                appendCallers(sb, n.getId(), calledBy);
                sb.append("\n");
            });
        }

        if (!modified.isEmpty()) {
            sb.append("MODIFIED (changed behavior):\n");
            modified.stream().limit(12).forEach(n -> {
                sb.append("  ~ ").append(simplifyId(n.getId())).append("()");
                appendNodeMeta(sb, n);
                appendCallers(sb, n.getId(), calledBy);
                sb.append("\n");
            });
        }

        if (!removed.isEmpty()) {
            sb.append("REMOVED (deleted behavior — verify callers):\n");
            removed.stream().limit(5).forEach(n ->
                                                      sb.append("  - ").append(simplifyId(n.getId())).append("()\n"));
        }

        if (added.isEmpty() && modified.isEmpty() && removed.isEmpty()) {
            sb.append("No method-level changes detected via AST. Use file evidence.\n");
        }

        return sb.toString();
    }

    private void appendNodeMeta(StringBuilder sb, FlowNode n) {
        if (n.getReturnType() != null && !n.getReturnType().isBlank()) {
            sb.append("  returns=").append(truncate(n.getReturnType(), 30));
        }
        sb.append("  CC=").append(n.getCyclomaticComplexity());
        if (n.getCyclomaticComplexity() >= 10) sb.append(" ⚠HIGH");
        else if (n.getCyclomaticComplexity() >= 6) sb.append(" ⚠MOD");
        if (n.getAnnotations() != null && !n.getAnnotations().isEmpty()) {
            Set<String> notable = Set.of("Transactional","PreAuthorize","PostAuthorize",
                    "Cacheable","CacheEvict","Async","Scheduled","EventListener","Override");
            String ann = n.getAnnotations().stream()
                                 .filter(notable::contains)
                                 .collect(Collectors.joining(","));
            if (!ann.isBlank()) sb.append("  @[").append(ann).append("]");
        }
    }

    private void appendCallers(StringBuilder sb, String nodeId, Map<String, List<String>> calledBy) {
        List<String> callers = calledBy.getOrDefault(nodeId, List.of());
        if (!callers.isEmpty()) {
            sb.append("  ← called by: ")
                    .append(callers.stream().limit(3).map(this::simplifyId).collect(Collectors.joining(", ")));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEW: BEHAVIORAL SIGNALS (annotation changes, hotspots, public API surface)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * This is the section most missing from the old prompt.
     *
     * Annotation changes are invisible in diff line counts but have major
     * behavioral impact:
     *   @Transactional removed   → DB operations no longer atomic
     *   @PreAuthorize added      → new authorization gate
     *   @Cacheable added/removed → response caching behavior changed
     *   @Async added             → method now runs in background thread
     *
     * Hotspot context is translated from raw IDs to actionable language.
     */
    private String buildBehavioralSignals(CallGraphDiff callGraph, DiffMetrics metrics) {
        StringBuilder sb = new StringBuilder();
        boolean hasAnySignal = false;

        // ── Annotation changes ────────────────────────────────────────────────
        List<FlowNode> modified = safeList(callGraph == null ? null : callGraph.getNodesModified());
        List<AnnotationChange> annChanges = detectAnnotationChanges(callGraph);

        if (!annChanges.isEmpty()) {
            sb.append("ANNOTATION CHANGES (runtime behavioral impact):\n");
            annChanges.forEach(ac -> sb.append("  ").append(ac.format()).append("\n"));
            hasAnySignal = true;
        }

        // ── Hotspot methods with context ──────────────────────────────────────
        if (metrics.getHotspotMethodIds() != null && !metrics.getHotspotMethodIds().isEmpty()) {
            sb.append("\nHOTSPOT METHODS (high centrality — changes propagate widely):\n");
            metrics.getHotspotMethodIds().stream().limit(5).forEach(id -> {
                FlowNode node = findNodeById(callGraph, id);
                sb.append("  ⚠ ").append(simplifyId(id));
                if (node != null) {
                    sb.append("  inDegree=").append(node.getInDegree())
                            .append("  outDegree=").append(node.getOutDegree())
                            .append("  CC=").append(node.getCyclomaticComplexity());
                    if (node.getInDegree() >= 3) {
                        sb.append("  ← ").append(node.getInDegree()).append(" callers affected by this change");
                    }
                }
                sb.append("\n");
            });
            hasAnySignal = true;
        }

        // ── High-CC individual methods ────────────────────────────────────────
        List<FlowNode> highCC = safeList(callGraph == null ? null : callGraph.getNodesAdded()).stream()
                                        .filter(n -> n.getCyclomaticComplexity() >= 8)
                                        .collect(Collectors.toList());
        safeList(callGraph == null ? null : callGraph.getNodesModified()).stream()
                .filter(n -> n.getCyclomaticComplexity() >= 8)
                .forEach(highCC::add);
        highCC.sort(Comparator.comparingInt(FlowNode::getCyclomaticComplexity).reversed());

        if (!highCC.isEmpty()) {
            sb.append("\nHIGH-COMPLEXITY METHODS (CC ≥ 8 — reviewer must trace all branches):\n");
            highCC.stream().limit(4).forEach(n ->
                                                     sb.append("  ⚠ ").append(simplifyId(n.getId()))
                                                             .append("  CC=").append(n.getCyclomaticComplexity())
                                                             .append("  (McCabe >10 = 'should be refactored'; SonarQube default threshold)")
                                                             .append("\n"));
            hasAnySignal = true;
        }

        // ── Removed public methods (API surface reduction) ────────────────────
        if (metrics.getRemovedPublicMethods() > 0) {
            sb.append("\nREMOVED PUBLIC METHODS = BREAKING CHANGE RISK:\n");
            safeList(callGraph == null ? null : callGraph.getNodesRemoved()).stream()
                    .filter(n -> !"void".equalsIgnoreCase(n.getReturnType()))
                    .limit(5)
                    .forEach(n -> sb.append("  ✂ ").append(simplifyId(n.getId()))
                                          .append("()  was returning: ").append(safe(n.getReturnType()))
                                          .append(" — verify all callers updated\n"));
            hasAnySignal = true;
        }

        // ── Return type changes ───────────────────────────────────────────────
        List<FlowNode> returnTypeChanges = modified.stream()
                                                   .filter(n -> n.getReturnType() != null && !n.getReturnType().isBlank())
                                                   .filter(n -> n.getAnnotations() != null) // crude proxy for "was an existing method"
                                                   .collect(Collectors.toList());
        // Note: true return type diff requires base node — surface modified nodes with non-void return
        modified.stream()
                .filter(n -> !"void".equalsIgnoreCase(n.getReturnType()) && n.getReturnType() != null)
                .limit(3)
                .forEach(n -> sb.append("  ⚡ MODIFIED return method: ")
                                      .append(simplifyId(n.getId()))
                                      .append("() returns=").append(n.getReturnType())
                                      .append(" — verify callers handle updated contract\n"));

        if (!hasAnySignal) {
            sb.append("No significant behavioral signals detected beyond complexity delta.\n");
        }

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ANNOTATION CHANGE DETECTION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Detects annotation changes by comparing added/modified nodes that carry
     * behavioral annotations. This is heuristic — we don't have base annotations
     * for modified nodes without a separate lookup, but we can surface which
     * modified methods NOW have important annotations (added) or added nodes
     * without annotations where annotations might be expected.
     */
    private List<AnnotationChange> detectAnnotationChanges(CallGraphDiff callGraph) {
        if (callGraph == null) return List.of();

        List<AnnotationChange> changes = new ArrayList<>();
        Set<String> behavioralAnnotations = Set.of(
                "Transactional", "PreAuthorize", "PostAuthorize",
                "Cacheable", "CacheEvict", "CachePut",
                "Async", "Scheduled", "EventListener",
                "RolesAllowed", "Secured", "PermitAll", "DenyAll"
        );

        // Added nodes with behavioral annotations = new guarded/transactional behavior
        safeList(callGraph.getNodesAdded()).stream()
                .filter(n -> n.getAnnotations() != null)
                .forEach(n -> {
                    Set<String> relevant = n.getAnnotations().stream()
                                                   .filter(behavioralAnnotations::contains)
                                                   .collect(Collectors.toSet());
                    if (!relevant.isEmpty()) {
                        changes.add(new AnnotationChange(
                                simplifyId(n.getId()), "ADDED",
                                String.join(", @", relevant),
                                annotationImpact(relevant)
                        ));
                    }
                });

        // Modified nodes with behavioral annotations = existing behavior potentially changed
        safeList(callGraph.getNodesModified()).stream()
                .filter(n -> n.getAnnotations() != null)
                .forEach(n -> {
                    Set<String> relevant = n.getAnnotations().stream()
                                                   .filter(behavioralAnnotations::contains)
                                                   .collect(Collectors.toSet());
                    if (!relevant.isEmpty()) {
                        changes.add(new AnnotationChange(
                                simplifyId(n.getId()), "ON_MODIFIED_METHOD",
                                String.join(", @", relevant),
                                annotationImpact(relevant)
                        ));
                    }
                });

        return changes;
    }

    private String annotationImpact(Set<String> annotations) {
        if (annotations.contains("Transactional"))
            return "DB operations now atomic — verify rollback behavior";
        if (annotations.contains("PreAuthorize") || annotations.contains("PostAuthorize")
                    || annotations.contains("Secured") || annotations.contains("RolesAllowed"))
            return "Authorization gate added — verify principal requirements";
        if (annotations.contains("Cacheable") || annotations.contains("CacheEvict"))
            return "Caching behavior changed — verify cache invalidation strategy";
        if (annotations.contains("Async"))
            return "Method now runs asynchronously — verify caller doesn't depend on synchronous result";
        if (annotations.contains("Scheduled"))
            return "New scheduled task — verify cron expression and idempotency";
        return "Runtime behavior annotation present — verify intent";
    }

    private static class AnnotationChange {
        final String methodId;
        final String changeType;
        final String annotations;
        final String impact;

        AnnotationChange(String methodId, String changeType, String annotations, String impact) {
            this.methodId    = methodId;
            this.changeType  = changeType;
            this.annotations = annotations;
            this.impact      = impact;
        }

        String format() {
            return String.format("@%s on %s (%s) → %s", annotations, methodId, changeType, impact);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CALL CHAIN NARRATIVE (replaces buildCallChainSummary)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Old version: raw "A → B" edge list with a step counter.
     * New version: human-readable narrative of the execution path,
     * with entry point clearly labeled and depth noted.
     */
    private String buildCallChainNarrative(CallGraphDiff callGraph) {
        if (callGraph == null || callGraph.getEdgesAdded() == null
                    || callGraph.getEdgesAdded().isEmpty()) {
            return "No new call chains introduced (internal method body changes only).\n";
        }

        StringBuilder sb = new StringBuilder();
        List<FlowEdge> addedEdges = callGraph.getEdgesAdded();

        // Find entry point: added node with no incoming added edges
        Set<String> hasIncoming = addedEdges.stream()
                                          .map(FlowEdge::getTo).collect(Collectors.toSet());
        Optional<FlowNode> entryOpt = safeList(callGraph.getNodesAdded()).stream()
                                              .filter(n -> !hasIncoming.contains(n.getId()))
                                              .findFirst();

        if (entryOpt.isPresent()) {
            sb.append("Entry point: ").append(simplifyId(entryOpt.get().getId()))
                    .append("()  [triggered by HTTP request or event]\n\n");
        }

        sb.append("Call sequence (left = caller, right = callee):\n");
        Set<String> seen = new LinkedHashSet<>();
        int step = 1;
        for (FlowEdge e : addedEdges.stream().limit(20).collect(Collectors.toList())) {
            String sig = simplifyId(e.getFrom()) + " → " + simplifyId(e.getTo());
            if (seen.contains(sig)) continue;
            seen.add(sig);
            sb.append("  ").append(step++).append(". ")
                    .append(simplifyId(e.getFrom())).append("()")
                    .append(" → ").append(simplifyId(e.getTo())).append("()")
                    .append("  [").append(e.getEdgeType()).append("]")
                    .append("\n");
        }

        if (callGraph.getMetrics() != null && callGraph.getMetrics().getMaxDepth() > 0) {
            sb.append("\nMax call depth: ").append(callGraph.getMetrics().getMaxDepth())
                    .append(callDepthInterpretation(callGraph.getMetrics().getMaxDepth())).append("\n");
        }

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // METRIC INTERPRETATION HELPERS (inline labels for LLM context)
    // ─────────────────────────────────────────────────────────────────────────

    private String complexityInterpretation(int delta) {
        if (delta < 0)  return " [PR REDUCES complexity — simplification]";
        if (delta == 0) return " [neutral]";
        if (delta <= 5) return " [minor — normal feature addition]";
        if (delta <= 15) return " [moderate — reviewer must trace logic paths]";
        if (delta <= 30) return " [HIGH — full mental model required]";
        return " [CRITICAL — consider requesting PR decomposition]";
    }

    private String callDepthInterpretation(int depth) {
        if (depth <= 2) return " [shallow — easy to trace]";
        if (depth <= 4) return " [medium — multi-step trace]";
        if (depth <= 6) return " [deep — mental stack required]";
        return " [very deep — consider stepping through in debugger]";
    }

    private String avgCCInterpretation(double cc) {
        if (cc < 4)  return " [simple methods]";
        if (cc < 8)  return " [moderate complexity]";
        if (cc < 12) return " [high — reviewer must trace branches]";
        return " [very high — above SonarQube refactoring threshold]";
    }

    private String extractPrimaryRiskDriver(RiskAssessment risk) {
        RiskBreakdown b = risk.getBreakdown();
        if (b == null) return "unknown";

        Map<String, Double> contributions = new LinkedHashMap<>();
        contributions.put("peak file risk",        safeDouble(b.getPeakRiskContribution()));
        contributions.put("complexity increase",   safeDouble(b.getComplexityContribution()));
        contributions.put("critical path density", safeDouble(b.getCriticalPathDensityContribution()));
        contributions.put("test coverage gap",     safeDouble(b.getTestCoverageGapContribution()));
        contributions.put("average file risk",     safeDouble(b.getAverageRiskContribution()));

        return contributions.entrySet().stream()
                       .max(Map.Entry.comparingByValue())
                       .map(e -> String.format("%s (%.1f%% of score)", e.getKey(), e.getValue() * 100))
                       .orElse("unknown");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXISTING EVIDENCE BUILDERS (retained, minor improvements)
    // ─────────────────────────────────────────────────────────────────────────

    private String buildDetailedFileEvidence(List<FileChangeSummary> files, List<GitHubFile> ghFiles) {
        StringBuilder sb = new StringBuilder();
        Map<String, GitHubFile> ghFileMap = ghFiles.stream()
                                                    .collect(Collectors.toMap(GitHubFile::getFilename, Function.identity(), (a, b) -> a));

        for (FileChangeSummary f : files) {
            GitHubFile ghFile = ghFileMap.get(f.getFilename());
            if (ghFile == null) continue;

            sb.append("\n  FILE: ").append(f.getFilename()).append("\n");
            sb.append("  Risk: ").append(f.getRiskLevel()).append("  |  ");
            sb.append("Change: ").append(f.getChangeType()).append("  |  ");
            sb.append("+").append(f.getLinesAdded()).append(" / -").append(f.getLinesDeleted()).append("  |  ");
            sb.append("CC: ").append(f.getComplexityDelta() >= 0 ? "+" : "")
                    .append(f.getComplexityDelta()).append("\n");

            if (f.getCriticalDetectionResult() != null
                        && !f.getCriticalDetectionResult().getReasons().isEmpty()) {
                sb.append("  Risk signals:\n");
                f.getCriticalDetectionResult().getReasons().stream().limit(3)
                        .forEach(r -> sb.append("    • ").append(r).append("\n"));
            }

            if (f.getRiskLevel() == RiskLevel.HIGH || f.getRiskLevel() == RiskLevel.CRITICAL) {
                List<String> addedLines = summarizeCodeLines(diffParser.extractAddedLines(ghFile.getPatch()), 10);
                if (!addedLines.isEmpty()) {
                    sb.append("  Key added lines:\n");
                    addedLines.forEach(l -> sb.append("    + ").append(l).append("\n"));
                }
            }
            sb.append("\n");
        }
        return sb.isEmpty() ? "No file evidence available.\n" : sb.toString();
    }

    private String buildMethodChangesEvidence(List<FileChangeSummary> files) {
        StringBuilder sb = new StringBuilder();
        for (FileChangeSummary file : files) {
            if (file.getMethodChanges() == null || file.getMethodChanges().isEmpty()) continue;
            List<MethodChange> significant = file.getMethodChanges().stream()
                                                     .filter(m -> m.getChangeType() != MethodChange.MethodChangeType.UNCHANGED)
                                                     .filter(m -> Math.abs(m.getComplexityDelta()) > 2
                                                                          || m.getChangeType() == MethodChange.MethodChangeType.ADDED
                                                                          || m.getChangeType() == MethodChange.MethodChangeType.DELETED)
                                                     .limit(5).collect(Collectors.toList());
            if (significant.isEmpty()) continue;
            sb.append("\n  ").append(file.getFilename()).append(":\n");
            significant.forEach(m -> sb.append("    ").append(formatMethodChange(m)).append("\n"));
        }
        return sb.isEmpty() ? "No significant method-level changes.\n" : sb.toString();
    }

    private String buildCrossFileImpactAnalysis(List<FileChangeSummary> files, CallGraphDiff callGraph) {
        if (callGraph == null || callGraph.getEdgesAdded() == null) return "Not available.\n";
        Map<String, Set<String>> fileDeps = new LinkedHashMap<>();
        for (FlowEdge edge : callGraph.getEdgesAdded()) {
            String from = extractFileFromNodeId(edge.getFrom(), files);
            String to   = extractFileFromNodeId(edge.getTo(),   files);
            if (from != null && to != null && !from.equals(to)) {
                fileDeps.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
            }
        }
        if (fileDeps.isEmpty()) return "No new cross-file dependencies introduced.\n";
        StringBuilder sb = new StringBuilder();
        fileDeps.entrySet().stream().limit(6).forEach(e -> {
            sb.append("  ").append(simplifyPath(e.getKey())).append(" → ");
            sb.append(e.getValue().stream().map(this::simplifyPath).collect(Collectors.joining(", ")));
            sb.append("\n");
        });
        return sb.toString();
    }

    private String buildTestCoverageAnalysis(DiffMetrics metrics, List<GitHubFile> files) {
        long testFiles = files.stream().filter(f -> isTestFile(f.getFilename())).count();
        long prodFiles = files.size() - testFiles;
        StringBuilder sb = new StringBuilder();
        sb.append("Production files: ").append(prodFiles)
                .append("  |  Test files: ").append(testFiles).append("\n");
        if (testFiles == 0 && prodFiles > 0) {
            sb.append("⚠ No test files modified. ");
            sb.append("Mockus et al. (2000): changes without tests have 2× post-merge defect rate.\n");
        } else if (testFiles > 0 && prodFiles > 0) {
            double ratio = (double) testFiles / prodFiles;
            sb.append(ratio >= 0.5 ? "✅ " : "⚠ ")
                    .append(String.format("%.0f%% test-to-production file ratio", ratio * 100)).append("\n");
        }
        return sb.toString();
    }

    private String buildUnchangedMethodWarning(CallGraphDiff callGraph) {
        if (callGraph == null || callGraph.getNodesUnchanged() == null
                    || callGraph.getNodesUnchanged().isEmpty()) return "N/A\n";
        return callGraph.getNodesUnchanged().stream()
                       .limit(10)
                       .map(n -> "  - " + simplifyId(n.getId()))
                       .collect(Collectors.joining("\n")) + "\n";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OUTPUT SCHEMA
    // ─────────────────────────────────────────────────────────────────────────

    private String buildOutputSchema() {
        return """
STRICT RULES:
1. Return ONLY the JSON object below. No markdown fences, no preamble.
2. ONLY reference methods from CHANGED_METHODS. Zero exceptions.
3. Never reference methods from UNCHANGED_METHODS.
4. For BEHAVIORAL_CHANGES: cite specific method names and annotation changes from BEHAVIORAL_SIGNALS.
5. For RISK_INTERPRETATION: reference the primary driver explicitly.
6. For CHECKLIST: generate specific, actionable items based on the signals above.
   Do NOT write generic items like "ensure tests pass."
7. Total response ≤ 700 words.
8. If a field cannot be determined from evidence, write: "Insufficient data."

{
  "OVERVIEW": "3-5 sentences: what does this PR do, what is the entry point, what problem does it solve?",
  "STRUCTURAL_IMPACT": "Which components/layers are affected? Any new cross-module dependencies?",
  "BEHAVIORAL_CHANGES": "Concrete behavioral changes. Reference specific methods from CHANGED_METHODS. Call out annotation changes from BEHAVIORAL_SIGNALS.",
  "RISK_INTERPRETATION": "Why this risk level? Reference the primary driver. Name specific files or methods.",
  "REVIEW_FOCUS": "Ordered list: which files/methods deserve most scrutiny and why.",
  "CHECKLIST": "5-7 specific verification steps tailored to the actual signals in this PR.",
  "CONFIDENCE": "HIGH / MEDIUM / LOW — one-line reason based on evidence quality."
}
""";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITIES
    // ─────────────────────────────────────────────────────────────────────────

    private List<FileChangeSummary> selectPriorityFiles(List<FileChangeSummary> files, int limit) {
        return files.stream()
                       .filter(f -> !isTestFile(f.getFilename()))
                       .sorted(Comparator.comparing(FileChangeSummary::getRiskLevel).reversed()
                                       .thenComparingInt(f -> -Math.abs(f.getComplexityDelta())))
                       .limit(limit)
                       .collect(Collectors.toList());
    }

    private FlowNode findNodeById(CallGraphDiff callGraph, String id) {
        if (callGraph == null) return null;
        return java.util.stream.Stream.of(
                        safeList(callGraph.getNodesAdded()),
                        safeList(callGraph.getNodesModified()),
                        safeList(callGraph.getNodesUnchanged()))
                       .flatMap(List::stream)
                       .filter(n -> id.equals(n.getId()))
                       .findFirst()
                       .orElse(null);
    }

    private List<String> summarizeCodeLines(List<String> lines, int limit) {
        return lines.stream().map(String::trim)
                       .filter(l -> !l.isBlank() && l.length() > 5)
                       .filter(l -> !l.matches("[{}();]+"))
                       .filter(l -> !l.startsWith("//") && !l.startsWith("/*"))
                       .filter(l -> !l.startsWith("import ") && !l.startsWith("package "))
                       .limit(limit).collect(Collectors.toList());
    }

    private String formatMethodChange(MethodChange m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getChangeType()).append(": ").append(m.getMethodName());
        if (m.getComplexityDelta() != 0)
            sb.append(" [CC: ").append(m.getComplexityBefore()).append("→").append(m.getComplexityAfter()).append("]");
        if (m.getChangeDescription() != null) sb.append(" — ").append(m.getChangeDescription());
        return sb.toString();
    }

    private String simplifyId(String fqn) {
        if (fqn == null) return "";
        String[] parts = fqn.split("\\.");
        return parts.length < 2 ? fqn : parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private String simplifyPath(String path) {
        if (path == null) return "";
        String[] parts = path.split("/");
        return parts[parts.length - 1];
    }

    private String extractFileFromNodeId(String nodeId, List<FileChangeSummary> files) {
        for (FileChangeSummary f : files) {
            if (nodeId.toLowerCase().contains(
                    f.getFilename().replaceAll(".*/", "").replaceAll("\\.java$", "").toLowerCase()))
                return f.getFilename();
        }
        return null;
    }

    private boolean isTestFile(String filename) {
        String l = filename.toLowerCase();
        return l.contains("/test/") || l.endsWith("test.java")
                       || l.endsWith("spec.js") || l.endsWith("_test.py")
                       || l.endsWith("spec.rb") || l.endsWith("test.ts");
    }

    private double safeDouble(Double d) { return d != null ? d : 0.0; }
    private String safe(String s)       { return s != null ? s : ""; }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max - 3) + "..." : safe(s);
    }

    private <T> List<T> safeList(List<T> l) { return l != null ? l : Collections.emptyList(); }

    private String cleanJson(String r) {
        return r.replaceAll("```json", "").replaceAll("```", "").trim();
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf("{");
        if (start == -1) throw new IllegalArgumentException("No JSON in AI response");
        int count = 0;
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '{') count++;
            if (text.charAt(i) == '}') count--;
            if (count == 0) return text.substring(start, i + 1);
        }
        throw new IllegalArgumentException("Malformed JSON");
    }

    private AIGeneratedNarrative parseJsonResponse(String response) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(response);
        return AIGeneratedNarrative.builder()
                       .overview(readFlexible(root, "OVERVIEW"))
                       .structuralImpact(readFlexible(root, "STRUCTURAL_IMPACT"))
                       .behavioralChanges(readFlexible(root, "BEHAVIORAL_CHANGES"))
                       .riskInterpretation(readFlexible(root, "RISK_INTERPRETATION"))
                       .reviewFocus(readFlexible(root, "REVIEW_FOCUS"))
                       .checklist(readFlexible(root, "CHECKLIST"))
                       .confidence(readFlexible(root, "CONFIDENCE"))
                       .generatedAt(Instant.now())
                       .build();
    }

    private String readFlexible(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return "Insufficient data.";
        if (v.isArray()) {
            StringBuilder sb = new StringBuilder();
            v.forEach(item -> sb.append("- ").append(item.asText()).append("\n"));
            return sb.toString().trim();
        }
        return v.asText();
    }

    private AIGeneratedNarrative fallbackNarrative(PRMetadata metadata, DiffMetrics metrics) {
        return AIGeneratedNarrative.builder()
                       .overview("Analysis summary temporarily unavailable.")
                       .structuralImpact("See file changes section.")
                       .behavioralChanges("See method-level changes.")
                       .riskInterpretation("See risk assessment section.")
                       .reviewFocus("Focus on high-risk files and complexity increases.")
                       .checklist("- Review high-risk files\n- Verify test coverage\n- Check public API changes")
                       .confidence("LOW — generated from fallback template")
                       .generatedAt(Instant.now())
                       .build();
    }
}