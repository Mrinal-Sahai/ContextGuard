package io.contextguard.dto;

import io.contextguard.service.criticalpath.CriticalPathDetector;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result for a single file: score and human-readable reasons (evidence).
 * This is what we persist or display to reviewers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CriticalDetectionResult {
    private String filename;
    private int score;
    private List<String> reasons;     // explicit list of signals that contributed
    private boolean isCritical;       // score >= threshold
    private CriticalityBand criticalityBand;
}


