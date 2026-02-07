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
    private double volumeContribution;
    private double complexityContribution;
    private double criticalPathContribution;
    private double churnContribution;
}
