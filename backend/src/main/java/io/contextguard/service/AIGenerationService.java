package io.contextguard.service;

import io.contextguard.client.AIClient;
import io.contextguard.client.AIProvider;
import io.contextguard.client.AIRouter;
import io.contextguard.dto.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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

    public AIGenerationService(AIRouter aiRouter) {
        this.aiRouter = aiRouter;
        this.promptTemplate = loadPromptTemplate();
    }

    public AIGeneratedNarrative generateSummary(
            PRMetadata metadata,
            DiffMetrics metrics,
            RiskAssessment risk,
            AIProvider provider) {


        String prompt = buildPrompt(metadata, metrics, risk);
        try {
            AIClient client = aiRouter.getClient(provider);
            String aiResponse = client.generateSummary(prompt);
            String sanitized = sanitizeAIResponse(aiResponse);

            return AIGeneratedNarrative.builder()
                           .overview(extractSection(sanitized, "OVERVIEW"))
                           .keyChanges(extractSection(sanitized, "KEY_CHANGES"))
                           .potentialConcerns(extractSection(sanitized, "CONCERNS"))
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
            RiskAssessment risk) {

        String fileSummaries = formatFileSummaries(
                metrics.getFileChanges().stream()
                        .filter(f -> f.getBeforeSnippet() != null || f.getAfterSnippet() != null)
                        .toList()
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
                fileSummaries
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
                       .overview("This PR modifies " + metrics.getTotalFilesChanged() + " files.")
                       .keyChanges("AI summary unavailable. See metrics for details.")
                       .potentialConcerns("Review large file changes manually.")
                       .generatedAt(java.time.Instant.now())
                       .build();
    }

    private String loadPromptTemplate() {
        return """
You are generating a **technical pull request summary** for a software engineer.

You will receive **structured data only** (no raw diffs, no full files).
You must base your summary **strictly on the provided information**.

━━━━━━━━━━━━━━━━━━━━━
PULL REQUEST CONTEXT
━━━━━━━━━━━━━━━━━━━━━━

PR Title:
%s

PR Description (written by the author):
%s

Base Branch → Head Branch:
%s → %s

━━━━━━━━━━━━━━━━━━━━━━
CHANGE METRICS
━━━━━━━━━━━━━━━━━━━━━━

Files changed: %d
Lines added: %d
Lines deleted: %d
File types involved: %s
Overall risk level: %s
Critical files detected: %s

━━━━━━━━━━━━━━━━━━━━━━
FILE-LEVEL CHANGE DETAILS
━━━━━━━━━━━━━━━━━━━━━━

Below are summaries of the most relevant file changes.
Some files include small **before/after code snippets** (truncated and contextual).

%s

━━━━━━━━━━━━━━━━━━━━━━
INSTRUCTIONS
━━━━━━━━━━━━━━━━━━━━━━

Generate a clear, high-signal PR summary that helps a reviewer understand:
- WHAT changed
- WHY it changed
- HOW behavior differs before vs after (when snippets are available)

━━━━━━━━━━━━━━━━━━━━━━
OUTPUT FORMAT (STRICT)
━━━━━━━━━━━━━━━━━━━━━━

OVERVIEW:
- 3–5 sentences describing the intent and scope of the PR.
- Ground your summary in the PR description and affected areas.
- Mention key modules/files when relevant.

BEFORE_BEHAVIOR:
- Describe system behavior before this PR.
- Use before-snippets if available.
- If behavior cannot be determined, write: "Not determinable from provided context."

AFTER_BEHAVIOR:
- Describe system behavior after this PR.
- Use after-snippets if available.
- If behavior cannot be determined, write: "Not determinable from provided context."

KEY_CHANGES:
- Bullet list of concrete changes.
- Each bullet MUST reference a filename.
- Focus on behavior or configuration changes, not syntax.

POTENTIAL_CONCERNS:
- Bullet list of risks, edge cases, or review points.
- Base concerns ONLY on provided risk level, complexity, or test impact.
- Do NOT suggest fixes or improvements.

CHECKLIST:
- Bullet list of checks that user must perform after merging.

━━━━━━━━━━━━━━━━━━━━━━
STRICT RULES
━━━━━━━━━━━━━━━━━━━━━━

- Do NOT include code blocks or code suggestions.
- Do NOT make recommendations or implementation advice.
- Do NOT invent behavior or infer beyond the given context.
- If information is missing, explicitly say it is not determinable.
- Keep total output under 400 words.
- Use clear, professional language suitable for a senior code review.

Now generate the summary.
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

    private String formatFileSummaries(List<FileChangeSummary> files) {
        StringBuilder sb = new StringBuilder();

        for (FileChangeSummary f : files) {
            sb.append("File: ").append(f.getFilename()).append("\n");
            sb.append("Change type: ").append(f.getChangeType()).append("\n");
            sb.append("Risk level: ").append(f.getRiskLevel()).append("\n");

            if (f.getReason() != null && !f.getReason().isBlank()) {
                sb.append("Reason: ").append(f.getReason()).append("\n");
            }

            if (f.getBeforeSnippet() != null) {
                sb.append("Before snippet:\n");
                sb.append(f.getBeforeSnippet()).append("\n");
            }

            if (f.getAfterSnippet() != null) {
                sb.append("After snippet:\n");
                sb.append(f.getAfterSnippet()).append("\n");
            }

            sb.append("----\n");
        }

        return sb.toString();
    }

}