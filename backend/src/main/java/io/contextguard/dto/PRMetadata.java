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
    private String prUrl;
    private String body;
}
