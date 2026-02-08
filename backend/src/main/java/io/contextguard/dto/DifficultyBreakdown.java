package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DifficultyBreakdown {
    private double sizeContribution;        // Lines changed
    private double spreadContribution;      // Number of files
    private double cognitiveContribution;   // Complexity delta
    private double contextContribution;     // File type diversity
}
