package dev.onurgndgdu.llmgateway.metrics;

import dev.onurgndgdu.llmgateway.cost.CostCalculator;
import dev.onurgndgdu.llmgateway.provider.ProviderException;
import dev.onurgndgdu.llmgateway.provider.Usage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The gateway's own view of what it is doing.
 *
 * <p>Tag cardinality is the constraint that shapes this. Provider, model and
 * outcome are bounded sets and make useful dimensions. Caller id is not
 * bounded — it grows with every client — so spend is counted per provider and
 * model only, and per-caller figures are read from the ledger, which is built
 * for exactly that question.
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSuccess(String providerId, String model, Duration latency, Usage usage) {
        Timer.builder("llm.gateway.request")
                .tag("provider", providerId)
                .tag("model", model)
                .tag("outcome", "success")
                .register(registry)
                .record(latency);

        counter("llm.gateway.tokens", providerId, model, "direction", "prompt")
                .increment(usage.promptTokens());
        counter("llm.gateway.tokens", providerId, model, "direction", "completion")
                .increment(usage.completionTokens());

        if (usage.estimated()) {
            // Watching this climb is how anyone notices that a provider stopped
            // reporting usage and the cost figures quietly became guesses.
            counter("llm.gateway.usage.estimated", providerId, model).increment();
        }
    }

    public void recordFailure(String providerId, String model, ProviderException.Kind kind) {
        counter("llm.gateway.failures", providerId, model, "kind", kind.name()).increment();
    }

    public void recordFailover(String fromProvider, String toProvider) {
        Counter.builder("llm.gateway.failover")
                .tag("from", fromProvider)
                .tag("to", toProvider)
                .register(registry)
                .increment();
    }

    public void recordCost(String providerId, String model, CostCalculator.Cost cost) {
        if (!cost.priced()) {
            // Unpriced calls are counted separately rather than added as zero.
            // A model missing from the price table would otherwise be invisible
            // in every cost chart.
            counter("llm.gateway.cost.unpriced", providerId, model).increment();
            return;
        }

        counter("llm.gateway.cost", providerId, model).increment(cost.amount().doubleValue());
    }

    private Counter counter(String name, String providerId, String model, String... extraTags) {
        Counter.Builder builder =
                Counter.builder(name).tag("provider", providerId).tag("model", model);
        for (int i = 0; i + 1 < extraTags.length; i += 2) {
            builder = builder.tag(extraTags[i], extraTags[i + 1]);
        }
        return builder.register(registry);
    }
}
