package io.contextguard.analysis.flow;

import io.contextguard.dto.AIGeneratedNarrative;
import io.contextguard.dto.DifficultyAssessment;
import io.contextguard.dto.RiskAssessment;

public record NarrativeResult(
        AIGeneratedNarrative narrative,
        RiskAssessment risk,
        DifficultyAssessment difficulty) {}
