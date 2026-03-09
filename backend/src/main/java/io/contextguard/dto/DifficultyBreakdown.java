package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DifficultyBreakdown {
    private double sizeContribution;        // Lines changed
    private double spreadContribution;      // Number of files
    private double cognitiveContribution;   // Complexity delta
    private double contextContribution;     // File type diversity
    private double concentrationContribution; // High/CRITICAL files
    private double criticalImpactContribution; // Critical files
    private double rawCognitiveDelta;
    private double rawLOC;
    private double rawLayerCount;
    private double rawDomainCount;
    private double rawCriticalCount;
    private List<SignalInterpretation> signals;
}
