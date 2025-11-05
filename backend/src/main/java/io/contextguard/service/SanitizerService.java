package io.contextguard.service;

import org.springframework.stereotype.Service;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

@Service
public class SanitizerService {

    // Regex patterns for sensitive data
    private static final Pattern AWS_KEY_PATTERN =
            Pattern.compile("(AKIA[0-9A-Z]{16})", Pattern.CASE_INSENSITIVE);

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private static final Pattern IPV4_PATTERN =
            Pattern.compile("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b");

    private static final Pattern IPV6_PATTERN =
            Pattern.compile("([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}");

    private static final Pattern JWT_PATTERN =
            Pattern.compile("eyJ[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+");

    private static final Pattern LONG_HEX_PATTERN =
            Pattern.compile("\\b[0-9a-fA-F]{32,}\\b");

    private static final Pattern SSH_KEY_PATTERN =
            Pattern.compile("-----BEGIN [A-Z ]+ PRIVATE KEY-----[\\s\\S]+?-----END [A-Z ]+ PRIVATE KEY-----");

    private static final Pattern GENERIC_SECRET_PATTERN =
            Pattern.compile("(password|api_key|secret|token)\\s*[:=]\\s*['\"]?([^'\"\\s]+)",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Sanitizes text by redacting sensitive information
     */
    public String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String sanitized = text;
        // Redact AWS keys
        sanitized = AWS_KEY_PATTERN.matcher(sanitized)
                            .replaceAll("[REDACTED_AWS_KEY]");

        // Redact emails
        sanitized = EMAIL_PATTERN.matcher(sanitized)
                            .replaceAll("[REDACTED_EMAIL]");

        // Redact IPs
        sanitized = IPV4_PATTERN.matcher(sanitized)
                            .replaceAll("[REDACTED_IP]");
        sanitized = IPV6_PATTERN.matcher(sanitized)
                            .replaceAll("[REDACTED_IPv6]");

        // Redact JWTs
        sanitized = JWT_PATTERN.matcher(sanitized)
                            .replaceAll("[REDACTED_JWT]");

        // Redact long hex strings (likely secrets)
        sanitized = LONG_HEX_PATTERN.matcher(sanitized)
                            .replaceAll("[REDACTED_HEX]");

        // Redact SSH keys
        sanitized = SSH_KEY_PATTERN.matcher(sanitized)
                            .replaceAll("[REDACTED_SSH_KEY]");

        // Redact generic secrets
        Matcher secretMatcher = GENERIC_SECRET_PATTERN.matcher(sanitized);
        StringBuffer sb = new StringBuffer();
        while (secretMatcher.find()) {
            String key = secretMatcher.group(1);
            secretMatcher.appendReplacement(sb, key + "=[REDACTED]");
        }
        secretMatcher.appendTail(sb);
        sanitized = sb.toString();

        return sanitized;
    }

    public boolean containsSensitiveData(String text) {
        if (text == null) return false;

        return AWS_KEY_PATTERN.matcher(text).find() ||
                       JWT_PATTERN.matcher(text).find() ||
                       SSH_KEY_PATTERN.matcher(text).find();
    }
}

