package io.contextguard.service;

import io.contextguard.model.Notification;
import io.contextguard.model.Review;
import io.contextguard.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final WebClient.Builder webClientBuilder;

    @Value("${contextguard.notifications.slack.webhook-url}")
    private String slackWebhookUrl;

    public void sendDecayAlert(Review review, int contextScore) {
        String message = buildDecayAlertMessage(review, contextScore);

        // Create notification record
        Notification notification = Notification.builder()
                                            .review(review)
                                            .type("decay_alert")
                                            .message(message)
                                            .deliveryStatus("pending")
                                            .build();

        notificationRepository.save(notification);

// Send to Slack
        try {
            sendToSlack(message);
            notification.setDeliveryStatus("sent");
            log.info("Decay alert sent for review: {}/{}/{}/{}",
                    review.getPlatform(), review.getOwner(),
                    review.getRepository(), review.getExternalId());
        } catch (Exception e) {
            notification.setDeliveryStatus("failed");
            log.error("Failed to send Slack notification", e);
        }

        notificationRepository.save(notification);
    }

    private String buildDecayAlertMessage(Review review, int contextScore) {
        return String.format(
                "⚠️ *Stale Review Alert*\n\n" +
                        "*PR*: %s/%s #%s\n" +
                        "*Title*: %s\n" +
                        "*Author*: %s\n" +
                        "*Context Score*: %d/100\n" +
                        "*Status*: Review has been inactive for >24 hours\n\n" +
                        "Context may be lost. Please review soon!",
                review.getOwner(),
                review.getRepository(),
                review.getExternalId(),
                review.getTitle(),
                review.getAuthor(),
                contextScore
        );
    }

    private void sendToSlack(String message) {
        WebClient webClient = webClientBuilder.build();

        Map<String, String> payload = Map.of("text", message);

        webClient.post()
                .uri(slackWebhookUrl)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }
}