package dev.onurgndgdu.llmgateway.config;

import dev.onurgndgdu.llmgateway.routing.Route;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param routes caller-facing model alias to the provider and upstream model
 *               that should serve it
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(Map<String, Route> routes) {

    public GatewayProperties {
        routes = routes == null ? Map.of() : Map.copyOf(routes);
    }
}
