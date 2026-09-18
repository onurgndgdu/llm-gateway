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
        // Boxed on purpose: the field is optional, and Jackson refuses to map a
        // missing value onto a primitive. A record component cannot carry a
        // default, so absence is modelled explicitly and read through streaming().
        Boolean stream) {

    public boolean streaming() {
        return Boolean.TRUE.equals(stream);
    }

    public record Message(@NotNull Role role, @NotBlank String content) {}

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }
}
