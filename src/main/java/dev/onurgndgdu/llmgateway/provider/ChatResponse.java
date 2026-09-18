package dev.onurgndgdu.llmgateway.provider;

import java.time.Duration;

/**
 * A completed, non-streaming answer.
 *
 * @param providerId which adapter answered, so callers can see through the
 *                   routing decision when diagnosing a response
 * @param upstreamModel the model the provider actually used, which may differ
 *                      from the alias the caller asked for
 */
public record ChatResponse(
        String content,
        String providerId,
        String upstreamModel,
        Usage usage,
        FinishReason finishReason,
        Duration latency) {

    public enum FinishReason {
        STOP,
        LENGTH,
        CONTENT_FILTER,
        ERROR
    }
}
