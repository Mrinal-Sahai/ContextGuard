package io.contextguard.engine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses unified diff format to extract added/deleted lines.
 *
 * Unified Diff Format:
 * @@ -10,7 +10,6 @@
 *  context line
 * -deleted line
 * +added line
 *
 * WHY THIS EXISTS:
 * GitHub API returns diffs in unified format. We need to parse this
 * to extract actual code changes for complexity estimation.
 */
@Component
public class DiffParser {

    private static final Pattern ADDED_LINE = Pattern.compile("^\\+(.*)");
    private static final Pattern DELETED_LINE = Pattern.compile("^-(.*)");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@\\s*-(\\d+),(\\d+)\\s*\\+(\\d+),(\\d+)\\s*@@.*$");


    public List<String> extractAddedLines(String patch) {

        if (patch == null) return List.of();

        List<String> added = new ArrayList<>();
        for (String line : patch.split("\n")) {
            Matcher matcher = ADDED_LINE.matcher(line);
            if (matcher.matches() && !line.startsWith("+++")) {
                added.add(matcher.group(1));
            }
        }
        return added;
    }

    public List<String> extractDeletedLines(String patch) {

        if (patch == null) return List.of();

        List<String> deleted = new ArrayList<>();
        for (String line : patch.split("\n")) {
            Matcher matcher = DELETED_LINE.matcher(line);
            if (matcher.matches() && !line.startsWith("---")) {
                deleted.add(matcher.group(1));
            }
        }
        return deleted;
    }
    /**
     * Parse the unified diff (patch) into a list of DiffHunk objects.
     */
    public List<DiffHunk> parseHunks(String patch) {
        List<DiffHunk> hunks = new ArrayList<>();
        if (patch == null || patch.isEmpty()) return hunks;

        String[] lines = patch.split("\n");
        int idx = 0;
        while (idx < lines.length) {
            String line = lines[idx];
            Matcher m = HUNK_HEADER.matcher(line);
            if (m.matches()) {
                int oldStart = Integer.parseInt(m.group(1));
                int oldCount = Integer.parseInt(m.group(2));
                int newStart = Integer.parseInt(m.group(3));
                int newCount = Integer.parseInt(m.group(4));

                idx++; // advance to first hunk content line
                List<String> hunkLines = new ArrayList<>();
                while (idx < lines.length && !lines[idx].startsWith("@@")) {
                    hunkLines.add(lines[idx]);
                    idx++;
                }
                DiffHunk h = new DiffHunk(oldStart, oldCount, newStart, newCount, hunkLines);
                hunks.add(h);
            } else {
                idx++;
            }
        }
        return hunks;
    }

}
