package dev.onurgndgdu.llmgateway.cost;

import java.math.BigDecimal;

/**
 * What one model costs, in currency units per million tokens.
 *
 * <p>Per million rather than per token because that is how vendors publish
 * their prices, and a price table that has to be converted before it can be
 * compared to a pricing page is a price table that will drift from it.
 */
public record ModelPrice(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {}
