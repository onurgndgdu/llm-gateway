package dev.onurgndgdu.llmgateway.routing;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * An ordered list of places a model alias may be served from.
 *
 * <p>The first target is the intended one; the rest exist for the moments it
 * is unavailable. Order is the policy — the gateway walks the chain rather
 * than scoring targets, because a predictable failover path is far easier to
 * reason about during an incident than a clever one.
 */
public record Route(@NotEmpty List<Target> targets) {

    public Route {
        targets = List.copyOf(targets);
    }

    public Target primary() {
        return targets.getFirst();
    }

    public record Target(String providerId, String upstreamModel) {}
}
