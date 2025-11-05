//package io.contextguard.demo.service;
//package io.contextguard.service;
//
//import io.contextguard.model.Review;
//import io.contextguard.model.Snapshot;
//import io.contextguard.service.ScoringService;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.BeforeEach;
//import  org.junit.jupiter.api.Assertions.*;
//import java.time.LocalDateTime;
//import java.util.*;
//
//class ScoringServiceTest {
//
//    private ScoringService scoringService;
//
//    @BeforeEach
//    void setUp() {
//        scoringService = new ScoringService();
//    }
//
//    @Test
//    void shouldCalculateHighScoreForRecentPR() {
//        Review review = Review.builder()
//                                .createdAt(LocalDateTime.now().minusHours(1))
//
//                                .build();
//
//        Snapshot snapshot = Snapshot.builder()
//                                    .commitList(List.of(
//                                            Map.of("sha", "abc", "message", "Fix bug"),
//                                            Map.of("sha", "def", "message", "Add test")
//                                    ))
//                                    .ticketIds(List.of("JIRA-123"))
//                                    .build();
//
//        int score = scoringService.calculateContextScore(review, snapshot);
//
//        assertTrue(score > 80, "Recent PR should have high score");
//    }
//
//    @Test
//    void shouldCalculateLowScoreForOldPR() {
//        Review review = Review.builder()
//                                .createdAt(LocalDateTime.now().minusDays(3))
//                                .build();
//
//        Snapshot snapshot = Snapshot.builder()
//                                    .commitList(List.of(Map.of("message", "Fix")))
//                                    .build();
//
//        int score = scoringService.calculateContextScore(review, snapshot);
//
//        assertTrue(score < 40, "Old PR should have low score");
//    }
//
//    @Test
//    void shouldIncreaseScoreWithMoreCommits() {
//        Review review = Review.builder()
//                                .createdAt(LocalDateTime.now().minusHours(2))
//                                .build();
//
//        Snapshot withFewCommits = Snapshot.builder()
//                                          .commitList(List.of(Map.of("message", "Fix")))
//                                          .build();
//
//        List<Map<String, String>> manyCommits = new ArrayList<>();
//        for (int i = 0; i < 5; i++) {
//            manyCommits.add(Map.of("message", "Commit " + i));
//        }
//
//        Snapshot withManyCommits = Snapshot.builder()
//                                           .commitList(manyCommits)
//                                           .build();
//
//        int scoreFew = scoringService.calculateContextScore(review, withFewCommits);
//        int scoreMany = scoringService.calculateContextScore(review, withManyCommits);
//
//        assertTrue(scoreMany > scoreFew, "More commits should increase score");
//    }
//
//    @Test
//    void shouldIncreaseScoreWithTicket() {
//        Review review = Review.builder()
//                                .createdAt(LocalDateTime.now().minusHours(2))
//                                .build();
//
//        Snapshot withoutTicket = Snapshot.builder()
//                                         .commitList(List.of(Map.of("message", "Fix")))
//                                         .build();
//
//        Snapshot withTicket = Snapshot.builder()
//                                      .commitList(List.of(Map.of("message", "Fix")))
//                                      .ticketIds(List.of("JIRA-123"))
//                                      .build();
//
//        int scoreWithout = scoringService.calculateContextScore(review, withoutTicket);
//        int scoreWith = scoringService.calculateContextScore(review, withTicket);
//
//        assertEquals(15, scoreWith - scoreWithout, "Ticket should add 15 points");
//    }
//
//    @Test
//    void shouldDetectDecay() {
//        Review inactiveReview = Review.builder()
//                                        .status("open")
//                                        .createdAt(LocalDateTime.now().minusDays(2))
//                                        .lastActivityAt(LocalDateTime.now().minusHours(30))
//                                        .build();
//
//        boolean hasDecayed = scoringService.hasDecayed(inactiveReview, 40, 24);
//        assertTrue(hasDecayed, "Review inactive >24h should have decayed");
//    }
//
//    @Test
//    void shouldNotDetectDecayForClosedReview() {
//        Review closedReview = Review.builder()
//                                      .status("closed")
//                                      .createdAt(LocalDateTime.now().minusDays(2))
//                                      .lastActivityAt(LocalDateTime.now().minusHours(30))
//                                      .build();
//
//        boolean hasDecayed = scoringService.hasDecayed(closedReview, 40, 24);
//        assertFalse(hasDecayed, "Closed review should not decay");
//    }
//}
//
