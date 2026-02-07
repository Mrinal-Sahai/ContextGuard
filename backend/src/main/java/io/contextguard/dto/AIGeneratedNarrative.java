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
    private String overview;          // One-sentence summary
    private String keyChanges;        // Bullet points
    private String potentialConcerns; // Bullet points
    private Instant generatedAt;

    // Metadata for transparency
    private String disclaimer = "This summary is AI-generated for comprehension assistance only.";
}