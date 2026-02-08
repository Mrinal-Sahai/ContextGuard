package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FileChangeSummary {
    private String filename;
    private String changeType; // added/modified/deleted
    private int linesAdded;
    private int linesDeleted;
    private int complexityDelta;
    private  RiskLevel riskLevel;
    private String methodSignatures;
    private String beforeSnippet;
    private String afterSnippet;
    private String reason;
}
