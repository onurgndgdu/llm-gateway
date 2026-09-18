package dev.onurgndgdu.llmgateway.resilience;

import dev.onurgndgdu.llmgateway.config.GatewayProperties;
import dev.onurgndgdu.llmgateway.provider.ProviderException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Wraps a single provider call in timeout, retry and circuit breaking.
 *
 * <p>Policies are built explicitly rather than through annotations. A gateway
 * applies different policy per provider and the decision is dynamic, which
 * annotations cannot express; building them here also means a test can assert
 * on the policy instead of on a proxy.
 *
 * <p>Order matters. The timeout is innermost so it bounds each attempt rather
 * than the whole sequence. The breaker sits inside retry so that every attempt
 * is recorded, which is what lets a provider that is failing consistently trip
 * the breaker instead of being retried forever.
 */
@Component
public class ProviderResilience {

    private final GatewayProperties properties;
    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    private final RetryRegistry retries = RetryRegistry.ofDefaults();

    public ProviderResilience(GatewayProperties properties) {
        this.properties = properties;
    }

    public <T> Mono<T> guard(String providerId, Mono<T> call) {
        GatewayProperties.Policy policy = properties.policyFor(providerId);

        return call.timeout(policy.timeout())
                .onErrorMap(TimeoutException.class, error -> timedOut(providerId, policy.timeout()))
                .transformDeferred(CircuitBreakerOperator.of(breaker(providerId, policy)))
                .onErrorMap(CallNotPermittedException.class, error -> circuitOpen(providerId))
                .transformDeferred(RetryOperator.of(retry(providerId, policy)));
    }

    /** Exposed so tests and metrics can observe breaker state. */
    public CircuitBreaker.State stateOf(String providerId) {
        return breaker(providerId, properties.policyFor(providerId)).getState();
    }

    private CircuitBreaker breaker(String providerId, GatewayProperties.Policy policy) {
        return breakers.circuitBreaker(
                providerId,
                () ->
                        CircuitBreakerConfig.custom()
                                .failureRateThreshold(policy.failureRateThreshold())
                                .slidingWindowSize(policy.slidingWindowSize())
                                // Defaults to 100 regardless of window size, which would
                                // keep the breaker shut forever on a smaller window.
                                .minimumNumberOfCalls(policy.slidingWindowSize())
                                .waitDurationInOpenState(policy.openStateDuration())
                                // Only conditions that might pass later count against the
                                // provider. A malformed request is our caller's fault and
                                // says nothing about the provider's health.
                                .recordException(ProviderResilience::countsAgainstProvider)
                                .build());
    }

    private Retry retry(String providerId, GatewayProperties.Policy policy) {
        return retries.retry(
                providerId,
                () ->
                        RetryConfig.custom()
                                .maxAttempts(policy.maxAttempts())
                                .intervalFunction(
                                        io.github.resilience4j.core.IntervalFunction
                                                .ofExponentialRandomBackoff(policy.initialBackoff(), 2.0, 0.5))
                                .retryOnException(ProviderResilience::worthRetrying)
                                .build());
    }

    private static boolean worthRetrying(Throwable error) {
        // A breaker that has opened must not be retried: the whole point of
        // opening it is to stop sending load. Failover to another provider is
        // the correct response, and that happens a layer up.
        return error instanceof ProviderException failure && failure.kind().retryable();
    }

    private static boolean countsAgainstProvider(Throwable error) {
        return error instanceof ProviderException failure && failure.kind().retryable();
    }

    private ProviderException timedOut(String providerId, Duration timeout) {
        return new ProviderException(
                providerId,
                ProviderException.Kind.TIMEOUT,
                "provider '%s' did not answer within %s".formatted(providerId, timeout));
    }

    private ProviderException circuitOpen(String providerId) {
        return new ProviderException(
                providerId,
                ProviderException.Kind.UNAVAILABLE,
                "circuit for provider '%s' is open".formatted(providerId));
    }
}
