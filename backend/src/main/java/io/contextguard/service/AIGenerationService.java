package io.contextguard.service;

import io.contextguard.client.AIClient;
import io.contextguard.client.AIProvider;
import io.contextguard.client.AIRouter;
import io.contextguard.dto.AIGeneratedNarrative;
import io.contextguard.dto.DiffMetrics;
import io.contextguard.dto.PRMetadata;
import io.contextguard.dto.RiskAssessment;
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
    private String buildPrompt(PRMetadata metadata, DiffMetrics metrics, RiskAssessment risk) {

        return String.format(promptTemplate,
                metadata.getTitle(),
                metrics.getTotalFilesChanged(),
                metrics.getLinesAdded(),
                metrics.getLinesDeleted(),
                formatFileTypes(metrics.getFileTypeDistribution()),
                risk.getLevel(),
                formatCriticalFiles(metrics.getCriticalFiles())
        );

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
        return  """
         Analyze this GitHub pull request and generate a summary.
         PR Title: %s
         Files Changed: %d
         Lines Added: %d
         Lines Deleted: %d
         File Types: %s
         Risk Level: %s
         Critical Files: %s

         Generate output in EXACTLY this format:

         OVERVIEW:
         [One-sentence summary]

         KEY_CHANGES:
         - [Change 1]
         - [Change 2]
         - [Change 3]

         CONCERNS:
         - [Concern 1]
         - [Concern 2]

         RULES:
         - Do NOT include code snippets
         - Do NOT make recommendations
         - Maximum 300 words total
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
}