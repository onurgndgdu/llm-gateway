package dev.onurgndgdu.llmgateway.cost;

import static org.assertj.core.api.Assertions.assertThat;

import dev.onurgndgdu.llmgateway.config.GatewayProperties;
import dev.onurgndgdu.llmgateway.provider.ChatRequest;
import dev.onurgndgdu.llmgateway.provider.Usage;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CostCalculatorTest {

    private static final ModelPrice PRICE =
            new ModelPrice(new BigDecimal("3.00"), new BigDecimal("15.00"));

    private CostCalculator calculatorWith(Map<String, ModelPrice> prices) {
        return new CostCalculator(new GatewayProperties(Map.of(), null, prices, null, null));
    }

    @Test
    void chargesInputAndOutputTokensAtTheirOwnRates() {
        CostCalculator calculator = calculatorWith(Map.of("openai:gpt", PRICE));

        // 1M input at 3.00 and 1M output at 15.00.
        var cost = calculator.costOf("openai", "gpt", Usage.reported(1_000_000, 1_000_000));

        assertThat(cost.amount()).isEqualByComparingTo("18.00");
        assertThat(cost.priced()).isTrue();
        assertThat(cost.fromEstimatedTokens()).isFalse();
    }

    @Test
    void keepsSmallAmountsInsteadOfRoundingThemToZero() {
        CostCalculator calculator = calculatorWith(Map.of("openai:gpt", PRICE));

        var cost = calculator.costOf("openai", "gpt", Usage.reported(10, 5));

        // Individually negligible, but these are summed over millions of calls,
        // which is exactly where a rounded-away fraction becomes real money.
        assertThat(cost.amount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(cost.amount()).isEqualByComparingTo("0.000105");
    }

    @Test
    void anUnknownModelIsReportedAsUnpricedRatherThanFree() {
        CostCalculator calculator = calculatorWith(Map.of());

        var cost = calculator.costOf("openai", "not-in-the-table", Usage.reported(1000, 1000));

        assertThat(cost.priced()).isFalse();
        // Zero is the amount, but 'priced' is what callers must branch on: a
        // model missing from the table would otherwise consume an unlimited
        // budget while appearing to cost nothing.
        assertThat(cost.amount()).isEqualByComparingTo("0");
    }

    @Test
    void carriesThroughThatTheTokenCountsWereEstimated() {
        CostCalculator calculator = calculatorWith(Map.of("openai:gpt", PRICE));

        var cost = calculator.costOf("openai", "gpt", Usage.estimated(1000, 1000));

        assertThat(cost.fromEstimatedTokens()).isTrue();
    }

    @Test
    void estimatesTokensWhenTheProviderReportsNone() {
        TokenEstimator estimator = new TokenEstimator();
        ChatRequest request =
                new ChatRequest(
                        "alias",
                        List.of(new ChatRequest.Message(ChatRequest.Role.USER, "12345678")),
                        null,
                        null,
                        null);

        Usage usage = estimator.resolve(request, "abcd", null);

        assertThat(usage.estimated()).isTrue();
        assertThat(usage.promptTokens()).isEqualTo(6); // 8 chars / 4, plus overhead
        assertThat(usage.completionTokens()).isEqualTo(1);
    }

    @Test
    void prefersReportedUsageOverAnEstimate() {
        TokenEstimator estimator = new TokenEstimator();
        ChatRequest request =
                new ChatRequest(
                        "alias",
                        List.of(new ChatRequest.Message(ChatRequest.Role.USER, "hello")),
                        null,
                        null,
                        null);

        Usage usage = estimator.resolve(request, "world", Usage.reported(42, 7));

        assertThat(usage.estimated()).isFalse();
        assertThat(usage.promptTokens()).isEqualTo(42);
    }
}
