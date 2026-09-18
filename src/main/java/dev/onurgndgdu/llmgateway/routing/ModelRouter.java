package dev.onurgndgdu.llmgateway.routing;

import dev.onurgndgdu.llmgateway.config.GatewayProperties;
import dev.onurgndgdu.llmgateway.provider.LlmProvider;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resolves a caller-facing alias to a concrete provider and upstream model.
 *
 * <p>Callers ask for {@code fast} or {@code long-context}; they never name a
 * vendor model. That indirection is the whole point of the gateway: swapping
 * a vendor is a configuration change, not a change in every caller.
 *
 * <p>Fallback chains and health-aware selection arrive in phase 2. For now a
 * route resolves to exactly one provider.
 */
@Component
public class ModelRouter {

    private final Map<String, Route> routes;
    private final Map<String, LlmProvider> providers;

    public ModelRouter(GatewayProperties properties, List<LlmProvider> providers) {
        this.routes = Map.copyOf(properties.routes());
        this.providers =
                providers.stream().collect(Collectors.toMap(LlmProvider::id, Function.identity()));
    }

    public Resolved resolve(String alias) {
        Route route = routes.get(alias);
        if (route == null) {
            throw new NoRouteException(alias);
        }

        LlmProvider provider = providers.get(route.providerId());
        if (provider == null) {
            // Configuration names a provider that is not on the classpath or not
            // enabled. This is an operator error, not a caller error, and it is
            // better surfaced loudly than silently routed elsewhere.
            throw new IllegalStateException(
                    "route '%s' names unknown provider '%s'".formatted(alias, route.providerId()));
        }

        return new Resolved(provider, route.upstreamModel());
    }

    public record Resolved(LlmProvider provider, String upstreamModel) {}
}
