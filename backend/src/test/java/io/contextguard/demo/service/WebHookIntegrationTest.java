//package io.contextguard.integration;
//
//import io.contextguard.model.Review;
//import io.contextguard.model.Snapshot;
//import io.contextguard.repository.ReviewRepository;
//import io.contextguard.repository.SnapshotRepository;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.test.web.client.TestRestTemplate;
//import org.springframework.http.*;
//import org.springframework.test.context.DynamicPropertyRegistry;
//import org.springframework.test.context.DynamicPropertySource;
//import org.testcontainers.containers.PostgreSQLContainer;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//import static org.junit.jupiter.api.Assertions.*;
//import java.util.Optional;
//
//@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
//@Testcontainers
//class WebhookIntegrationTest {
//
//    @Container
//    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
//                                                     .withDatabaseName("testdb")
//                                                     .withUsername("test")
//                                                     .withPassword("test");
//
//    @DynamicPropertySource
//    static void configureProperties(DynamicPropertyRegistry registry) {
//        registry.add("spring.datasource.url", postgres::getJdbcUrl);
//        registry.add("spring.datasource.username", postgres::getUsername);
//        registry.add("spring.datasource.password", postgres::getPassword);
//    }
//    @Autowired
//    private TestRestTemplate restTemplate;
//
//    @Autowired
//    private ReviewRepository reviewRepository;
//
//    @Autowired
//    private SnapshotRepository snapshotRepository;
//
//    @Test
//    void shouldProcessGitHubWebhookEndToEnd() {
//        String payload = """
//            {
//              "action": "opened",
//              "pull_request": {
//                "number": 42,
//                "title": "Add feature X",
//                "body": "This PR adds feature X",
//                "state": "open",
//                "user": {"login": "testuser"},
//                "head": {"sha": "abc123"},
//                "html_url": "https://github.com/test/repo/pull/42"
//              },
//              "repository": {
//                "name": "repo",
//                "owner": {"login": "test"},
//                "full_name": "test/repo"
//              }
//            }
//            """;
//        HttpHeaders headers = new HttpHeaders();
//        headers.setContentType(MediaType.APPLICATION_JSON);
//        headers.set("X-Hub-Signature-256", "sha256=fake-signature-for-test");
//
//        HttpEntity<String> request = new HttpEntity<>(payload, headers);
//
//        ResponseEntity<String> response = restTemplate.postForEntity(
//                "/v1/webhook/github", request, String.class);
//
//        // Verify webhook was processed
//        assertEquals(HttpStatus.OK, response.getStatusCode());
//
//        // Verify review was created in DB
//        Optional<Review> review = reviewRepository
//                                          .findByPlatformAndOwnerAndRepositoryAndExternalId(
//                                                  "github", "test", "repo", "42");
//        assertTrue(review.isPresent());
//        assertEquals("Add feature X", review.get().getTitle());
//        assertEquals("testuser", review.get().getAuthor());
//
//        // Verify snapshot was created
//        Optional<Snapshot> snapshot = snapshotRepository
//                                              .findLatestByReviewId(review.get().getId());
//
//        assertTrue(snapshot.isPresent());
//        assertEquals("webhook", snapshot.get().getCreatedBy());
//        assertNotNull(snapshot.get().getDiffUrl());
//    }
//}
//
