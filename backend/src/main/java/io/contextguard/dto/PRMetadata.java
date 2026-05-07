package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PRMetadata {
    private String title;
    private String author;
    private String createdAt;
    private String updatedAt;
    private String baseBranch;
    private String headBranch;
    private String headSha;
    private String baseSha;
    private String headRepo;
    private String baseRepo;
    private String prUrl;
    private String body;
    /** null = GitHub hasn't computed yet; true = no conflicts; false = conflicts exist */
    private Boolean mergeable;
    /** clean | dirty | unstable | blocked | behind | draft | unknown */
    private String mergeableState;
}
