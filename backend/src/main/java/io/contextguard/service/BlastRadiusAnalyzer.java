package io.contextguard.service;

import io.contextguard.dto.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * BLAST RADIUS ANALYZER
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * WHAT IS BLAST RADIUS — AND WHY REVIEWERS SHOULD CARE
 * ──────────────────────────────────────────────────────
 * "Blast radius" is a term borrowed from SRE (Site Reliability Engineering),
 * popularized by Google's SRE book (Beyer et al., 2016) and adopted in
 * software engineering to describe: "if this change causes a failure, how
 * many other systems/layers/teams are affected?"
 *
 * It is DISTINCT from risk:
 *   Risk = probability of something going wrong.
 *   Blast radius = size of impact IF something goes wrong.
 *
 * A low-risk change with high blast radius (e.g., touching a shared library
 * used by 12 services) still demands careful review because a mistake is
 * expensive to roll back and affects many users/teams.
 *
 * RESEARCH BACKING
 * ─────────────────
 * - Beyer et al. (2016): "Site Reliability Engineering", Google Press.
 *   Coined blast radius in DevOps context. Change = "radius of blast if it fails."
 * - Tamrawi et al. (2011): "Fuzzy set and cache-based approach for change
 *   impact analysis." — Architectural layer coupling amplifies change impact.
 * - Bavota et al. (2013): "The Evolution of Project Inter-dependencies in a
 *   Software Ecosystem." — Cross-module changes are 2.4× more defect-prone.
 * - Zimmermann et al. (2008): Import graph centrality predicts defect propagation.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * BLAST RADIUS DIMENSIONS
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * We measure blast radius along 3 orthogonal dimensions:
 *
 * 1. HORIZONTAL SPREAD (module breadth)
 *    How many distinct logical modules does this PR touch?
 *    More modules → changes are harder to isolate and roll back.
 *
 * 2. VERTICAL DEPTH (layer penetration)
 *    How many architectural layers does this PR cross?
 *    (Presentation → Business Logic → Data Access → Infrastructure)
 *    Bavota et al. 2013: cross-layer changes are 2.4× more defect-prone.
 *
 * 3. DOMAIN BREADTH (functional area spread)
 *    How many distinct business domains are affected?
 *    (auth, payments, user management, notifications, etc.)
 *    Domain crossing requires multiple teams to review and coordinate.
 *
 * SCOPE CLASSIFICATION
 * ─────────────────────
 *
 *   LOCALIZED    → 1 module, 1 layer, 1 domain.
 *                  Blast radius is contained. Isolated rollback is straightforward.
 *
 *   COMPONENT    → 1 module, multiple files/layers.
 *                  Blast radius stays within a single service. Rollback manageable.
 *
 *   CROSS_MODULE → 2–3 modules or 2–3 layers.
 *                  Blast radius crosses component boundaries. Coordinate with
 *                  owners of adjacent modules before merging.
 *
 *   SYSTEM_WIDE  → 4+ modules or 3+ layers or 3+ domains.
 *                  Blast radius is system-level. A failure here can cascade.
 *                  Requires: staged rollout, feature flags, or phased deployment.
 *
 * WHAT THE SCOPE MEANS — EXAMPLE SCENARIOS
 * ──────────────────────────────────────────
 *
 *   Scenario A (LOCALIZED):
 *     2 files in "src/service/user/", both in service layer.
 *     Blast radius: user-service only. 1 team needs to review.
 *     Reviewer action: "Standard review. Verify unit tests cover both files."
 *
 *   Scenario B (COMPONENT):
 *     5 files across controller + service + repository, all in auth module.
 *     Blast radius: auth module, 3 layers deep.
 *     Reviewer action: "Trace the full request path. Ensure auth invariants hold
 *     at each layer. Consider integration test before merge."
 *
 *   Scenario C (CROSS_MODULE):
 *     8 files touching payments + notification + user modules.
 *     Blast radius: 3 teams. Payments → triggers notification → updates user.
 *     Reviewer action: "Tag owners of notification and user modules. Verify
 *     event ordering and rollback strategy across module boundaries."
 *
 *   Scenario D (SYSTEM_WIDE):
 *     15 files across 5 modules, 4 architectural layers.
 *     Blast radius: entire system. A config error breaks all services.
 *     Reviewer action: "Consider splitting PR. If not possible, require
 *     staged canary deployment. At minimum, 2 senior reviewers."
 */
@Component
public class BlastRadiusAnalyzer {

    // ─────────────────────────────────────────────────────────────────────────
    // ARCHITECTURAL LAYER DETECTION
    // ─────────────────────────────────────────────────────────────────────────

    /** Ordered from outermost to innermost layer */
    private enum ArchLayer {
        PRESENTATION("presentation",
                List.of("/controller", "/api", "/rest", "/graphql", "/grpc")),
        BUSINESS("business logic",
                List.of("/service", "/usecase", "/handler", "/command", "/query")),
        DATA_ACCESS("data access",
                List.of("/repository", "/dao", "/mapper", "/store")),
        DOMAIN("domain model",
                List.of("/model", "/entity", "/domain", "/aggregate", "/vo")),
        INFRASTRUCTURE("infrastructure",
                List.of("/config", "/adapter", "/client", "/integration",
                        "/messaging", "/queue", "/cache", "/kafka"));

        final String displayName;
        final List<String> pathKeywords;

        ArchLayer(String displayName, List<String> pathKeywords) {
            this.displayName = displayName;
            this.pathKeywords = pathKeywords;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BUSINESS DOMAIN DETECTION
    // ─────────────────────────────────────────────────────────────────────────

    private static final Map<String, List<String>> DOMAIN_KEYWORDS = Map.of(
            "authentication",  List.of("auth", "security", "oauth", "jwt", "permission"),
            "payments",        List.of("payment", "billing", "invoice", "checkout", "refund"),
            "user-management", List.of("user", "profile", "account", "identity"),
            "notifications",   List.of("notification", "email", "sms", "push", "alert"),
            "data-pipeline",   List.of("pipeline", "etl", "batch", "scheduler", "job"),
            "configuration",   List.of("config", "settings", "properties", "feature-flag"),
            "api-gateway",     List.of("gateway", "proxy", "routing", "middleware")
    );

    // Module path segments to skip (Maven/Gradle standard layout)
    private static final Set<String> SKIPPABLE_SEGMENTS = Set.of(
            "src", "main", "java", "test", "kotlin", "resources",
            "webapp", "groovy", "scala", "com", "org", "io", "net", "app"
    );

    // ─────────────────────────────────────────────────────────────────────────
    // SCOPE THRESHOLDS
    // ─────────────────────────────────────────────────────────────────────────

    private static final int CROSS_MODULE_THRESHOLD = 2;  // ≥2 modules = CROSS_MODULE
    private static final int SYSTEM_WIDE_MODULES    = 4;  // ≥4 modules = SYSTEM_WIDE
    private static final int SYSTEM_WIDE_LAYERS     = 3;  // ≥3 layers  = SYSTEM_WIDE
    private static final int SYSTEM_WIDE_DOMAINS    = 3;  // ≥3 domains = SYSTEM_WIDE

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN ANALYSIS
    // ─────────────────────────────────────────────────────────────────────────

    public BlastRadiusAssessment analyze(DiffMetrics metrics) {

        List<String> filePaths = metrics.getFileChanges().stream()
                                         .map(FileChangeSummary::getFilename)
                                         .collect(Collectors.toList());

        // Dimension 1: Horizontal — module breadth
        Set<String> modules = filePaths.stream()
                                      .map(this::extractModule)
                                      .collect(Collectors.toCollection(TreeSet::new));

        // Dimension 2: Vertical — architectural layer penetration
        Set<ArchLayer> layersTouched = detectLayers(filePaths);
        List<String> layerNames = layersTouched.stream()
                                          .map(l -> l.displayName)
                                          .sorted()
                                          .collect(Collectors.toList());

        // Dimension 3: Domain — business area spread
        Map<String, List<String>> domainToFiles = detectDomains(filePaths);
        Set<String> domainsAffected = new TreeSet<>(domainToFiles.keySet());

        // Unique directories (for reporting)
        Set<String> directories = filePaths.stream()
                                          .map(this::extractDirectory)
                                          .collect(Collectors.toCollection(TreeSet::new));

        // Classify scope from all 3 dimensions
        ImpactScope scope = classifyScope(modules.size(), layersTouched.size(), domainsAffected.size());

        // Build reviewer-actionable assessment
        String assessment = buildAssessment(scope, modules, layerNames, domainsAffected);
        String reviewerGuidance = buildReviewerGuidance(scope, modules, layerNames, domainsAffected);

        return BlastRadiusAssessment.builder()
                       .scope(scope)
                       .affectedDirectories(directories.size())
                       .affectedModules(modules.size())
                       .affectedModuleNames(new ArrayList<>(modules))
                       .affectedLayers(layerNames)
                       .affectedLayerCount(layersTouched.size())
                       .affectedDomains(new ArrayList<>(domainsAffected))
                       .impactedAreas(new ArrayList<>(domainsAffected))  // backward compat
                       .assessment(assessment)
                       .reviewerGuidance(reviewerGuidance)
                       .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DIMENSION DETECTION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Detect architectural layers touched by the changed files.
     * Uses TreeSet to guarantee deterministic ordering.
     */
    private Set<ArchLayer> detectLayers(List<String> filePaths) {
        Set<ArchLayer> layers = new TreeSet<>(Comparator.comparing(Enum::name));
        for (String path : filePaths) {
            String lower = path.toLowerCase();
            for (ArchLayer layer : ArchLayer.values()) {
                for (String keyword : layer.pathKeywords) {
                    if (lower.contains(keyword)) {
                        layers.add(layer);
                        break;
                    }
                }
            }
        }
        return layers;
    }

    /**
     * Detect business domains affected by the changed files.
     * Returns a map of domain → list of files in that domain (for evidence).
     */
    private Map<String, List<String>> detectDomains(List<String> filePaths) {
        Map<String, List<String>> domainFiles = new TreeMap<>();
        for (String path : filePaths) {
            String lower = path.toLowerCase();
            for (Map.Entry<String, List<String>> entry : DOMAIN_KEYWORDS.entrySet()) {
                for (String keyword : entry.getValue()) {
                    if (lower.contains(keyword)) {
                        domainFiles.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(path);
                        break;
                    }
                }
            }
        }
        return domainFiles;
    }

    /**
     * Extract logical module from file path.
     *
     * Strategy: skip standard Maven/Gradle layout segments and reverse-domain
     * prefixes (com, org, io), return the first meaningful package segment.
     *
     * Example: "src/main/java/io/contextguard/payment/PaymentService.java"
     * → skips: src, main, java, io
     * → returns: "contextguard"  (the first meaningful segment)
     *
     * If you want module = "payment" (the business module), set SKIP_ORG_SEGMENT=true
     * in a future config. For now we capture the top-level project package.
     */
    private String extractModule(String filePath) {
        if (filePath == null || filePath.isBlank()) return "root";
        String[] parts = filePath.split("/");

        for (String part : parts) {
            if (part.isBlank()) continue;
            if (SKIPPABLE_SEGMENTS.contains(part.toLowerCase())) continue;
            // Skip short pure-lowercase reverse-domain segments (com, org, io, net)
            if (part.length() <= 3 && part.matches("[a-z]+")) continue;
            return part;
        }

        // Fallback: use directory containing the file
        return parts.length > 1 ? parts[parts.length - 2] : "root";
    }

    private String extractDirectory(String filePath) {
        int lastSlash = filePath.lastIndexOf('/');
        return lastSlash > 0 ? filePath.substring(0, lastSlash) : ".";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCOPE CLASSIFICATION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Classify blast radius scope from all 3 dimensions.
     * Any single dimension at SYSTEM_WIDE level triggers SYSTEM_WIDE classification.
     */
    private ImpactScope classifyScope(int moduleCount, int layerCount, int domainCount) {
        // SYSTEM_WIDE: any dimension exceeds threshold
        if (moduleCount >= SYSTEM_WIDE_MODULES
                    || layerCount >= SYSTEM_WIDE_LAYERS
                    || domainCount >= SYSTEM_WIDE_DOMAINS) {
            return ImpactScope.SYSTEM_WIDE;
        }

        // CROSS_MODULE: touches 2–3 modules or 2 layers
        if (moduleCount >= CROSS_MODULE_THRESHOLD || layerCount >= 2) {
            return ImpactScope.MODULE;
        }

        // COMPONENT: single module, multiple files
        if (layerCount == 1 || moduleCount == 1) {
            return ImpactScope.COMPONENT;
        }

        return ImpactScope.LOCALIZED;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REVIEWER-FACING OUTPUT
    // ─────────────────────────────────────────────────────────────────────────

    private String buildAssessment(ImpactScope scope,
                                   Set<String> modules,
                                   List<String> layers,
                                   Set<String> domains) {
        return switch (scope) {
            case LOCALIZED ->
                    "Blast radius LOCALIZED: changes contained within a single component. " +
                            "Isolated rollback is straightforward.";

            case COMPONENT ->
                    String.format("Blast radius COMPONENT: changes affect layers [%s] within one module. " +
                                          "Trace the full request path across these layers.",
                            String.join(", ", layers));

            case MODULE ->
                    String.format("Blast radius CROSS-MODULE: %d modules affected [%s] across %d layer(s). " +
                                          "Coordinate with module owners. Bavota et al. (2013): cross-module changes " +
                                          "are 2.4× more defect-prone.",
                            modules.size(), String.join(", ", modules), layers.size());

            case SYSTEM_WIDE ->
                    String.format("Blast radius SYSTEM-WIDE: %d modules, %d architectural layers, " +
                                          "%d business domains [%s] affected. " +
                                          "A failure here cascades. Consider staged deployment.",
                            modules.size(), layers.size(), domains.size(),
                            String.join(", ", domains));
        };
    }

    private String buildReviewerGuidance(ImpactScope scope,
                                         Set<String> modules,
                                         List<String> layers,
                                         Set<String> domains) {
        return switch (scope) {
            case LOCALIZED ->
                    "Standard review. Verify unit tests cover the changed component.";

            case COMPONENT ->
                    String.format("Trace the request flow through layers: %s. " +
                                          "Verify integration tests exist for the full path.",
                            String.join(" → ", layers));

            case MODULE ->
                    String.format("Tag owners of modules: %s. " +
                                          "Verify event contracts and API boundaries between modules are unchanged, " +
                                          "or that all consumers are updated.",
                            String.join(", ", modules));

            case SYSTEM_WIDE ->
                    String.format("RECOMMEND: (1) Split PR if possible. " +
                                          "(2) If not, require 2 senior reviewers. " +
                                          "(3) Verify feature flags or canary deployment is in place. " +
                                          "(4) Domains affected: %s — tag domain owners.",
                            String.join(", ", domains));
        };
    }
}