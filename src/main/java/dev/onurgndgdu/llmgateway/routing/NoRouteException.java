package dev.onurgndgdu.llmgateway.routing;

/** The caller asked for an alias that is not configured. */
public class NoRouteException extends RuntimeException {

    public NoRouteException(String alias) {
        super("no route configured for model '%s'".formatted(alias));
    }
}
