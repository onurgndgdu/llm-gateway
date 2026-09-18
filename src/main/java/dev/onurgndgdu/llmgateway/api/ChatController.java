package dev.onurgndgdu.llmgateway.api;

import dev.onurgndgdu.llmgateway.provider.ChatChunk;
import dev.onurgndgdu.llmgateway.provider.ChatRequest;
import dev.onurgndgdu.llmgateway.provider.ProviderException;
import dev.onurgndgdu.llmgateway.routing.ModelRouter;
import jakarta.validation.Valid;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/chat")
class ChatController {

    private final ModelRouter router;

    ChatController(ModelRouter router) {
        this.router = router;
    }

    @PostMapping(path = "/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    Mono<ChatResponsePayload> complete(@Valid @RequestBody ChatRequest request) {
        var resolved = router.resolve(request.model());
        return resolved
                .provider()
                .complete(request, resolved.upstreamModel())
                .map(ChatResponsePayload::from);
    }

    @PostMapping(path = "/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ChatChunk> stream(@Valid @RequestBody ChatRequest request) {
        var resolved = router.resolve(request.model());
        AtomicBoolean sawFinalChunk = new AtomicBoolean(false);

        return resolved
                .provider()
                .stream(request, resolved.upstreamModel())
                .doOnNext(
                        chunk -> {
                            if (chunk.last()) {
                                sawFinalChunk.set(true);
                            }
                        })
                .concatWith(
                        Mono.defer(
                                () ->
                                        // A stream that ends without a final chunk was cut short.
                                        // Completing normally would hand the caller a partial answer
                                        // that looks complete, so it is surfaced as a failure instead.
                                        sawFinalChunk.get()
                                                ? Mono.empty()
                                                : Mono.error(
                                                        new ProviderException(
                                                                resolved.provider().id(),
                                                                ProviderException.Kind.TRUNCATED_STREAM,
                                                                "upstream stream ended without a final chunk"))));
    }
}
