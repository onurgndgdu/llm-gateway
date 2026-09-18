package dev.onurgndgdu.llmgateway.provider;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The single seam between the gateway and any upstream vendor.
 *
 * <p>Implementations translate the gateway's vocabulary into a vendor's API and
 * back, and normalize vendor errors into {@link ProviderException}. Nothing
 * above this interface knows which vendor answered; that is what makes routing,
 * failover and cost attribution possible without touching callers.
 *
 * <p>Implementations must not retry internally. Retry, timeout and circuit
 * breaking are policy decisions and belong to the routing layer, which can see
 * all providers at once and choose a different one.
 */
public interface LlmProvider {

    /** Stable identifier used in configuration, metrics and responses. */
    String id();

    /** Whether this provider can serve the given upstream model name. */
    boolean supports(String upstreamModel);

    Mono<ChatResponse> complete(ChatRequest request, String upstreamModel);

    Flux<ChatChunk> stream(ChatRequest request, String upstreamModel);
}
