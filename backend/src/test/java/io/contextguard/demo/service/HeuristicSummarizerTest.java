//package io.contextguard.demo.service;
//
//import io.contextguard.dto.SummaryData;
//import io.contextguard.model.Review;
//import io.contextguard.model.Snapshot;
//import io.contextguard.service.summarizer.HeuristicSummarizer;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.BeforeEach;
//import static org.junit.jupiter.api.Assertions.*;
//import java.util.*;
//
//class HeuristicSummarizerTest {
//
//    private HeuristicSummarizer summarizer;
//
//    @BeforeEach
//    void setUp() {
//        summarizer = new HeuristicSummarizer();
//    }
//
//    @Test
//    void shouldGenerateSummaryFromPRBody() {
//        Snapshot snapshot = Snapshot.builder()
//                                    .prBody("This PR adds user authentication to the API. It implements JWT-based auth.")
//                                    .commitList(List.of(
//                                            Map.of("sha", "abc123", "message", "Add JWT authentication"),
//                                            Map.of("sha", "def456", "message", "Add login endpoint"),
//                                            Map.of("sha", "ghi789", "message", "Add tests for auth")
//                                    ))
//                                    .build();
//
//        SummaryData result = summarizer.summarize(snapshot);
//
//        assertNotNull(result.getSummary());
//        assertFalse(result.getSummary().isEmpty());
//        assertTrue(result.getSummary().length() <= 200);
//    }
//
//    @Test
//    void shouldExtractWhy() {
//        Snapshot snapshot = Snapshot.builder()
//                                    .prBody("This change fixes login issues because the previous implementation had timing bugs.")
//                                    .commitList(List.of())
//                                    .build();
//
//        SummaryData result = summarizer.summarize(snapshot);
//
//        assertNotNull(result.getWhy());
//        assertTrue(result.getWhy().toLowerCase().contains("because"));
//    }
//
//    @Test
//    void shouldGenerateReviewChecklist() {
//        Snapshot snapshot = Snapshot.builder()
//                                    .prBody("Adding new API endpoint")
//                                    .commitList(List.of(
//                                            Map.of("message", "Add API endpoint for user profiles")
//                                    ))
//                                    .build();
//        SummaryData result = summarizer.summarize(snapshot);
//
//        assertNotNull(result.getReviewChecklist());
//        assertEquals(3, result.getReviewChecklist().size());
//        assertTrue(result.getReviewChecklist().get(0).contains("tests"));
//    }
//
//    @Test
//    void shouldIdentifyAPIRelatedRisks() {
//        Snapshot snapshot = Snapshot.builder()
//                                    .prBody("Update API contract")
//                                    .commitList(List.of(
//                                            Map.of("message", "Change API response format")
//                                    ))
//                                    .build();
//
//        SummaryData result = summarizer.summarize(snapshot);
//
//        assertNotNull(result.getReviewChecklist());
//        assertTrue(result.getReviewChecklist().stream()
//                           .anyMatch(item -> item.toLowerCase().contains("api")));
//    }
//
//    @Test
//    void shouldIdentifyDatabaseRisks() {
//        Snapshot snapshot = Snapshot.builder()
//                                    .prBody("Database migration for user table")
//                                    .commitList(List.of(
//                                            Map.of("message", "Add migration script")
//                                    ))
//                                    .build();
//
//        SummaryData result = summarizer.summarize(snapshot);
//
//        assertNotNull(result.getRisks());
//        assertTrue(result.getRisks().stream()
//                           .anyMatch(risk -> risk.toLowerCase().contains("database")));
//    }
//
//    @Test
//    void shouldIdentifySecurityRisks() {
//        Snapshot snapshot = Snapshot.builder()
//                                    .prBody("Update authentication logic")
//                                    .commitList(List.of(
//                                            Map.of("message", "Modify security checks")
//                                    ))
//                                    .build();
//
//        SummaryData result = summarizer.summarize(snapshot);
//
//        assertNotNull(result.getRisks());
//        assertTrue(result.getRisks().stream()
//                           .anyMatch(risk -> risk.toLowerCase().contains("security")));
//    }
//
//    @Test
//    void shouldHandleLargeCommitCount() {
//        List<Map<String, String>> manyCommits = new ArrayList<>();
//        for (int i = 0; i < 15; i++) {
//            manyCommits.add(Map.of("message", "Commit " + i));
//        }
//
//        Snapshot snapshot = Snapshot.builder()
//                                    .prBody("Large refactoring")
//                                    .commitList(manyCommits)
//                                    .build();
//
//        SummaryData result = summarizer.summarize(snapshot);
//
//        assertTrue(result.getRisks().stream()
//                           .anyMatch(risk -> risk.toLowerCase().contains("large")));
//    }
//}
