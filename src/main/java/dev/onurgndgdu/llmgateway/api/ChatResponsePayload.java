package dev.onurgndgdu.llmgateway.api;

import dev.onurgndgdu.llmgateway.provider.ChatResponse;

/**
 * The wire shape of a completion.
 *
 * <p>Kept separate from {@link ChatResponse} on purpose: the internal model
 * carries things callers should not depend on, such as measured latency, and
 * the wire contract should be free to stay stable while the internal model
 * changes.
 */
public record ChatResponsePayload(
        String content, String model, String provider, UsagePayload usage, String finishReason) {

    public record UsagePayload(
            int promptTokens, int completionTokens, int totalTokens, boolean estimated) {}

    static ChatResponsePayload from(ChatResponse response) {
        var usage = response.usage();
        return new ChatResponsePayload(
                response.content(),
                response.upstreamModel(),
                response.providerId(),
                new UsagePayload(
                        usage.promptTokens(),
                        usage.completionTokens(),
                        usage.totalTokens(),
                        usage.estimated()),
                response.finishReason().name());
    }
}
