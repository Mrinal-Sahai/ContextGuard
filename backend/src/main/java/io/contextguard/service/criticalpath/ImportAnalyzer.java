package io.contextguard.service.criticalpath;

import io.contextguard.dto.GitHubFile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
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
            "^\\+.*\\b(import|require|from)\\b.*",
            Pattern.CASE_INSENSITIVE
    );

    public Map<String, Integer> computeImportInDegree(List<GitHubFile> files) {

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, GitHubFile> byFullPath = new HashMap<>();
        Map<String, List<GitHubFile>> byBaseName = new HashMap<>();

        for (GitHubFile f : files) {
            byFullPath.put(f.getFilename(), f);
            byBaseName
                    .computeIfAbsent(simpleName(f.getFilename()), k -> new ArrayList<>())
                    .add(f);
        }

        for (GitHubFile source : files) {
            String patch = source.getPatch();
            if (patch == null) continue;

            for (String line : patch.split("\n")) {

                // Only consider added lines
                if (!line.startsWith("+") || line.startsWith("+++"))
                    continue;

                if (!IMPORT_PATTERN.matcher(line).find())
                    continue;

                String token = extractImportTarget(line);
                if (token == null) continue;

                String normalized = normalizeImport(token);

                // Try full-path match first
                for (GitHubFile candidate : files) {
                    if (candidate.getFilename().contains(normalized)) {
                        inDegree.merge(candidate.getFilename(), 1, Integer::sum);
                    }
                }

                // Fallback: basename match
                List<GitHubFile> matches =
                        byBaseName.get(simpleName(normalized));

                if (matches != null) {
                    for (GitHubFile match : matches) {
                        inDegree.merge(match.getFilename(), 1, Integer::sum);
                    }
                }
            }
        }

        return inDegree;
    }

    private static String extractImportTarget(String line) {
        Pattern p = Pattern.compile("['\"]([^'\"]+)['\"]");
        Matcher m = p.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private static String normalizeImport(String token) {
        token = token.replace("./", "")
                        .replace("../", "");
        return token;
    }

    private static String simpleName(String path) {
        int idx = path.lastIndexOf('/');
        return (idx >= 0 ? path.substring(idx + 1) : path)
                       .toLowerCase();
    }
}

