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

    public List<String> extractAddedLines(String patch) {
        if (patch == null || patch.isBlank()) {
            return List.of();
        }

        patch = patch.replace("\r\n", "\n");

        List<String> added = new ArrayList<>();

        for (String line : patch.split("\n")) {

            // Skip diff metadata
            if (line.startsWith("+++")
                        || line.startsWith("@@")
                        || line.startsWith("\\")
                        || line.startsWith("diff --git")) {
                continue;
            }

            Matcher matcher = ADDED_LINE.matcher(line);
            if (matcher.find() && matcher.groupCount() >= 1) {
                added.add(matcher.group(1));
            }
        }

        return added;
    }

    public List<String> extractDeletedLines(String patch) {

        if (patch == null || patch.isBlank()) {
            return List.of();
        }

        patch = patch.replace("\r\n", "\n");

        List<String> deleted = new ArrayList<>();

        for (String line : patch.split("\n")) {

            // Skip diff metadata
            if (line.startsWith("---")
                        || line.startsWith("@@")
                        || line.startsWith("\\")
                        || line.startsWith("diff --git")) {
                continue;
            }

            Matcher matcher = DELETED_LINE.matcher(line);
            if (matcher.find() && matcher.groupCount() >= 1) {
                deleted.add(matcher.group(1));
            }
        }

        return deleted;
    }


}
