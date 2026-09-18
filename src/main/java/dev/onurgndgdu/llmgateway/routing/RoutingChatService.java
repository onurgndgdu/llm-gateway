package dev.onurgndgdu.llmgateway.routing;

import dev.onurgndgdu.llmgateway.cost.BudgetGuard;
import dev.onurgndgdu.llmgateway.cost.CostCalculator;
import dev.onurgndgdu.llmgateway.cost.CostLedger;
import dev.onurgndgdu.llmgateway.cost.TokenEstimator;
import dev.onurgndgdu.llmgateway.metrics.GatewayMetrics;
import dev.onurgndgdu.llmgateway.provider.ChatChunk;
import dev.onurgndgdu.llmgateway.provider.ChatRequest;
import dev.onurgndgdu.llmgateway.provider.ChatResponse;
import dev.onurgndgdu.llmgateway.provider.ProviderException;
import dev.onurgndgdu.llmgateway.resilience.ProviderResilience;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Walks a route's chain until someone answers.
 *
 * <p>Each provider is tried under its own timeout, retry and breaker. Only
 * conditions that could plausibly succeed elsewhere move to the next target:
 * a malformed request fails immediately, because sending the same bad request
 * to three vendors only multiplies the cost of the caller's mistake.
 */
@Service
public class RoutingChatService {

    private static final Logger log = LoggerFactory.getLogger(RoutingChatService.class);

    private final ModelRouter router;
    private final ProviderResilience resilience;
    private final BudgetGuard budgets;
    private final CostCalculator costs;
    private final CostLedger ledger;
    private final TokenEstimator tokens;
    private final GatewayMetrics metrics;

    public RoutingChatService(
            ModelRouter router,
            ProviderResilience resilience,
            BudgetGuard budgets,
            CostCalculator costs,
            CostLedger ledger,
            TokenEstimator tokens,
            GatewayMetrics metrics) {
        this.router = router;
        this.resilience = resilience;
        this.budgets = budgets;
        this.costs = costs;
        this.ledger = ledger;
        this.tokens = tokens;
        this.metrics = metrics;
    }

    public Mono<ChatResponse> complete(ChatRequest request, String callerId) {
        List<ModelRouter.Resolved> chain = router.resolve(request.model());
        return budgets
                .check(callerId)
                // Measured here rather than taken from the response: a provider's
                // own figure excludes the network, retries and any failover, and
                // what matters operationally is what the caller waited for.
                .then(Mono.defer(() -> attempt(request, chain, 0).elapsed()))
                .flatMap(
                        timed -> {
                            ChatResponse response = timed.getT2();
                            Duration observed = Duration.ofMillis(timed.getT1());
                            return recordSpend(request, response, callerId, observed)
                                    .thenReturn(response);
                        });
    }

    /**
     * Spend is recorded after the answer, never before it, and a failure to
     * record it does not fail the call. The tokens have already been bought by
     * then; losing the bookkeeping is bad, but returning an error for an answer
     * the caller has paid for is worse.
     */
    private Mono<Void> recordSpend(
            ChatRequest request, ChatResponse response, String callerId, Duration observedLatency) {
        var usage = tokens.resolve(request, response.content(), response.usage());
        var cost = costs.costOf(response.providerId(), response.upstreamModel(), usage);

        metrics.recordSuccess(
                response.providerId(), response.upstreamModel(), observedLatency, usage);
        metrics.recordCost(response.providerId(), response.upstreamModel(), cost);

        if (!cost.priced()) {
            return Mono.empty();
        }

        return ledger
                .record(callerId, cost.amount())
                .onErrorResume(
                        error -> {
                            log.error("failed to record spend for caller '{}'", callerId, error);
                            return Mono.empty();
                        });
    }

    private Mono<ChatResponse> attempt(
            ChatRequest request, List<ModelRouter.Resolved> chain, int index) {

        ModelRouter.Resolved target = chain.get(index);
        boolean last = index == chain.size() - 1;

        Mono<ChatResponse> call =
                resilience.guard(
                        target.provider().id(),
                        target.provider().complete(request, target.upstreamModel()));

        if (last) {
            return call;
        }

        return call.onErrorResume(
                error -> {
                    if (error instanceof ProviderException failure) {
                        metrics.recordFailure(
                                target.provider().id(), target.upstreamModel(), failure.kind());
                    }
                    if (!shouldFailOver(error)) {
                        return Mono.error(error);
                    }
                    metrics.recordFailover(
                            target.provider().id(), chain.get(index + 1).provider().id());
                    log.warn(
                            "provider '{}' failed for alias '{}', falling over to '{}': {}",
                            target.provider().id(),
                            request.model(),
                            chain.get(index + 1).provider().id(),
                            error.getMessage());
                    return attempt(request, chain, index + 1);
                });
    }

    /**
     * Streaming fails over only before the first chunk reaches the caller.
     *
     * <p>Once bytes are on the wire, switching provider mid-answer would splice
     * two different completions into one response. The caller would have no way
     * to tell. Failing is the honest outcome.
     */
    public Flux<ChatChunk> stream(ChatRequest request, String callerId) {
        List<ModelRouter.Resolved> chain = router.resolve(request.model());
        return budgets.check(callerId).thenMany(Flux.defer(() -> streamAttempt(request, chain, 0)));
    }

    private Flux<ChatChunk> streamAttempt(
            ChatRequest request, List<ModelRouter.Resolved> chain, int index) {

        ModelRouter.Resolved target = chain.get(index);
        boolean last = index == chain.size() - 1;
        AtomicBoolean emitted = new AtomicBoolean(false);
        AtomicBoolean sawFinalChunk = new AtomicBoolean(false);

        Flux<ChatChunk> call =
                target
                        .provider()
                        .stream(request, target.upstreamModel())
                        .doOnNext(
                                chunk -> {
                                    emitted.set(true);
                                    if (chunk.last()) {
                                        sawFinalChunk.set(true);
                                    }
                                })
                        .concatWith(
                                Mono.defer(
                                        () ->
                                                sawFinalChunk.get()
                                                        ? Mono.empty()
                                                        : Mono.error(truncated(target.provider().id()))));

        if (last) {
            return call;
        }

        return call.onErrorResume(
                error -> {
                    if (emitted.get() || !shouldFailOver(error)) {
                        return Flux.error(error);
                    }
                    return streamAttempt(request, chain, index + 1);
                });
    }

    private ProviderException truncated(String providerId) {
        return new ProviderException(
                providerId,
                ProviderException.Kind.TRUNCATED_STREAM,
                "upstream stream ended without a final chunk");
    }

    private boolean shouldFailOver(Throwable error) {
        return error instanceof ProviderException failure && failure.kind().retryable();
    }
}
