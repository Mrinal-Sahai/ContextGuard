package io.contextguard.dto;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Response for PR analysis trigger.
 *
 * Indicates whether analysis was freshly computed or retrieved from cache.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PRAnalysisResponse {

    /**
     * Unique identifier for this analysis.
     * Can be used to retrieve full intelligence via GET /api/v1/pr-analysis/{analysisId}
     */
    private UUID analysisId;

    /**
     * True if analysis was retrieved from cache (HTTP 200)
     * False if analysis was freshly computed (HTTP 201)
     */
    private boolean cached;

    /**
     * Human-readable message
     * Examples:
     * - "Analysis retrieved from cache"
     * - "Analysis completed"
     */
    private String message;
}