package io.contextguard.service;

import io.contextguard.dto.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes blast radius of PR changes using file path topology.
 *
 * FIX (2025-03):
 * 1. identifyImpactedAreas() previously used HashSet — area ordering was
 *    non-deterministic between JVM runs, producing different responses for
 *    identical inputs. Now uses TreeSet for alphabetical, stable ordering.
 *
 * 2. extractModule() used path.split("/")[0] — breaks on monorepos where all
 *    source files are under "src/main/java/..." (every file maps to "src").
 *    Now uses the deepest meaningful directory segment (last directory before
 *    the file name), which correctly differentiates service modules.
 */
@Component
public class BlastRadiusAnalyzer {

    public BlastRadiusAssessment analyze(DiffMetrics metrics) {

        List<String> filePaths = metrics.getFileChanges().stream()
                                         .map(FileChangeSummary::getFilename)
                                         .collect(Collectors.toList());

        Set<String> directories = filePaths.stream()
                                          .map(this::extractDirectory)
                                          .collect(Collectors.toCollection(TreeSet::new)); // FIX: TreeSet for determinism

        Set<String> modules = filePaths.stream()
                                      .map(this::extractModule)
                                      .collect(Collectors.toCollection(TreeSet::new)); // FIX: TreeSet for determinism

        // FIX: impactedAreas now returns sorted list for deterministic output
        List<String> impactedAreas = identifyImpactedAreas(filePaths);

        ImpactScope scope = determineScope(directories.size(), modules.size());
        String assessment = generateAssessment(scope, modules.size(), impactedAreas);

        return BlastRadiusAssessment.builder()
                       .scope(scope)
                       .affectedDirectories(directories.size())
                       .affectedModules(modules.size())
                       .impactedAreas(impactedAreas)
                       .assessment(assessment)
                       .build();
    }

    private String extractDirectory(String filePath) {
        int lastSlash = filePath.lastIndexOf('/');
        return lastSlash > 0 ? filePath.substring(0, lastSlash) : "";
    }

    /**
     * Extract the logical module from a file path.
     *
     * FIX: Previously used path.split("/")[0] which maps every file in a
     * Maven/Gradle monorepo to "src", making all changes appear LOCALIZED.
     *
     * Strategy: skip common prefix segments (src, main, java, test, kotlin,
     * resources) and return the first meaningful package segment after them.
     * Falls back to the last directory segment if no meaningful segment found.
     */
    private String extractModule(String filePath) {
        if (filePath == null || filePath.isBlank()) return "root";

        String[] parts = filePath.split("/");

        Set<String> skippable = Set.of("src", "main", "java", "test", "kotlin",
                "resources", "webapp", "groovy", "scala");

        for (String part : parts) {
            if (part.isBlank()) continue;
            if (skippable.contains(part.toLowerCase())) continue;
            // Skip fully-qualified reverse-domain segments (com, org, io, net, etc.)
            if (part.length() <= 3 && part.matches("[a-z]+")) continue;
            return part;
        }

        // Fallback: last directory segment before the file
        return parts.length > 1 ? parts[parts.length - 2] : "root";
    }

    /**
     * Identify functional areas affected by this PR.
     *
     * FIX: Uses TreeSet internally so the returned List is always
     * alphabetically sorted — identical inputs produce identical outputs.
     */
    private List<String> identifyImpactedAreas(List<String> filePaths) {
        // FIX: TreeSet replaces HashSet to guarantee deterministic ordering
        Set<String> areas = new TreeSet<>();

        for (String path : filePaths) {
            String lowerPath = path.toLowerCase();

            if (lowerPath.contains("auth") || lowerPath.contains("security")) {
                areas.add("authentication");
            }
            if (lowerPath.contains("payment") || lowerPath.contains("billing")) {
                areas.add("payment");
            }
            if (lowerPath.contains("user") || lowerPath.contains("profile")) {
                areas.add("user-management");
            }
            if (lowerPath.contains("api") || lowerPath.contains("controller")) {
                areas.add("api");
            }
            if (lowerPath.contains("database") || lowerPath.contains("repository")) {
                areas.add("data-layer");
            }
            if (lowerPath.contains("config")) {
                areas.add("configuration");
            }
        }

        return new ArrayList<>(areas);
    }

    private ImpactScope determineScope(int directories, int modules) {
        if (directories == 1 && modules == 1) return ImpactScope.LOCALIZED;
        if (modules == 1)                     return ImpactScope.COMPONENT;
        if (modules <= 3)                     return ImpactScope.MODULE;
        return ImpactScope.SYSTEM_WIDE;
    }

    private String generateAssessment(ImpactScope scope, int moduleCount, List<String> areas) {
        switch (scope) {
            case LOCALIZED:    return "Changes are localized to a single component.";
            case COMPONENT:    return "Changes affect a single module but span multiple files.";
            case MODULE:       return String.format("Changes span %d modules. Affected areas: %s",
                    moduleCount, String.join(", ", areas));
            case SYSTEM_WIDE:  return String.format("System-wide changes across %d modules. High coordination required.", moduleCount);
            default:           return "Impact scope unknown.";
        }
    }
}