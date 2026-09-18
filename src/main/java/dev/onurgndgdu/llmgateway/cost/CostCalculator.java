package dev.onurgndgdu.llmgateway.cost;

import dev.onurgndgdu.llmgateway.config.GatewayProperties;
import dev.onurgndgdu.llmgateway.provider.Usage;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns token counts into money.
 *
 * <p>{@link BigDecimal} rather than double: these figures are summed across
 * millions of calls and then compared against a budget, and binary floating
 * point drifts in exactly the way that makes such a comparison untrustworthy.
 */
@Component
public class CostCalculator {

    private static final Logger log = LoggerFactory.getLogger(CostCalculator.class);
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final GatewayProperties properties;

    public CostCalculator(GatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * @return the cost, or {@link Cost#unpriced()} when no price is configured.
     *     An unknown price is reported rather than assumed to be zero: a model
     *     missing from the table would otherwise quietly consume an unlimited
     *     budget.
     */
    public Cost costOf(String providerId, String upstreamModel, Usage usage) {
        ModelPrice price = properties.priceFor(providerId, upstreamModel);

        if (price == null) {
            log.warn(
                    "no price configured for '{}:{}'; spend for this call is not being counted",
                    providerId,
                    upstreamModel);
            return Cost.unpriced();
        }

        BigDecimal input =
                price.inputPerMillion()
                        .multiply(BigDecimal.valueOf(usage.promptTokens()))
                        .divide(MILLION, MathContext.DECIMAL64);
        BigDecimal output =
                price.outputPerMillion()
                        .multiply(BigDecimal.valueOf(usage.completionTokens()))
                        .divide(MILLION, MathContext.DECIMAL64);

        return new Cost(
                input.add(output).setScale(8, RoundingMode.HALF_UP), true, usage.estimated());
    }

    /**
     * @param priced whether a price was known for this model
     * @param fromEstimatedTokens whether the token counts behind it were a guess
     */
    public record Cost(BigDecimal amount, boolean priced, boolean fromEstimatedTokens) {

        public static Cost unpriced() {
            return new Cost(BigDecimal.ZERO, false, false);
        }
    }
}
