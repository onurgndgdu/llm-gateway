package dev.onurgndgdu.llmgateway.provider.mock;

import dev.onurgndgdu.llmgateway.provider.ChatChunk;
import dev.onurgndgdu.llmgateway.provider.ChatRequest;
import dev.onurgndgdu.llmgateway.provider.ChatResponse;
import dev.onurgndgdu.llmgateway.provider.LlmProvider;
import dev.onurgndgdu.llmgateway.provider.ProviderException;
import dev.onurgndgdu.llmgateway.provider.Usage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A provider whose behaviour is declared by the test rather than by a vendor.
 *
 * <p>Scenarios are registered per upstream model, so a single instance can act
 * as a healthy provider for one model and a failing one for another. Call
 * counts are recorded so tests can assert on retry and failover behaviour —
 * that a fallback was reached, or that a non-retryable error was not retried.
 */
public class MockProvider implements LlmProvider {

    private static final Usage FIXED_USAGE = Usage.reported(12, 8);

    private final String id;
    private final Map<String, MockScenario> scenarios = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();
    private volatile MockScenario fallbackScenario = MockScenario.replying("mock reply");

    public MockProvider(String id) {
        this.id = id;
    }

    public MockProvider register(String upstreamModel, MockScenario scenario) {
        scenarios.put(upstreamModel, scenario);
        return this;
    }

    /** Applies to any model without its own scenario. */
    public MockProvider defaultScenario(MockScenario scenario) {
        this.fallbackScenario = scenario;
        return this;
    }

    public int callCount(String upstreamModel) {
        AtomicInteger counter = callCounts.get(upstreamModel);
        return counter == null ? 0 : counter.get();
    }

    public int totalCalls() {
        return callCounts.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public void reset() {
        callCounts.clear();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean supports(String upstreamModel) {
        return true;
    }

    @Override
    public Mono<ChatResponse> complete(ChatRequest request, String upstreamModel) {
        MockScenario scenario = scenarioFor(upstreamModel);

        if (scenario.fails()) {
            return Mono.error(failure(scenario));
        }

        ChatResponse response = new ChatResponse(
                scenario.reply(),
                id,
                upstreamModel,
                FIXED_USAGE,
                ChatResponse.FinishReason.STOP,
                scenario.latency());

        return Mono.just(response).delayElement(scenario.latency());
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request, String upstreamModel) {
        MockScenario scenario = scenarioFor(upstreamModel);

        if (scenario.fails()) {
            return Flux.error(failure(scenario));
        }

        List<String> words = List.of(scenario.reply().split(" "));
        Flux<ChatChunk> deltas = Flux.fromIterable(words)
                .map(word -> ChatChunk.delta(word + " "))
                .delayElements(scenario.perChunkDelay());

        if (scenario.truncates()) {
            // Stops without a final chunk, the way a dropped connection looks.
            return deltas.take(scenario.truncateAfterChunks())
                    .delaySubscription(scenario.latency());
        }

        return deltas.concatWith(
                        Mono.just(ChatChunk.last(FIXED_USAGE, ChatResponse.FinishReason.STOP)))
                .delaySubscription(scenario.latency());
    }

    private MockScenario scenarioFor(String upstreamModel) {
        callCounts.computeIfAbsent(upstreamModel, key -> new AtomicInteger()).incrementAndGet();
        return scenarios.getOrDefault(upstreamModel, fallbackScenario);
    }

    private ProviderException failure(MockScenario scenario) {
        return new ProviderException(
                id, scenario.failWith(), "mock provider '%s' configured to fail".formatted(id));
    }
}
