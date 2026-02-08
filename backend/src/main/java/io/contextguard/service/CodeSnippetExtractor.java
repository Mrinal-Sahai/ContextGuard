package io.contextguard.service;

import io.contextguard.engine.DiffHunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts bounded "before" and "after" snippets from full file content using diff hunks.
 * Truncates output to MAX_SNIPPET_CHARS to keep sizes bounded.
 */
@Service
public class CodeSnippetExtractor {

    public static final int CONTEXT_RADIUS = 8;      // lines before/after hunk start
    public static final int MAX_SNIPPET_CHARS = 400; // truncation cap

    /**
     * Extract snippet from "before" (base branch) using hunks' oldStart positions.
     */
    public String extractBeforeSnippet(String baseFileContent, List<DiffHunk> hunks) {
        return extractSnippet(baseFileContent, hunks, /*before=*/true);
    }

    /**
     * Extract snippet from "after" (head branch) using hunks' newStart positions.
     */
    public String extractAfterSnippet(String headFileContent, List<DiffHunk> hunks) {
        return extractSnippet(headFileContent, hunks, /*before=*/false);
    }

    private String extractSnippet(String content, List<DiffHunk> hunks, boolean before) {
        if (content == null || content.isEmpty() || hunks == null || hunks.isEmpty()) {
            return null;
        }

        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        List<String> sections = new ArrayList<>();

        for (DiffHunk h : hunks) {
            int startLine = before ? h.getOldStart() : h.getNewStart(); // 1-based
            int from = Math.max(1, startLine - CONTEXT_RADIUS);
            int to = Math.min(lines.length, startLine + CONTEXT_RADIUS);

            StringBuilder s = new StringBuilder();
            for (int i = from; i <= to; i++) {
                s.append(lines[i - 1]).append("\n"); // convert to 0-based index
            }
            sections.add(s.toString());
        }

        // join sections with separator
        for (String sec : sections) {
            if (sb.length() + sec.length() > MAX_SNIPPET_CHARS) {
                int remaining = MAX_SNIPPET_CHARS - sb.length();
                if (remaining > 0) {
                    sb.append(sec, 0, Math.min(sec.length(), remaining));
                }
                sb.append("..."); // truncated
                break;
            } else {
                sb.append(sec);
                sb.append("\n---\n");
            }
        }

        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }
}
