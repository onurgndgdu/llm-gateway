package dev.onurgndgdu.llmgateway.cache;

import dev.onurgndgdu.llmgateway.provider.ChatRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives the cache key for a request.
 *
 * <p>Every input that can change the answer is in the key: the alias, the
 * messages in order, the temperature and the token ceiling. Leaving any of
 * them out would serve an answer produced under different parameters, a bug
 * that surfaces as an occasional inexplicable response rather than a failure.
 *
 * <p>The key is namespaced by caller. Two tenants sending an identical prompt
 * would receive identical answers anyway, so sharing would be safe and
 * cheaper, but it makes one tenant's traffic observable in another's latency
 * and lets a poisoned entry reach everyone. The saving is not worth that.
 */
final class CacheKey {

    private CacheKey() {}

    static String of(String callerId, ChatRequest request) {
        StringBuilder material = new StringBuilder();
        material.append(request.model()).append(SEPARATOR);
        material.append(request.temperature()).append(SEPARATOR);
        material.append(request.maxTokens()).append(SEPARATOR);

        for (ChatRequest.Message message : request.messages()) {
            // Separators that cannot occur in the content, so two different
            // message splits cannot collapse into the same key.
            material.append(message.role()).append(FIELD);
            material.append(message.content()).append(SEPARATOR);
        }

        return "cache:%s:%s".formatted(callerId, sha256(material.toString()));
    }

    private static final char SEPARATOR = '\u0000';
    private static final char FIELD = '\u0001';

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
