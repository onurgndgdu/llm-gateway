package dev.onurgndgdu.llmgateway.cache;

import dev.onurgndgdu.llmgateway.config.GatewayProperties;
import dev.onurgndgdu.llmgateway.provider.ChatRequest;
import dev.onurgndgdu.llmgateway.provider.ChatResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Exact-match cache for completions.
 *
 * <p>Requests that asked for variety are not cached. A non-zero temperature is
 * the caller saying the answer should differ between calls, and serving the
 * same stored answer every time silently takes that away — the caller cannot
 * tell the difference between a model that is deterministic and a cache that
 * is lying to them.
 *
 * <p>A cache failure never fails a request. The cache exists to make things
 * cheaper and faster; letting it take the gateway down with it would invert
 * the point of having one.
 */
@Component
public class ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(ResponseCache.class);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper json;
    private final GatewayProperties properties;

    public ResponseCache(
            ReactiveStringRedisTemplate redis, ObjectMapper json, GatewayProperties properties) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    public boolean isCacheable(ChatRequest request) {
        if (!properties.cache().isEnabled()) {
            return false;
        }
        // Null means the provider default, which is not necessarily zero, so it
        // is treated as "varies" rather than assumed deterministic.
        return request.temperature() != null && request.temperature() == 0.0d;
    }

    public Mono<ChatResponse> lookup(String callerId, ChatRequest request) {
        if (!isCacheable(request)) {
            return Mono.empty();
        }

        return redis
                .opsForValue()
                .get(CacheKey.of(callerId, request))
                .map(stored -> json.readValue(stored, CachedAnswer.class))
                // Latency reported for a hit is the time this request actually
                // took, which is near zero, not what the original call took.
                .map(answer -> answer.toResponse(Duration.ZERO))
                .onErrorResume(
                        error -> {
                            log.warn("cache lookup failed, treating as a miss", error);
                            return Mono.empty();
                        });
    }

    public Mono<Void> store(String callerId, ChatRequest request, ChatResponse response) {
        if (!isCacheable(request)) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> json.writeValueAsString(CachedAnswer.from(response)))
                .flatMap(
                        payload ->
                                redis
                                        .opsForValue()
                                        .set(CacheKey.of(callerId, request), payload, properties.cache().ttl()))
                .onErrorResume(
                        error -> {
                            log.warn("cache write failed, continuing without caching", error);
                            return Mono.empty();
                        })
                .then();
    }

    public Mono<Long> invalidate(String callerId) {
        return redis.keys("cache:%s:*".formatted(callerId)).flatMap(redis::delete).reduce(0L, Long::sum);
    }
}
