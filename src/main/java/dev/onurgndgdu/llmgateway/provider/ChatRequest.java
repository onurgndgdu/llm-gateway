package dev.onurgndgdu.llmgateway.provider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * A chat completion request in the gateway's own vocabulary.
 *
 * <p>Callers speak this format regardless of which provider ends up answering.
 * {@code model} is a gateway-level alias (for example {@code fast} or
 * {@code long-context}), not an upstream model name; routing resolves it.
 */
public record ChatRequest(
        @NotBlank String model,
        @NotEmpty List<@Valid Message> messages,
        Double temperature,
        @Positive Integer maxTokens,
        boolean stream) {

    public record Message(@NotNull Role role, @NotBlank String content) {}

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }
}
