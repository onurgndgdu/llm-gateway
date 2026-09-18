package dev.onurgndgdu.llmgateway.cache;

import reactor.core.publisher.Mono;

/**
 * Turns text into a vector for similarity comparison.
 *
 * <p>An interface with a local implementation behind it, so the semantic cache
 * can be built and tested without an embedding API. A hosted embedder plugs in
 * later without the cache changing.
 */
public interface Embedder {

    /** Unit-length vector, so cosine similarity is a plain dot product. */
    Mono<float[]> embed(String text);

    int dimensions();
}
