package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Blast radius assessment.
 *
 * Quantifies potential impact scope of PR changes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlastRadiusAssessment {

    private ImpactScope scope;  // LOCALIZED / COMPONENT / MODULE / SYSTEM_WIDE
    private int affectedDirectories;
    private int affectedModules;  // Based on top-level package structure
    private List<String> impactedAreas;  // e.g., ["authentication", "payment"]
    private String assessment;  // Human-readable summary
}