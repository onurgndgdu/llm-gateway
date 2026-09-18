package dev.onurgndgdu.llmgateway.api;

import dev.onurgndgdu.llmgateway.provider.ChatChunk;
import dev.onurgndgdu.llmgateway.provider.ChatRequest;
import dev.onurgndgdu.llmgateway.routing.RoutingChatService;
import jakarta.validation.Valid;
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

    private final RoutingChatService chat;

    ChatController(RoutingChatService chat) {
        this.chat = chat;
    }

    @PostMapping(path = "/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    Mono<ChatResponsePayload> complete(@Valid @RequestBody ChatRequest request) {
        return chat.complete(request).map(ChatResponsePayload::from);
    }

    @PostMapping(path = "/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ChatChunk> stream(@Valid @RequestBody ChatRequest request) {
        return chat.stream(request);
    }
}
