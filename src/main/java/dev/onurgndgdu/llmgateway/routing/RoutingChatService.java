package dev.onurgndgdu.llmgateway.routing;

import dev.onurgndgdu.llmgateway.provider.ChatChunk;
import dev.onurgndgdu.llmgateway.provider.ChatRequest;
import dev.onurgndgdu.llmgateway.provider.ChatResponse;
import dev.onurgndgdu.llmgateway.provider.ProviderException;
import dev.onurgndgdu.llmgateway.resilience.ProviderResilience;
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

    public RoutingChatService(ModelRouter router, ProviderResilience resilience) {
        this.router = router;
        this.resilience = resilience;
    }

    public Mono<ChatResponse> complete(ChatRequest request) {
        List<ModelRouter.Resolved> chain = router.resolve(request.model());
        return attempt(request, chain, 0);
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
                    if (!shouldFailOver(error)) {
                        return Mono.error(error);
                    }
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
    public Flux<ChatChunk> stream(ChatRequest request) {
        List<ModelRouter.Resolved> chain = router.resolve(request.model());
        return streamAttempt(request, chain, 0);
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
