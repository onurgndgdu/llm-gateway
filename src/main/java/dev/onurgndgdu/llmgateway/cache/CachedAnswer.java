package dev.onurgndgdu.llmgateway.cache;

import dev.onurgndgdu.llmgateway.provider.ChatResponse;
import dev.onurgndgdu.llmgateway.provider.Usage;
import java.time.Duration;

/**
 * What is stored for a cache hit.
 *
 * <p>Deliberately not the whole {@link ChatResponse}: latency belongs to the
 * call that produced it, and replaying a stored 900ms as though this request
 * had taken that long would corrupt the very latency figures a cache exists
 * to improve.
 */
public record CachedAnswer(
        String content,
        String providerId,
        String upstreamModel,
        int promptTokens,
        int completionTokens,
        boolean estimated,
        String finishReason) {

    static CachedAnswer from(ChatResponse response) {
        Usage usage = response.usage();
        return new CachedAnswer(
                response.content(),
                response.providerId(),
                response.upstreamModel(),
                usage.promptTokens(),
                usage.completionTokens(),
                usage.estimated(),
                response.finishReason().name());
    }

    ChatResponse toResponse(Duration observedLatency) {
        return new ChatResponse(
                content,
                providerId,
                upstreamModel,
                new Usage(promptTokens, completionTokens, estimated),
                ChatResponse.FinishReason.valueOf(finishReason),
                observedLatency);
    }
}
