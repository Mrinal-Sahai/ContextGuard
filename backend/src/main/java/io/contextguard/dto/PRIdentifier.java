package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Value object representing GitHub PR coordinates.
 *
 * Used as a composite key for identifying PRs across the system.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PRIdentifier {
    private String owner;
    private String repo;
    private Integer prNumber;

    /**
     * Format: owner/repo#prNumber
     * Example: spring-projects/spring-boot#12345
     */
    public String toKey() {
        return String.format("%s/%s#%d", owner, repo, prNumber);
    }
}