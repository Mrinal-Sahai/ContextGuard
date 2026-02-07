package io.contextguard.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single file change in a GitHub PR.
 *
 * Maps directly to GitHub API's file object structure.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GitHubFile {

    /**
     * Full file path (e.g., "src/main/java/com/example/Service.java")
     */
    private String filename;

    /**
     * Change status: "added", "modified", "removed", "renamed"
     */
    private String status;

    /**
     * Number of lines added in this file
     */
    private int additions;

    /**
     * Number of lines deleted in this file
     */
    private int deletions;

    /**
     * Unified diff patch content (GitHub's diff format)
     * Example:
     * @@ -10,7 +10,6 @@
     *  context line
     * -deleted line
     * +added line
     */
    private String patch;
}
