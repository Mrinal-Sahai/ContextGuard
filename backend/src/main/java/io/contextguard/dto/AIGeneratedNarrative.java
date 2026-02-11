package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AIGeneratedNarrative {
    private String overview;

    private String structuralImpact;

    private String behavioralChanges;

    private String riskInterpretation;

    private String reviewFocus;

    private String checklist;

    private String confidence; // HIGH / MEDIUM / LOW + explanation

    private java.time.Instant generatedAt;
    // Metadata for transparency
    private String disclaimer = "This summary is AI-generated for comprehension assistance only.";
}