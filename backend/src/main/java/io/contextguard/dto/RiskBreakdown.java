package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RiskBreakdown {
    private double averageRiskContribution;
    private double peakRiskContribution;
    private double criticalPathDensityContribution;
    private double highRiskDensityContribution;
    private double complexityContribution;
    private double testCoverageGapContribution;
    private double rawAverageRisk;
    private double rawPeakRisk;
    private double rawComplexityDelta;
    private double rawCriticalDensity;
    private double rawTestCoverageGap;
    private double rawSastFindings;
    private double sastFindingsContribution;
    private List<SignalInterpretation> signals;



}
