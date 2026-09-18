package dev.onurgndgdu.llmgateway.cache;

import dev.onurgndgdu.llmgateway.config.GatewayProperties;
import dev.onurgndgdu.llmgateway.provider.ChatRequest;
import dev.onurgndgdu.llmgateway.provider.ChatResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Serves an answer to a question close enough to one already asked.
 *
 * <p>Similarity is computed in the gateway over a bounded per-caller index
 * held in a Redis hash, rather than through a vector index. For the few
 * thousand entries a per-caller index holds, a linear scan is faster than the
 * round trips a real index would add, and it keeps the project runnable on
 * plain Redis. Past that size this is the wrong structure, and the interface
 * is narrow enough to replace.
 *
 * <p>The threshold is the entire risk. Set too low, callers get answers to
 * questions they did not ask, and nothing in the response tells them so. It
 * is therefore configurable, defaults conservatively, and every hit records
 * the score it matched on.
 */
@Component
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper json;
    private final Embedder embedder;
    private final GatewayProperties properties;

    public SemanticCache(
            ReactiveStringRedisTemplate redis,
            ObjectMapper json,
            Embedder embedder,
            GatewayProperties properties) {
        this.redis = redis;
        this.json = json;
        this.embedder = embedder;
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.cache().isSemanticEnabled();
    }

    public Mono<Match> lookup(String callerId, ChatRequest request) {
        if (!enabled()) {
            return Mono.empty();
        }

        return embedder
                .embed(textOf(request))
                .flatMap(query -> bestMatch(callerId, query))
                .onErrorResume(
                        error -> {
                            log.warn("semantic lookup failed, treating as a miss", error);
                            return Mono.empty();
                        });
    }

    public Mono<Void> store(String callerId, ChatRequest request, ChatResponse response) {
        if (!enabled()) {
            return Mono.empty();
        }

        String indexKey = indexKey(callerId);

        return embedder
                .embed(textOf(request))
                .flatMap(
                        vector -> {
                            Entry entry =
                                    new Entry(encode(vector), CachedAnswer.from(response));
                            return redis
                                    .opsForHash()
                                    .put(indexKey, fieldFor(request), json.writeValueAsString(entry));
                        })
                .flatMap(stored -> trim(indexKey))
                .flatMap(trimmed -> redis.expire(indexKey, properties.cache().ttl()))
                .onErrorResume(
                        error -> {
                            log.warn("semantic store failed, continuing without caching", error);
                            return Mono.just(false);
                        })
                .then();
    }

    /** How many entries a caller's index holds. Exposed for tests and metrics. */
    public Mono<Long> indexSize(String callerId) {
        return redis.opsForHash().size(indexKey(callerId));
    }

    private Mono<Match> bestMatch(String callerId, float[] query) {
        double threshold = properties.cache().similarityThreshold();

        return redis
                .<String, String>opsForHash()
                .values(indexKey(callerId))
                .map(raw -> json.readValue(raw, Entry.class))
                .map(entry -> new Match(entry.answer().toResponse(Duration.ZERO),
                        similarity(query, decode(entry.vector()))))
                .reduce((a, b) -> a.score() >= b.score() ? a : b)
                .filter(match -> match.score() >= threshold);
    }

    /**
     * Keeps the index bounded. Without this, a caller with varied traffic grows
     * the index until the scan that makes this design viable stops being cheap.
     */
    private Mono<Long> trim(String indexKey) {
        int limit = properties.cache().maxSemanticEntries();
        return redis
                .opsForHash()
                .size(indexKey)
                .flatMap(
                        size ->
                                size <= limit
                                        ? Mono.just(0L)
                                        : redis
                                                .<String, String>opsForHash()
                                                .keys(indexKey)
                                                .take(size - limit)
                                                .collectList()
                                                .flatMap(
                                                        stale ->
                                                                redis
                                                                        .opsForHash()
                                                                        .remove(indexKey, stale.toArray())));
    }

    private static double similarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    /** Only the conversation text is embedded; parameters are not prose. */
    private String textOf(ChatRequest request) {
        StringBuilder text = new StringBuilder();
        for (ChatRequest.Message message : request.messages()) {
            text.append(message.content()).append(' ');
        }
        return text.toString().trim();
    }

    private String indexKey(String callerId) {
        return "semantic:%s".formatted(callerId);
    }

    private String fieldFor(ChatRequest request) {
        return Integer.toHexString(textOf(request).hashCode());
    }

    private static String encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private static float[] decode(String encoded) {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
        float[] vector = new float[buffer.remaining() / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    /**
     * @param score the similarity it matched on, carried out so that a hit can
     *              be logged and measured rather than being indistinguishable
     *              from an exact one
     */
    public record Match(ChatResponse response, double score) {}

    record Entry(String vector, CachedAnswer answer) {}
}
