package dev.onurgndgdu.llmgateway.api;

import dev.onurgndgdu.llmgateway.cache.ResponseCache;
import dev.onurgndgdu.llmgateway.provider.ChatChunk;
import dev.onurgndgdu.llmgateway.provider.ChatRequest;
import dev.onurgndgdu.llmgateway.routing.RoutingChatService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1")
class ChatController {

    /**
     * Until authentication lands, the caller simply states who it is. It is
     * enough to attribute spend and separate cache namespaces, and nothing
     * here should be mistaken for a security boundary — phase 5 replaces this
     * header with a verified key.
     */
    private static final String CALLER_HEADER = "X-Caller-Id";

    private static final String BYPASS_HEADER = "X-Cache-Bypass";
    private static final String ANONYMOUS = "anonymous";

    private final RoutingChatService chat;
    private final ResponseCache cache;

    ChatController(RoutingChatService chat, ResponseCache cache) {
        this.chat = chat;
        this.cache = cache;
    }

    @PostMapping(path = "/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    Mono<ChatResponsePayload> complete(
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(name = CALLER_HEADER, defaultValue = ANONYMOUS) String callerId,
            @RequestHeader(name = BYPASS_HEADER, defaultValue = "false") boolean bypassCache) {
        return chat.complete(request, callerId, bypassCache).map(ChatResponsePayload::from);
    }

    /**
     * Streaming never serves from cache. Replaying stored text as a stream
     * would arrive instantly and in one piece, which is a different experience
     * from the one the caller asked for, and it would report token timings
     * that never happened.
     */
    @PostMapping(path = "/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ChatChunk> stream(
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(name = CALLER_HEADER, defaultValue = ANONYMOUS) String callerId) {
        return chat.stream(request, callerId);
    }

    /** Drops everything cached for one caller. */
    @DeleteMapping("/cache")
    Mono<Map<String, Long>> invalidate(
            @RequestHeader(name = CALLER_HEADER, defaultValue = ANONYMOUS) String callerId) {
        return cache.invalidate(callerId).map(removed -> Map.of("removed", removed));
    }
}
