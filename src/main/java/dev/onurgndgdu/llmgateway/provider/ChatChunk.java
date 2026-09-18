package dev.onurgndgdu.llmgateway.provider;

/**
 * One element of a streamed answer.
 *
 * <p>The final chunk carries {@code usage} and a {@code finishReason}; every
 * earlier chunk carries only a text delta. A stream that ends without such a
 * chunk was truncated, and the gateway treats that as a failure rather than a
 * short answer.
 */
public record ChatChunk(
        String delta,
        boolean last,
        Usage usage,
        ChatResponse.FinishReason finishReason) {

    public static ChatChunk delta(String text) {
        return new ChatChunk(text, false, null, null);
    }

    public static ChatChunk last(Usage usage, ChatResponse.FinishReason finishReason) {
        return new ChatChunk("", true, usage, finishReason);
    }
}
