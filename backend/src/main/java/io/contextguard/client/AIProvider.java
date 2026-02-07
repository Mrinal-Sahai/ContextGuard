package io.contextguard.client;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AIProvider {
    GEMINI,
    OPENAI;


    @JsonCreator
    public static AIProvider fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return AIProvider.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid aiProvider. Allowed values: GEMINI, OPENAI", e
            );
        }
    }
}

