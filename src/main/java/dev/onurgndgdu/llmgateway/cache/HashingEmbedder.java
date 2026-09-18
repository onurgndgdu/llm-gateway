package dev.onurgndgdu.llmgateway.cache;

import java.util.Locale;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * A local, deterministic embedder built on hashed token counts.
 *
 * <p>This is not a semantic model and does not pretend to be: it captures word
 * overlap, so "how do I reset my password" and "how can I reset my password"
 * land close together, while a genuine paraphrase with no shared words does
 * not. That is enough to build and test the cache against, and it keeps the
 * whole suite offline and deterministic.
 *
 * <p>Swapping in a hosted embedder changes retrieval quality and nothing else,
 * which is the property this interface exists to preserve.
 */
@Component
public class HashingEmbedder implements Embedder {

    private static final int DIMENSIONS = 256;

    @Override
    public Mono<float[]> embed(String text) {
        float[] vector = new float[DIMENSIONS];

        for (String token : text.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (token.isEmpty()) {
                continue;
            }
            int bucket = Math.floorMod(token.hashCode(), DIMENSIONS);
            vector[bucket] += 1f;
        }

        return Mono.just(normalize(vector));
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    /**
     * Scaling to unit length makes cosine similarity a dot product, and stops
     * a long prompt from looking dissimilar to a short one purely because it
     * contains more words.
     */
    private float[] normalize(float[] vector) {
        double sumOfSquares = 0;
        for (float value : vector) {
            sumOfSquares += value * value;
        }

        if (sumOfSquares == 0) {
            return vector;
        }

        float length = (float) Math.sqrt(sumOfSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= length;
        }
        return vector;
    }
}
