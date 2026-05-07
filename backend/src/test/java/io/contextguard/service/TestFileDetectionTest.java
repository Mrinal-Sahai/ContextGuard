package io.contextguard.service;

import io.contextguard.dto.FileChangeSummary;
import io.contextguard.dto.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for isTestFile() and computeTestCoverageGap().
 *
 * The bugs these guard against:
 *  1. isTestFile() missed Python test_ prefix, /tests/ plural, __tests__, Go _test.go,
 *     JS/TS .test.ts/.spec.tsx variants, and Java *IT.java integration tests.
 *  2. computeTestCoverageGap() returned 100% when integration-test files were present
 *     but didn't share a base-name with any production file (e.g. Fineract PR where
 *     FixedDepositTest.java tests FixedDepositAccountTransactionsApiResource.java).
 */
class TestFileDetectionTest {

    private RiskScoringEngine engine;

    @BeforeEach
    void setUp() {
        // RiskScoringEngine has no external dependencies for these two methods
        engine = new RiskScoringEngine();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isTestFile — positive cases (must all be true)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class IsTestFile_Positive {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            // Java — Maven standard layout
            "src/test/java/com/example/UserServiceTest.java",
            "src/test/java/com/example/UserServiceTests.java",
            "src/test/java/com/example/UserServiceSpec.java",
            "src/test/java/com/example/UserServiceIT.java",         // integration test
            // Fineract-style: integration-tests module
            "integration-tests/src/test/java/org/apache/fineract/integrationtests/FixedDepositTest.java",
            "integration-tests/src/test/java/org/apache/fineract/integrationtests/FixedDepositAccountHelper.java",
            // Kotlin
            "src/test/kotlin/com/example/ServiceTest.kt",
            "src/test/kotlin/com/example/ServiceSpec.kt",
            // Python — pytest prefix (most common)
            "tests/test_user_service.py",
            "app/test_models.py",
            // Python — suffix variant
            "app/user_service_test.py",
            "app/user_service_tests.py",
            // /tests/ directory (plural)
            "tests/unit/user_service.py",
            "backend/tests/test_api.py",
            // JavaScript / TypeScript
            "src/components/__tests__/Button.test.tsx",
            "src/__tests__/api.test.ts",
            "src/utils/formatDate.test.js",
            "src/utils/formatDate.spec.js",
            "src/pages/Home.test.tsx",
            "src/pages/Home.spec.tsx",
            "src/hooks/useAuth.test.ts",
            "src/hooks/useAuth.spec.ts",
            // Ruby
            "spec/models/user_spec.rb",
            "test/models/user_test.rb",
            // Go
            "pkg/service/user_test.go",
            "internal/handler/handler_test.go",
        })
        void shouldBeRecognisedAsTestFile(String path) {
            assertThat(engine.isTestFile(path))
                    .as("Expected '%s' to be detected as a test file", path)
                    .isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isTestFile — negative cases (must all be false)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class IsTestFile_Negative {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "src/main/java/com/example/UserService.java",
            "src/main/java/com/example/PaymentProcessor.java",
            "fineract-provider/src/main/java/org/apache/fineract/portfolio/savings/api/FixedDepositAccountTransactionsApiResource.java",
            "frontend/src/pages/HomePage.tsx",
            "frontend/src/components/Button.tsx",
            "app/models/user.py",
            "app/services/payment.py",
            "pkg/service/user.go",
            "lib/user.rb",
            "backend/src/main/resources/application.yaml",
        })
        void shouldNotBeRecognisedAsTestFile(String path) {
            assertThat(engine.isTestFile(path))
                    .as("Expected '%s' NOT to be detected as a test file", path)
                    .isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // computeTestCoverageGap — end-to-end scenarios
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class CoverageGap {

        private FileChangeSummary prodFile(String path) {
            return FileChangeSummary.builder()
                    .filename(path)
                    .riskLevel(RiskLevel.LOW)
                    .build();
        }

        /** Simulate the Fineract PR from the bug report. */
        @org.junit.jupiter.api.Test
        void fineractIntegrationTestPR_shouldNotBe100PercentGap() {
            List<FileChangeSummary> files = List.of(
                prodFile("fineract-client/src/main/java/org/apache/fineract/client/util/FineractClient.java"),
                prodFile("fineract-core/src/main/java/org/apache/fineract/commands/service/CommandWrapperBuilder.java"),
                prodFile("fineract-provider/src/main/java/org/apache/fineract/portfolio/savings/api/FixedDepositAccountTransactionsApiResource.java"),
                prodFile("fineract-provider/src/main/java/org/apache/fineract/portfolio/savings/api/FixedDepositAccountTransactionsApiResourceSwagger.java"),
                prodFile("fineract-provider/src/main/java/org/apache/fineract/portfolio/savings/service/DepositAccountWritePlatformServiceJpaRepositoryImpl.java"),
                // Test files — in integration-tests/src/test/java, no name-match to prod files
                prodFile("integration-tests/src/test/java/org/apache/fineract/integrationtests/common/fixeddeposit/FixedDepositAccountHelper.java"),
                prodFile("integration-tests/src/test/java/org/apache/fineract/integrationtests/common/fixeddeposit/FixedDepositTest.java")
            );

            double gap = engine.computeTestCoverageGap(files);

            assertThat(gap)
                    .as("Gap should be less than 1.0 because test files exist in the PR")
                    .isLessThan(1.0);
            // 5 prod files, 2 test files → integration credit covers 2/5 = 60% → gap = 3/5 = 60%
            assertThat(gap).isEqualTo(0.6, org.assertj.core.api.Assertions.within(0.01));
        }

        @org.junit.jupiter.api.Test
        void noTestFiles_shouldBe100PercentGap() {
            List<FileChangeSummary> files = List.of(
                prodFile("src/main/java/UserService.java"),
                prodFile("src/main/java/PaymentService.java")
            );
            assertThat(engine.computeTestCoverageGap(files)).isEqualTo(1.0);
        }

        @org.junit.jupiter.api.Test
        void namedMatchCoversProdFile_reducesGap() {
            List<FileChangeSummary> files = List.of(
                prodFile("src/main/java/UserService.java"),         // matched
                prodFile("src/main/java/PaymentService.java"),      // not matched
                prodFile("src/test/java/UserServiceTest.java")      // test file
            );
            double gap = engine.computeTestCoverageGap(files);
            // 2 prod files, 1 named match → gap should be 0.5
            assertThat(gap).isEqualTo(0.5, org.assertj.core.api.Assertions.within(0.01));
        }

        @org.junit.jupiter.api.Test
        void allProdFilesHaveTests_shouldBeZeroGap() {
            List<FileChangeSummary> files = List.of(
                prodFile("src/main/java/UserService.java"),
                prodFile("src/test/java/UserServiceTest.java")
            );
            assertThat(engine.computeTestCoverageGap(files)).isEqualTo(0.0);
        }

        @org.junit.jupiter.api.Test
        void onlyTestFiles_noProductionFiles_shouldBeZeroGap() {
            List<FileChangeSummary> files = List.of(
                prodFile("src/test/java/SomeTest.java")
            );
            assertThat(engine.computeTestCoverageGap(files)).isEqualTo(0.0);
        }

        @org.junit.jupiter.api.Test
        void emptyFileList_shouldBeZeroGap() {
            assertThat(engine.computeTestCoverageGap(List.of())).isEqualTo(0.0);
        }

        @org.junit.jupiter.api.Test
        void pythonPRWithTestPrefix_shouldReduceGap() {
            List<FileChangeSummary> files = List.of(
                prodFile("app/services/user_service.py"),
                prodFile("app/services/payment_service.py"),
                prodFile("tests/test_user_service.py"),   // pytest prefix pattern
                prodFile("tests/test_payment_service.py")
            );
            // Both prod files have named matches (test path contains base name)
            double gap = engine.computeTestCoverageGap(files);
            assertThat(gap).isEqualTo(0.0, org.assertj.core.api.Assertions.within(0.01));
        }
    }
}
