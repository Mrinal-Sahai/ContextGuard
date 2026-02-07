package io.contextguard.client;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class AIRouter {

    private final Map<String, AIClient> clients;

    public AIRouter(List<AIClient> clientList) {
        this.clients = clientList.stream()
                               .collect(Collectors.toMap(
                                       client -> client.getClass().getSimpleName(),
                                       client -> client
                               ));
    }

    public AIClient getClient(AIProvider provider) {
        return switch (provider) {
            case GEMINI -> clients.get("GeminiApiClient");
            case OPENAI -> clients.get("OpenAIApiClient");
        };
    }
}

