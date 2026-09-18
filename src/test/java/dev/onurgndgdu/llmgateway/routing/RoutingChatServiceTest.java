package dev.onurgndgdu.llmgateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.onurgndgdu.llmgateway.config.GatewayProperties;
import dev.onurgndgdu.llmgateway.cost.BudgetGuard;
import dev.onurgndgdu.llmgateway.cost.CostCalculator;
import dev.onurgndgdu.llmgateway.cost.CostLedger;
import dev.onurgndgdu.llmgateway.cost.TokenEstimator;
import dev.onurgndgdu.llmgateway.provider.ChatChunk;
import dev.onurgndgdu.llmgateway.provider.ChatRequest;
import dev.onurgndgdu.llmgateway.provider.ChatResponse;
import dev.onurgndgdu.llmgateway.provider.LlmProvider;
import dev.onurgndgdu.llmgateway.provider.ProviderException;
import dev.onurgndgdu.llmgateway.provider.mock.MockProvider;
import dev.onurgndgdu.llmgateway.provider.mock.MockScenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import dev.onurgndgdu.llmgateway.resilience.ProviderResilience;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Failover and retry behaviour, driven entirely by injected upstream failures.
 *
 * <p>These are the assertions the project exists to make. Each one states a
 * decision that would otherwise live only in someone's head: what is worth
 * retrying, what is worth failing over, and where the line is for a stream
 * that has already started.
 */
class RoutingChatServiceTest {

    private static final ChatRequest REQUEST =
            new ChatRequest(
                    "alias",
                    List.of(new ChatRequest.Message(ChatRequest.Role.USER, "hi")),
                    null,
                    null,
                    null);

    private static GatewayProperties propertiesWith(GatewayProperties.Policy policy) {
        Route route =
                new Route(
                        List.of(
                                new Route.Target("primary", "model"),
                                new Route.Target("secondary", "model")));
        return new GatewayProperties(
                Map.of("alias", route),
                new GatewayProperties.Resilience(policy, Map.of()),
                Map.of(),
                null);
    }

    /** One attempt per provider, so retry does not blur the failover assertions. */
    private static GatewayProperties.Policy singleAttempt() {
        return new GatewayProperties.Policy(
                Duration.ofSeconds(5), 1, Duration.ofMillis(1), 50f, 100, Duration.ofSeconds(30));
    }

    private static RoutingChatService serviceFor(
            GatewayProperties.Policy policy, LlmProvider... providers) {
        GatewayProperties properties = propertiesWith(policy);
        return serviceFor(properties, new ProviderResilience(properties), providers);
    }

    /**
     * Cost accounting is exercised in its own tests; here it is stubbed out so
     * that these assertions stay about routing and nothing else.
     */
    private static RoutingChatService serviceFor(
            GatewayProperties properties, ProviderResilience resilience, LlmProvider... providers) {
        ModelRouter router = new ModelRouter(properties, List.of(providers));
        CostLedger ledger = org.mockito.Mockito.mock(CostLedger.class);
        org.mockito.Mockito.when(ledger.record(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.empty());
        BudgetGuard budgets = org.mockito.Mockito.mock(BudgetGuard.class);
        org.mockito.Mockito.when(budgets.check(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Mono.empty());
        return new RoutingChatService(
                router,
                resilience,
                budgets,
                new CostCalculator(properties),
                ledger,
                new TokenEstimator(),
                new dev.onurgndgdu.llmgateway.metrics.GatewayMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @Test
    void failsOverToTheNextProviderWhenThePrimaryIsRateLimited() {
        MockProvider primary =
                new MockProvider("primary")
                        .defaultScenario(MockScenario.failing(ProviderException.Kind.RATE_LIMITED));
        MockProvider secondary =
                new MockProvider("secondary").defaultScenario(MockScenario.replying("from secondary"));

        ChatResponse response = serviceFor(singleAttempt(), primary, secondary).complete(REQUEST, "tester").block();

        assertThat(response.providerId()).isEqualTo("secondary");
        assertThat(response.content()).isEqualTo("from secondary");
        assertThat(primary.totalCalls()).isEqualTo(1);
        assertThat(secondary.totalCalls()).isEqualTo(1);
    }

    @Test
    void doesNotFailOverWhenTheRequestItselfIsWrong() {
        MockProvider primary =
                new MockProvider("primary")
                        .defaultScenario(MockScenario.failing(ProviderException.Kind.INVALID_REQUEST));
        MockProvider secondary =
                new MockProvider("secondary").defaultScenario(MockScenario.replying("never reached"));

        Throwable error =
                catchError(serviceFor(singleAttempt(), primary, secondary).complete(REQUEST, "tester"));

        assertThat(error).isInstanceOf(ProviderException.class);
        assertThat(((ProviderException) error).kind())
                .isEqualTo(ProviderException.Kind.INVALID_REQUEST);
        // Sending a malformed request to every vendor in the chain only
        // multiplies the cost of the caller's mistake.
        assertThat(secondary.totalCalls()).isZero();
    }

    @Test
    void retriesTheSameProviderBeforeGivingUpOnIt() {
        GatewayProperties.Policy threeAttempts =
                new GatewayProperties.Policy(
                        Duration.ofSeconds(5), 3, Duration.ofMillis(1), 50f, 100, Duration.ofSeconds(30));

        MockProvider primary =
                new MockProvider("primary")
                        .defaultScenario(MockScenario.failing(ProviderException.Kind.UNAVAILABLE));
        MockProvider secondary =
                new MockProvider("secondary").defaultScenario(MockScenario.replying("from secondary"));

        ChatResponse response = serviceFor(threeAttempts, primary, secondary).complete(REQUEST, "tester").block();

        assertThat(primary.totalCalls()).isEqualTo(3);
        assertThat(response.providerId()).isEqualTo("secondary");
    }

    @Test
    void breakerOpensAfterRepeatedFailuresAndStopsCallingTheProvider() {
        GatewayProperties.Policy shortWindow =
                new GatewayProperties.Policy(
                        Duration.ofSeconds(5), 1, Duration.ofMillis(1), 50f, 4, Duration.ofSeconds(30));

        MockProvider primary =
                new MockProvider("primary")
                        .defaultScenario(MockScenario.failing(ProviderException.Kind.UNAVAILABLE));
        MockProvider secondary =
                new MockProvider("secondary").defaultScenario(MockScenario.replying("from secondary"));

        GatewayProperties properties = propertiesWith(shortWindow);
        ProviderResilience resilience = new ProviderResilience(properties);
        RoutingChatService service =
                serviceFor(properties, resilience, primary, secondary);

        for (int i = 0; i < 4; i++) {
            service.complete(REQUEST, "tester").block();
        }
        assertThat(resilience.stateOf("primary")).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBefore = primary.totalCalls();
        ChatResponse response = service.complete(REQUEST, "tester").block();

        // The breaker short-circuits: the provider is not contacted at all, and
        // the caller is still served by the fallback.
        assertThat(primary.totalCalls()).isEqualTo(callsBefore);
        assertThat(response.providerId()).isEqualTo("secondary");
    }

    @Test
    void streamFailsOverWhenTheFailureHappensBeforeAnyChunk() {
        MockProvider primary =
                new MockProvider("primary")
                        .defaultScenario(MockScenario.failing(ProviderException.Kind.UNAVAILABLE));
        MockProvider secondary =
                new MockProvider("secondary").defaultScenario(MockScenario.replying("one two"));

        List<ChatChunk> chunks =
                serviceFor(singleAttempt(), primary, secondary).stream(REQUEST, "tester").collectList().block();

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.getLast().last()).isTrue();
    }

    @Test
    void streamDoesNotFailOverOnceChunksHaveReachedTheCaller() {
        MockProvider primary =
                new MockProvider("primary")
                        .defaultScenario(MockScenario.replying("one two three").truncatedAfter(2));
        MockProvider secondary =
                new MockProvider("secondary").defaultScenario(MockScenario.replying("a different answer"));

        Throwable error =
                catchError(
                        serviceFor(singleAttempt(), primary, secondary).stream(REQUEST, "tester").collectList());

        assertThat(error).isInstanceOf(ProviderException.class);
        assertThat(((ProviderException) error).kind())
                .isEqualTo(ProviderException.Kind.TRUNCATED_STREAM);
        // Switching mid-answer would splice two different completions into one
        // response, and the caller could not tell.
        assertThat(secondary.totalCalls()).isZero();
    }

    private static Throwable catchError(Mono<?> mono) {
        try {
            mono.block();
            throw new AssertionError("expected a failure");
        } catch (Throwable error) {
            return error;
        }
    }
}
