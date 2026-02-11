package io.contextguard.service.criticalpath;

import io.contextguard.dto.GitHubFile;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Simple textual import scanner:
 * - scans added/modified files for import/require patterns
 * - counts references to other filenames among changed files
 *
 * This is a heuristic (fast) and gives an "in-degree" count per filename.
 */
@Component
public class ImportAnalyzer {

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "\\b(import|require|from)\\b\\s+['\\\"]?([\\w\\./-]+)['\\\"]?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Returns map: filename -> in-degree (how many other changed files reference it)
     */
    public Map<String, Integer> computeImportInDegree(List<GitHubFile> files) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, GitHubFile> byBasename = new HashMap<>();
        for (GitHubFile f : files) {
            byBasename.put(simpleName(f.getFilename()), f);
        }

        for (GitHubFile f : files) {
            String patch = f.getPatch();
            if (patch == null) continue;
            for (String line : patch.split("\n")) {
                var m = IMPORT_PATTERN.matcher(line);
                while (m.find()) {
                    String target = m.group(2);
                    String possible = guessFilenameFromImport(target);
                    if (possible != null && byBasename.containsKey(possible)) {
                        String key = byBasename.get(possible).getFilename();
                        inDegree.merge(key, 1, Integer::sum);
                    }
                }
            }
        }
        return inDegree;
    }

    private static String simpleName(String filename) {
        int idx = filename.lastIndexOf('/');
        String base = idx >= 0 ? filename.substring(idx + 1) : filename;
        return base.toLowerCase();
    }

    private static String guessFilenameFromImport(String importToken) {
        // crude heuristic: take last segment and append common extensions
        if (importToken == null) return null;
        String[] parts = importToken.split("/");
        String last = parts[parts.length - 1].toLowerCase();
        // common extensions to try
        if (last.contains(".")) return last;
        String[] tryExt = new String[] { last + ".java", last + ".rb", last + ".js", last + ".py" };
        for (String s : tryExt) return s; // return first; calling code checks existence
        return last;
    }
}

