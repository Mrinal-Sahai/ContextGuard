package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RiskBreakdown {
    private double averageRiskContribution;
    private double peakRiskContribution;
    private double criticalPathDensityContribution;
    private double highRiskDensityContribution;
}
