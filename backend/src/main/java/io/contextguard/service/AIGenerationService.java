package io.contextguard.service;

import io.contextguard.client.AIClient;
import io.contextguard.client.AIProvider;
import io.contextguard.client.AIRouter;
import io.contextguard.dto.*;
import io.contextguard.engine.DiffParser;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generates natural language summary using AI.
 *
 * CRITICAL CONSTRAINTS:
 * - AI receives ONLY structured data (no raw code diffs)
 * - AI output is NARRATIVE, not analysis
 * - AI cannot influence risk scores (already computed)
 *
 * HALLUCINATION MITIGATION:
 * 1. Structured prompt with strict output format
 * 2. No open-ended questions to AI
 * 3. Constrained output length (max 500 words)
 * 4. Validation: Discard AI output if it contains code suggestions
 */
@Service
public class AIGenerationService {

    private final AIRouter aiRouter;
    private final String promptTemplate;
    private final DiffParser diffParser ;

    public AIGenerationService(AIRouter aiRouter, DiffParser diffParser) {
        this.aiRouter = aiRouter;
        this.diffParser = diffParser;
        this.promptTemplate = loadPromptTemplate();
    }

    public AIGeneratedNarrative generateSummary(List<GitHubFile> files,
            PRMetadata metadata,
            DiffMetrics metrics,
            RiskAssessment risk,
            AIProvider provider) {


        String prompt = buildPrompt(metadata, metrics, risk, files);
        try {
            AIClient client = aiRouter.getClient(provider);
            String aiResponse = client.generateSummary(prompt);
            String sanitized = sanitizeAIResponse(aiResponse);

            return AIGeneratedNarrative.builder()
                           .overview(extractSection(sanitized, "OVERVIEW"))
                           .structuralImpact(extractSection(sanitized, "STRUCTURAL_IMPACT"))
                           .behavioralChanges(extractSection(sanitized, "BEHAVIORAL_CHANGES"))
                           .riskInterpretation(extractSection(sanitized, "RISK_INTERPRETATION"))
                           .reviewFocus(extractSection(sanitized, "REVIEW_FOCUS"))
                           .checklist(extractSection(sanitized, "CHECKLIST"))
                           .confidence(extractSection(sanitized, "CONFIDENCE"))
                           .generatedAt(java.time.Instant.now())
                           .build();

        } catch (Exception e) {
            // Fallback: Return template-based summary if AI fails
            return generateFallbackSummary(metadata, metrics);
        }
    }

    /**
     * Build structured prompt with strict output format.
     *
     * Prompt Engineering Principles:
     * - Provide concrete data, not vague descriptions
     * - Specify output structure explicitly
     * - Prohibit code generation or recommendations
     */
    private String buildPrompt(
            PRMetadata metadata,
            DiffMetrics metrics,
            RiskAssessment risk,
            List<GitHubFile> files
    ) {

        String fileEvidence = formatFileSummaries(
                selectTopRelevantFiles(metrics.getFileChanges()), files
        );

        return String.format(
                promptTemplate,
                metadata.getTitle(),
                safe(metadata.getBody()),
                metadata.getBaseBranch(),
                metadata.getHeadBranch(),
                metrics.getTotalFilesChanged(),
                metrics.getLinesAdded(),
                metrics.getLinesDeleted(),
                formatFileTypes(metrics.getFileTypeDistribution()),
                risk.getLevel(),
                formatCriticalFiles(metrics.getCriticalFiles()),
                fileEvidence
        );
    }


    private String safe(String s) {
        return s == null ? "Not provided." : s;
    }


    /**
     * Sanitize AI response to prevent code injection or excessive content.
     */
    private String sanitizeAIResponse(String response) {
        // Remove code blocks (```)
        String cleaned = response.replaceAll("```[\\s\\S]*?```", "[CODE_REMOVED]");
        // Truncate to 500 words
        String[] words = cleaned.split("\\s+");
        if (words.length > 500) {
            cleaned = String.join(" ", java.util.Arrays.copyOf(words, 500)) + "...";
        }
        return cleaned;
    }

    private AIGeneratedNarrative generateFallbackSummary(PRMetadata metadata, DiffMetrics metrics) {
        return AIGeneratedNarrative.builder()
                .overview("This pull request contains changes to the following files: " + metrics.getFileChanges())
                .structuralImpact("No structural impact identified.")
                .behavioralChanges("No behavioral changes identified.")
                .riskInterpretation("No risk interpretation provided.")
                .reviewFocus("No review focus provided.")
                .checklist("No checklist provided.")
                .confidence("No confidence level provided.")
                .generatedAt(java.time.Instant.now())
                .build();
    }

    private String loadPromptTemplate() {
        return """
You are generating a senior-level technical pull request summary.

Base your analysis ONLY on the structured evidence provided.
Do NOT assume undocumented behavior.

PR CONTEXT
Title:%s
Author Description:%s
Branch Change:%s → %s


PR METRICS
Files Changed: %d
Lines Added: %d
Lines Deleted: %d
File Types: %s
Overall Risk Level: %s
Critical Files: %s


FILE-LEVEL EVIDENCE

%s

Each file block may include:
- change type
- risk level
- critical detection reasons
- method changes
- added lines
- deleted lines



GENERATE  a high-signal summary explaining:

1. INTENT
   - What problem this PR addresses (based ONLY on description).

2. STRUCTURAL_IMPACT 
   - How this PR affects the codebase.
   - How this PR affects the business logic.
   - Localized or cross-cutting impact.
   - Mention critical files.

3. BEHAVIORAL_CHANGES
   Infer only from snippets or method changes.
   If unclear: "Not determinable from provided data."

4. RISK_INTERPRETATION
   Explain risk using complexity, deletions, criticality.

5. REVIEW_FOCUS
   Concrete review areas (no improvement suggestions).

6. CHECKLIST
   Operational checks inferred from evidence.
   
7. CONFIDENCE
   HIGH / MEDIUM / LOW with brief reason.
   Based only on completeness of provided data.


OUTPUT FORMAT (strict)

OVERVIEW:
[4–6 sentences]

STRUCTURAL_IMPACT:
- ...

BEHAVIORAL_CHANGES:
- ...

RISK_INTERPRETATION:
- ...

REVIEW_FOCUS:
- ...

CHECKLIST:
- ...

CONFIDENCE:
- ...

RULES
- Reference filenames when applicable.
- No code blocks.
- No speculation beyond evidence.
- Keep under 500 words.

Generate the summary.
""";
    }


    private String extractSection(String text, String section) {
        if (text == null || section == null) {
            return "";
        }
        String regex = "(?s)"
                               + section + "\\s*:\\s*"
                               + "(.*?)"
                               + "(?=\\n[A-Z_]+\\s*:|\\z)";

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        String content = matcher.group(1).trim();
        if (content.equalsIgnoreCase("none") ||
                    content.matches("(?i)-\\s*none")) {
            return "";
        }
        return content;
    }


    private String formatFileTypes(Map<String, Integer> distribution) {
        return distribution.entrySet().stream()
                       .map(e -> e.getKey() + " (" + e.getValue() + ")")
                       .collect(java.util.stream.Collectors.joining(", "));
    }

    private String formatCriticalFiles(List<String> files) {
        return files.isEmpty() ? "None" : String.join(", ", files);
    }

    private String formatFileSummaries(List<FileChangeSummary> files, List<GitHubFile> ghFiles) {
        StringBuilder sb = new StringBuilder();
        Map<String, GitHubFile> ghFileMap = ghFiles.stream().collect(Collectors.toMap(GitHubFile::getFilename,
                                                            Function.identity(),
                                                            (a, b) -> a));

        for (FileChangeSummary f : files) {
            GitHubFile ghFile = ghFileMap.get(f.getFilename());
            List<String> addedLines = summarizeLines(
                    diffParser.extractAddedLines(ghFile.getPatch())
            );

            List<String> removedLines = summarizeLines(
                    diffParser.extractDeletedLines(ghFile.getPatch())
            );


            sb.append("File: ").append(f.getFilename()).append("\n");
            sb.append("Change type: ").append(f.getChangeType()).append("\n");
            sb.append("Risk level: ").append(f.getRiskLevel()).append("\n");
            if(f.getCriticalDetectionResult() != null) {
                sb.append("Risk Reasons: ").append(String.join(", ", f.getCriticalDetectionResult().getReasons())).append("\n");
            }


            if (f.getReason() != null && !f.getReason().isBlank()) {
                sb.append("Reason: ").append(f.getReason()).append("\n");
            }

            if (!addedLines.isEmpty()) {
                sb.append("ADDED LINES:\n");
                for (String line : addedLines) {
                    sb.append("- ").append(line).append("\n");
                }
            }

            if (!removedLines.isEmpty()) {
                sb.append("DELETED LINES:\n");
                for (String line : removedLines) {
                    sb.append("- ").append(line).append("\n");
                }
            }

            sb.append("----\n");
        }

        return sb.toString();
    }

    private List<FileChangeSummary> selectTopRelevantFiles(List<FileChangeSummary> files) {
        return files.stream()
                       .sorted(Comparator
                                       .comparing(FileChangeSummary::getRiskLevel).reversed()
                                       .thenComparing(f -> Math.abs(f.getComplexityDelta()), Comparator.reverseOrder()))
                       .limit(5)
                       .toList();
    }

    private List<String> summarizeLines(List<String> lines) {
        return lines.stream()
                       .map(String::trim)
                       .filter(l -> !l.isBlank())
                       .filter(l -> l.length() > 3)
                       .filter(l -> !l.matches("[{}();]+"))
                       .filter(l -> !l.startsWith("//"))
                       .limit(8)
                       .toList();
    }



}