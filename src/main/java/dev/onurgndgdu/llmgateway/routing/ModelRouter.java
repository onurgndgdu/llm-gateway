package dev.onurgndgdu.llmgateway.routing;

import dev.onurgndgdu.llmgateway.config.GatewayProperties;
import dev.onurgndgdu.llmgateway.provider.LlmProvider;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resolves a caller-facing alias into the ordered chain of providers that may
 * serve it.
 *
 * <p>Callers ask for {@code fast} or {@code long-context} and never name a
 * vendor model. That indirection is the point of the gateway: changing vendor,
 * or adding somewhere to fail over to, is a configuration change rather than a
 * change in every caller.
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

    /** The chain to try, in order. Never empty. */
    public List<Resolved> resolve(String alias) {
        Route route = routes.get(alias);
        if (route == null) {
            throw new NoRouteException(alias);
        }

        return route.targets().stream().map(this::bind).toList();
    }

    private Resolved bind(Route.Target target) {
        LlmProvider provider = providers.get(target.providerId());
        if (provider == null) {
            // Configuration names a provider that is not registered. This is an
            // operator error; failing loudly beats quietly routing somewhere else.
            throw new IllegalStateException("unknown provider '%s'".formatted(target.providerId()));
        }
        return new Resolved(provider, target.upstreamModel());
    }

    public record Resolved(LlmProvider provider, String upstreamModel) {}
}
