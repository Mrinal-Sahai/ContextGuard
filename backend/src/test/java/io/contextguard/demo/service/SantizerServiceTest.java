//package io.contextguard.demo.service;
//
//import io.contextguard.service.SanitizerService;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.BeforeEach;
//import static org.junit.jupiter.api.Assertions.*;
//
//class SanitizerServiceTest {
//
//    private SanitizerService sanitizer;
//
//    @BeforeEach
//    void setUp() {
//        sanitizer = new SanitizerService();
//    }
//
//    @Test
//    void shouldRedactAWSKeys() {
//        String input = "AWS Key: AKIAIOSFODNN7EXAMPLE should be hidden";
//        String result = sanitizer.sanitize(input);
//
//        assertFalse(result.contains("AKIAIOSFODNN7EXAMPLE"));
//        assertTrue(result.contains("[REDACTED_AWS_KEY]"));
//    }
//
//    @Test
//    void shouldRedactEmails() {
//        String input = "Contact user@example.com for details";
//        String result = sanitizer.sanitize(input);
//
//        assertFalse(result.contains("user@example.com"));
//        assertTrue(result.contains("[REDACTED_EMAIL]"));
//    }
//
//
//    @Test
//    void shouldRedactIPAddresses() {
//        String input = "Server at 192.168.1.100 is down";
//        String result = sanitizer.sanitize(input);
//
//        assertFalse(result.contains("192.168.1.100"));
//        assertTrue(result.contains("[REDACTED_IP]"));
//    }
//
//    @Test
//    void shouldRedactJWTTokens() {
//        String input = "Token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
//        String result = sanitizer.sanitize(input);
//
//        assertFalse(result.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
//        assertTrue(result.contains("[REDACTED_JWT]"));
//    }
//
//    @Test
//    void shouldRedactLongHexStrings() {
//        String input = "Hash: 5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8";
//        String result = sanitizer.sanitize(input);
//
//        assertFalse(result.contains("5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8"));
//        assertTrue(result.contains("[REDACTED_HEX]"));
//    }
//
//    @Test
//    void shouldRedactSSHKeys() {
//        String input = """
//            -----BEGIN RSA PRIVATE KEY-----
//            MIIEpAIBAAKCAQEAx...
//            -----END RSA PRIVATE KEY-----
//            """;
//        String result = sanitizer.sanitize(input);
//
//        assertFalse(result.contains("BEGIN RSA PRIVATE KEY"));
//        assertTrue(result.contains("[REDACTED_SSH_KEY]"));
//    }
//
//    @Test
//    void shouldRedactGenericSecrets() {
//        String input = "password=secret123 api_key=\"sk-1234\"";
//        String result = sanitizer.sanitize(input);
//
//        assertFalse(result.contains("secret123"));
//        assertFalse(result.contains("sk-1234"));
//        assertTrue(result.contains("password=[REDACTED]"));
//        assertTrue(result.contains("api_key=[REDACTED]"));
//    }
//
//    @Test
//    void shouldHandleNullInput() {
//        String result = sanitizer.sanitize(null);
//        assertNull(result);
//    }
//    @Test
//    void shouldHandleEmptyInput() {
//        String result = sanitizer.sanitize("");
//        assertEquals("", result);
//    }
//
//    @Test
//    void shouldDetectSensitiveData() {
//        String withAWSKey = "Key: AKIAIOSFODNN7EXAMPLE";
//        assertTrue(sanitizer.containsSensitiveData(withAWSKey));
//
//        String sanitized = sanitizer.sanitize(withAWSKey);
//        assertFalse(sanitizer.containsSensitiveData(sanitized));
//    }
//}
//
//
