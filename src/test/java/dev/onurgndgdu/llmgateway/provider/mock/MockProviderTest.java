package dev.onurgndgdu.llmgateway.provider.mock;

import static org.assertj.core.api.Assertions.assertThat;

import dev.onurgndgdu.llmgateway.provider.ChatChunk;
import dev.onurgndgdu.llmgateway.provider.ChatRequest;
import dev.onurgndgdu.llmgateway.provider.ProviderException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * The mock provider is the instrument every later resilience test depends on,
 * so its own behaviour is pinned down here. If the mock silently stops failing
 * on demand, the tests that rely on it would pass for the wrong reason.
 */
class MockProviderTest {

    private static final ChatRequest REQUEST =
            new ChatRequest(
                    "fast",
                    List.of(new ChatRequest.Message(ChatRequest.Role.USER, "hello")),
                    null,
                    null,
                    false);

    @Test
    void completesWithTheConfiguredReply() {
        MockProvider provider =
                new MockProvider("primary").register("model-a", MockScenario.replying("hi there"));

        StepVerifier.create(provider.complete(REQUEST, "model-a"))
                .assertNext(
                        response -> {
                            assertThat(response.content()).isEqualTo("hi there");
                            assertThat(response.providerId()).isEqualTo("primary");
                            assertThat(response.upstreamModel()).isEqualTo("model-a");
                            assertThat(response.usage().estimated()).isFalse();
                        })
                .verifyComplete();
    }

    @Test
    void failsWithTheConfiguredCondition() {
        MockProvider provider =
                new MockProvider("primary")
                        .register("model-a", MockScenario.failing(ProviderException.Kind.RATE_LIMITED));

        StepVerifier.create(provider.complete(REQUEST, "model-a"))
                .expectErrorSatisfies(
                        error -> {
                            assertThat(error).isInstanceOf(ProviderException.class);
                            ProviderException failure = (ProviderException) error;
                            assertThat(failure.kind()).isEqualTo(ProviderException.Kind.RATE_LIMITED);
                            assertThat(failure.kind().retryable()).isTrue();
                            assertThat(failure.providerId()).isEqualTo("primary");
                        })
                .verify();
    }

    @Test
    void scenariosAreSelectedPerModel() {
        MockProvider provider =
                new MockProvider("primary")
                        .register("healthy", MockScenario.replying("fine"))
                        .register("broken", MockScenario.failing(ProviderException.Kind.UNAVAILABLE));

        StepVerifier.create(provider.complete(REQUEST, "healthy")).expectNextCount(1).verifyComplete();
        StepVerifier.create(provider.complete(REQUEST, "broken"))
                .expectError(ProviderException.class)
                .verify();
    }

    @Test
    void countsCallsPerModelSoFailoverCanBeAsserted() {
        MockProvider provider = new MockProvider("primary").defaultScenario(MockScenario.replying("x"));

        provider.complete(REQUEST, "model-a").block();
        provider.complete(REQUEST, "model-a").block();
        provider.complete(REQUEST, "model-b").block();

        assertThat(provider.callCount("model-a")).isEqualTo(2);
        assertThat(provider.callCount("model-b")).isEqualTo(1);
        assertThat(provider.callCount("never-called")).isZero();
        assertThat(provider.totalCalls()).isEqualTo(3);
    }

    @Test
    void streamsDeltasAndThenAFinalChunkCarryingUsage() {
        MockProvider provider =
                new MockProvider("primary").register("model-a", MockScenario.replying("one two three"));

        List<ChatChunk> chunks = provider.stream(REQUEST, "model-a").collectList().block();

        assertThat(chunks).hasSize(4);
        assertThat(chunks.subList(0, 3)).allSatisfy(chunk -> assertThat(chunk.last()).isFalse());

        ChatChunk last = chunks.getLast();
        assertThat(last.last()).isTrue();
        assertThat(last.usage()).isNotNull();

        String assembled = chunks.stream().map(ChatChunk::delta).reduce("", String::concat);
        assertThat(assembled.trim()).isEqualTo("one two three");
    }

    @Test
    void truncatedStreamEndsWithoutAFinalChunk() {
        MockProvider provider =
                new MockProvider("primary")
                        .register("model-a", MockScenario.replying("one two three").truncatedAfter(2));

        List<ChatChunk> chunks = provider.stream(REQUEST, "model-a").collectList().block();

        assertThat(chunks).hasSize(2);
        assertThat(chunks).noneMatch(ChatChunk::last);
    }

    @Test
    void latencyIsObservableSoTimeoutPolicyCanBeTested() {
        MockProvider provider =
                new MockProvider("slow")
                        .register(
                                "model-a", MockScenario.replying("late").withLatency(Duration.ofSeconds(30)));

        // Virtual time keeps the suite fast: a 30s upstream delay is simulated,
        // not waited for.
        StepVerifier.withVirtualTime(() -> Flux.from(provider.complete(REQUEST, "model-a")))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(29))
                .thenAwait(Duration.ofSeconds(1))
                .expectNextCount(1)
                .verifyComplete();
    }
}
