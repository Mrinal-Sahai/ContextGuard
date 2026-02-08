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
public class RiskAssessment {
    private double overallScore; // 0.0 - 1.0
    private RiskLevel level; // LOW/MEDIUM/HIGH/CRITICAL
    private RiskBreakdown breakdown;
    private List<String> criticalFilesDetected;
}

