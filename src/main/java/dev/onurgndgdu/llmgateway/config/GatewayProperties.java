package dev.onurgndgdu.llmgateway.config;

import dev.onurgndgdu.llmgateway.cost.ModelPrice;
import dev.onurgndgdu.llmgateway.routing.Route;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param routes caller-facing alias to the ordered chain that serves it
 * @param resilience default policy, plus per-provider overrides; a provider
 *                   known to be slow should not force everyone else to wait
 *                   as long before a timeout fires
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        Map<String, Route> routes,
        Resilience resilience,
        Map<String, ModelPrice> prices,
        Budgets budgets,
        Cache cache) {

    public GatewayProperties {
        routes = routes == null ? Map.of() : Map.copyOf(routes);
        resilience = resilience == null ? Resilience.defaults() : resilience;
        prices = prices == null ? Map.of() : Map.copyOf(prices);
        budgets = budgets == null ? new Budgets(null, Map.of()) : budgets;
        cache = cache == null ? Cache.defaults() : cache;
    }

    /**
     * @param ttl how long an answer stays servable. Short by default: a cached
     *            answer is a snapshot of what a model said once, and the longer
     *            it is kept the further it drifts from what the model would say
     *            now.
     */
    public record Cache(boolean enabled, Duration ttl) {
        public Cache {
            ttl = ttl == null ? Duration.ofHours(1) : ttl;
        }

        static Cache defaults() {
            return new Cache(true, Duration.ofHours(1));
        }
    }

    /** The caller's daily limit, or null when the caller is uncapped. */
    public BigDecimal dailyBudgetFor(String callerId) {
        return budgets.perCaller().getOrDefault(callerId, budgets.defaultDaily());
    }

    /**
     * @param defaultDaily applies to any caller without its own limit; null
     *                     leaves callers uncapped, which is the right default
     *                     for a gateway that would otherwise start rejecting
     *                     traffic the moment it is deployed
     */
    public record Budgets(BigDecimal defaultDaily, Map<String, BigDecimal> perCaller) {
        public Budgets {
            perCaller = perCaller == null ? Map.of() : Map.copyOf(perCaller);
        }
    }

    /** Prices are keyed by {@code providerId:upstreamModel}. */
    public ModelPrice priceFor(String providerId, String upstreamModel) {
        return prices.get(providerId + ":" + upstreamModel);
    }

    public Policy policyFor(String providerId) {
        return resilience.overrides().getOrDefault(providerId, resilience.defaultPolicy());
    }

    public record Resilience(Policy defaultPolicy, Map<String, Policy> overrides) {

        public Resilience {
            defaultPolicy = defaultPolicy == null ? Policy.defaults() : defaultPolicy;
            overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
        }

        static Resilience defaults() {
            return new Resilience(Policy.defaults(), Map.of());
        }
    }

    /**
     * @param timeout how long a single attempt may take
     * @param maxAttempts total attempts against one provider, including the first
     * @param initialBackoff delay before the second attempt, doubled thereafter
     * @param failureRateThreshold percentage of recent calls that must fail
     *                             before the breaker opens
     * @param slidingWindowSize how many recent calls the breaker judges on
     * @param openStateDuration how long the breaker stays open before probing
     */
    public record Policy(
            Duration timeout,
            int maxAttempts,
            Duration initialBackoff,
            float failureRateThreshold,
            int slidingWindowSize,
            Duration openStateDuration) {

        public static Policy defaults() {
            return new Policy(Duration.ofSeconds(30), 3, Duration.ofMillis(200), 50f, 20, Duration.ofSeconds(30));
        }
    }
}
