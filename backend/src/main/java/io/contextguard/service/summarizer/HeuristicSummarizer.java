package io.contextguard.service.summarizer;

import io.contextguard.dto.SummaryData;
import io.contextguard.model.Snapshot;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class HeuristicSummarizer implements Summarizer {

    private static final Pattern TICKET_PATTERN =
            Pattern.compile("(JIRA-\\d+|#\\d+|[A-Z]+-\\d+)");

    @Override
    public SummaryData summarize(Snapshot snapshot) {
        SummaryData summary = new SummaryData();
        List<String> commitMessages = extractCommitMessages(snapshot);
        String prBody = snapshot.getPrBody() != null ? snapshot.getPrBody() : "";
        String combinedText = prBody + " " + String.join(" ", commitMessages);
        summary.setSummary(generateShortSummary(combinedText));
        summary.setWhy(extractWhy(prBody, commitMessages));
        summary.setReviewChecklist(generateChecklist(snapshot, commitMessages));
        summary.setRisks(identifyRisks(combinedText, commitMessages));
        return summary;
    }

    private List<String> extractCommitMessages(Snapshot snapshot) {
        List<String> messages = new ArrayList<>();
        if (snapshot.getCommitList() != null) {
            for (Map<String, String> commit : snapshot.getCommitList()) {
                if (messages.size() >= 3) break;
                String message = commit.get("message");
                if (message != null && !message.trim().isEmpty()) {
                    messages.add(message.split("\n")[0]); // First line only
                }
            }
        }
        return messages;
    }

    private String generateShortSummary(String text) {
        // Extract first meaningful sentence
        String[] sentences = text.split("\\.");
        if (sentences.length > 0) {
            String firstSentence = sentences[0].trim();
            if (firstSentence.length() > 150) {
                return firstSentence.substring(0, 147) + "...";
            }
            return firstSentence + ".";
        }
        return "Code review for changes in this pull request.";
    }

        private String extractWhy(String prBody, List<String> commits) {
            // Look for "why" indicators
            String lowerBody = prBody.toLowerCase();

            if (lowerBody.contains("because")) {
                int idx = lowerBody.indexOf("because");
                return prBody.substring(idx, Math.min(idx + 200, prBody.length())).trim();
            }

            if (lowerBody.contains("fixes") || lowerBody.contains("resolves")) {
                return "Addresses reported issue or bug.";
            }

            if (!commits.isEmpty()) {
                return "Implements changes described in: " + commits.get(0);
            }

            return "Updates codebase functionality.";
        }

        private List<String> generateChecklist(Snapshot snapshot, List<String> commits) {
            List<String> checklist = new ArrayList<>();
            // Always include these base items
            checklist.add("Verify all tests pass");
            checklist.add("Check for breaking changes");

            // Add context-specific items
            String allText = String.join(" ", commits).toLowerCase();

            if (allText.contains("api") || allText.contains("endpoint")) {
                checklist.add("Review API contract changes");
            } else if (allText.contains("database") || allText.contains("migration")) {
                checklist.add("Validate database migration safety");
            } else if (allText.contains("security") || allText.contains("auth")) {
                checklist.add("Security review required");
            } else {
                checklist.add("Validate edge cases and error handling");
            }

            return checklist.subList(0, Math.min(3, checklist.size()));
        }

        private List<String> identifyRisks(String combinedText, List<String> commits) {
            List<String> risks = new ArrayList<>();
            String lowerText = combinedText.toLowerCase();

            if (lowerText.contains("breaking") || lowerText.contains("remove")) {
                risks.add("Potential breaking changes detected");
            }

            if (lowerText.contains("database") || lowerText.contains("migration")) {
                risks.add("Database schema changes require careful review");
            }

            if (lowerText.contains("security") || lowerText.contains("authentication")) {
                risks.add("Security-sensitive changes present");
            }

            if (commits.size() > 10) {
                risks.add("Large number of commits - consider breaking into smaller PRs");
            }

            if (risks.isEmpty()) {
                risks.add("No significant risks identified");
            }

            return risks.subList(0, Math.min(3, risks.size()));
        }
    }
