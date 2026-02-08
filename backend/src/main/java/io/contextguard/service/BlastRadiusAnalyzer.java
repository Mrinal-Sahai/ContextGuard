package io.contextguard.service;


import io.contextguard.dto.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes blast radius of PR changes.
 *
 * Uses file path analysis to determine impact scope.
 */
@Component
public class BlastRadiusAnalyzer {

    public BlastRadiusAssessment analyze(DiffMetrics metrics) {

        List<String> filePaths = metrics.getFileChanges().stream()
                                         .map(FileChangeSummary::getFilename)
                                         .collect(Collectors.toList());

        // Analyze directory spread
        Set<String> directories = filePaths.stream()
                                          .map(this::extractDirectory)
                                          .collect(Collectors.toSet());

        // Analyze module spread (top-level package or directory)
        Set<String> modules = filePaths.stream()
                                      .map(this::extractModule)
                                      .collect(Collectors.toSet());

        // Identify impacted functional areas
        List<String> impactedAreas = identifyImpactedAreas(filePaths);

        // Determine scope
        ImpactScope scope = determineScope(directories.size(), modules.size());

        // Generate assessment
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

    private String extractModule(String filePath) {
        // Extract first-level directory (module)
        String[] parts = filePath.split("/");
        return parts.length > 0 ? parts[0] : "root";
    }

    private List<String> identifyImpactedAreas(List<String> filePaths) {
        Set<String> areas = new HashSet<>();

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
        if (directories == 1 && modules == 1) {
            return ImpactScope.LOCALIZED;
        }
        if (modules == 1) {
            return ImpactScope.COMPONENT;
        }
        if (modules <= 3) {
            return ImpactScope.MODULE;
        }
        return ImpactScope.SYSTEM_WIDE;
    }

    private String generateAssessment(ImpactScope scope, int moduleCount, List<String> areas) {
        switch (scope) {
            case LOCALIZED:
                return "Changes are localized to a single component.";
            case COMPONENT:
                return "Changes affect a single module but span multiple files.";
            case MODULE:
                return String.format("Changes span %d modules. Affected areas: %s",
                        moduleCount, String.join(", ", areas));
            case SYSTEM_WIDE:
                return String.format("System-wide changes across %d modules. High coordination required.",
                        moduleCount);
            default:
                return "Impact scope unknown.";
        }
    }
}
