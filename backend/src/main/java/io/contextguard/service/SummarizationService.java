package io.contextguard.service;

import io.contextguard.dto.SummaryData;
import io.contextguard.model.Snapshot;
import io.contextguard.service.summarizer.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class SummarizationService {

    private final HeuristicSummarizer heuristicSummarizer;
    private final LocalStubLLMClient stubLLMClient;
    private final OpenAIClient openAIClient;
    private final SanitizerService sanitizerService;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${contextguard.llm.provider:stub}")
    private String llmProvider;

    private static final long CACHE_TTL_DAYS = 7;

    public SummaryData generateSummary(Snapshot snapshot) {
        // Create cache key from snapshot content
        String cacheKey = "summary:" + generateContentHash(snapshot);

        // Check cache
        SummaryData cached = (SummaryData) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.info("Cache hit for summary: {}", cacheKey);
            return cached;
        }

        // Generate summary based on provider
        SummaryData summary;

        if ("openai".equalsIgnoreCase(llmProvider)) {
            try {
                String sanitizedContent = buildSanitizedContent(snapshot);
                summary = openAIClient.generateSummary(snapshot, sanitizedContent);
                log.info("Generated summary using OpenAI");
            } catch (Exception e) {
                log.warn("OpenAI failed, falling back to heuristic", e);
                summary = heuristicSummarizer.summarize(snapshot);
            }
        } else {
            summary = heuristicSummarizer.summarize(snapshot);
            log.info("Generated summary using heuristic approach");
        }

        redisTemplate.opsForValue().set(cacheKey, summary, CACHE_TTL_DAYS, TimeUnit.DAYS);

        return summary;
    }

    private String buildSanitizedContent(Snapshot snapshot) {
        StringBuilder content = new StringBuilder();
        if (snapshot.getPrBody() != null) {
            content.append(sanitizerService.sanitize(snapshot.getPrBody()));
        }
        if (snapshot.getCommitList() != null) {
            snapshot.getCommitList().forEach(commit -> {
                String message = commit.get("message");
                if (message != null) {
                    content.append("\n").append(sanitizerService.sanitize(message));
                }
            });
        }
        return content.toString();
    }

    private String generateContentHash(Snapshot snapshot) {
        try {
            String content = buildSanitizedContent(snapshot);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes());
            return bytesToHex(hash).substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
