package dev.onurgndgdu.llmgateway.provider.mock;

import dev.onurgndgdu.llmgateway.provider.ProviderException;
import java.time.Duration;

/**
 * What the mock provider should do for one upstream model.
 *
 * <p>A scenario is how a test states a failure mode it wants to exercise:
 * a provider that is rate limited, one that answers too slowly, one whose
 * stream dies halfway. Real providers cannot be asked to misbehave on cue,
 * which is why resilience behaviour is developed against this instead.
 */
public record MockScenario(
        String reply,
        Duration latency,
        Duration perChunkDelay,
        ProviderException.Kind failWith,
        int truncateAfterChunks) {

    private static final Duration DEFAULT_CHUNK_DELAY = Duration.ofMillis(5);

    /** Answers normally and immediately. */
    public static MockScenario replying(String reply) {
        return new MockScenario(reply, Duration.ZERO, DEFAULT_CHUNK_DELAY, null, -1);
    }

    /** Answers normally, but only after {@code latency} has passed. */
    public MockScenario withLatency(Duration latency) {
        return new MockScenario(reply, latency, perChunkDelay, failWith, truncateAfterChunks);
    }

    /** Fails every call with the given condition. */
    public static MockScenario failing(ProviderException.Kind kind) {
        return new MockScenario("", Duration.ZERO, DEFAULT_CHUNK_DELAY, kind, -1);
    }

    /**
     * Emits {@code chunks} deltas and then stops without a final chunk, the way
     * a dropped connection looks to a caller.
     */
    public MockScenario truncatedAfter(int chunks) {
        return new MockScenario(reply, latency, perChunkDelay, failWith, chunks);
    }

    public MockScenario withPerChunkDelay(Duration delay) {
        return new MockScenario(reply, latency, delay, failWith, truncateAfterChunks);
    }

    public boolean fails() {
        return failWith != null;
    }

    public boolean truncates() {
        return truncateAfterChunks >= 0;
    }
}
