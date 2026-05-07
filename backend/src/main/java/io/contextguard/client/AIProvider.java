package io.contextguard.client;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AIProvider {
    GEMINI,
    OPENAI,
    /**
     * Anthropic Claude via the Anthropic Messages API.
     * Supports the MCP Connector (beta) — allows Claude to call the GitHub MCP server
     * to fetch code scanning alerts, review status, and CI results during generation.
     * Requires ANTHROPIC_API_KEY env var.
     */
    ANTHROPIC;


    @JsonCreator
    public static AIProvider fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return AIProvider.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid aiProvider. Allowed values: GEMINI, OPENAI, ANTHROPIC", e
            );
        }
    }
}

