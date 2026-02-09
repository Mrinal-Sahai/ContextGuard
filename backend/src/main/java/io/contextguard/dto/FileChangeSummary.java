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
public class FileChangeSummary {
    private String filename;
    private String changeType; // added/modified/deleted
    private int linesAdded;
    private int linesDeleted;
    private int complexityDelta;
    private int totalComplexityBefore;
    private int totalComplexityAfter;
    private  RiskLevel riskLevel;
    private List<MethodChange> methodChanges;
    private String methodSignatures;
    private String beforeSnippet;
    private String afterSnippet;
    private String reason;
}
