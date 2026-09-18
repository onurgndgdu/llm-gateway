package dev.onurgndgdu.llmgateway.provider;

/**
 * Token counts for a single completion.
 *
 * <p>{@code estimated} records whether these numbers came from the provider or
 * were computed by the gateway. Providers do not always report usage, and cost
 * figures derived from an estimate should never be presented as exact.
 */
public record Usage(int promptTokens, int completionTokens, boolean estimated) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public static Usage reported(int promptTokens, int completionTokens) {
        return new Usage(promptTokens, completionTokens, false);
    }

    public static Usage estimated(int promptTokens, int completionTokens) {
        return new Usage(promptTokens, completionTokens, true);
    }
}
